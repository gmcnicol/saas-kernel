package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class IntentExecutionService {

    // ponytail: fixed first-release lease; #40 adds validated worker policy.
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final Map<String, IntentHandler> handlers;
    private final TaxiPayloadValidator payloads;
    private final Clock clock;

    IntentExecutionService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            List<IntentHandler> handlers,
            TaxiPayloadValidator payloads,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.handlers = handlers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                IntentHandler::target, handler -> handler));
        this.payloads = payloads;
        this.clock = clock;
    }

    Optional<Intent> processNext(Instant processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("Processing time must be explicit");
        }
        UUID token = UUID.randomUUID();
        Instant claimedAt = clock.instant();
        Claim claim = transactions.execute(status -> claim(token, processedAt, claimedAt));
        if (claim == null) {
            return Optional.empty();
        }
        return Optional.of(transactions.execute(status -> complete(claim, token, processedAt)));
    }

    private Claim claim(UUID token, Instant dueAt, Instant claimedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        List<Claim> claims = jdbc.query("""
                SELECT intent_id, tenant_id FROM kernel.claim_due_intent(?, ?, ?, ?, ?)
                """, (result, row) -> new Claim(
                        result.getObject("intent_id", UUID.class), result.getString("tenant_id")),
                token, Timestamp.from(dueAt), Timestamp.from(claimedAt.plus(LEASE)),
                UUID.randomUUID(), UUID.randomUUID());
        return claims.isEmpty() ? null : claims.getFirst();
    }

    private Intent complete(Claim claim, UUID token, Instant processedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredIntent stored = load(claim.intentId(), token);
        TenantContext.lockSubject(jdbc, claim.tenantId(), stored.subject());
        ProjectedState state = currentState(claim.tenantId(), stored.subject());
        Intent claimed = new Intent(stored.id(), stored.actionOfferId(), IntentStatus.CLAIMED, stored.acceptedAt());
        if (state.version() != stored.expectedStateVersion()
                || !DefaultKernel.stateChecksum(state).equals(stored.expectedStateChecksum())) {
            return rejectStale(claim, stored, token, processedAt);
        }
        IntentHandler handler = Optional.ofNullable(handlers.get(stored.actionId()))
                .orElseThrow(() -> new IllegalStateException("Missing Intent handler: " + stored.actionId()));
        List<Event> events = List.copyOf(handler.handle(claimed, stored.payload(), state));
        if (events.isEmpty()) {
            throw new IllegalStateException("Successful Intent handling must emit at least one Event");
        }

        long version = state.version();
        for (int index = 0; index < events.size(); index++) {
            Event event = events.get(index);
            payloads.validateEvent(stored.actionId(), event.type(), event.version(), event.payload());
            version++;
            persistEvent(claim, stored, event, index + 1, version, processedAt);
            persistState(new ProjectedState(claim.tenantId(), stored.subject(), version, event.resultingState()));
        }
        jdbc.update("""
                INSERT INTO kernel.reevaluation_request
                    (tenant_id, subject_type, subject_id, expected_state_version,
                     semantic_pack_id, semantic_pack_checksum, due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, subject_type, subject_id) DO UPDATE SET
                    expected_state_version = EXCLUDED.expected_state_version,
                    semantic_pack_id = EXCLUDED.semantic_pack_id,
                    semantic_pack_checksum = EXCLUDED.semantic_pack_checksum,
                    due_at = EXCLUDED.due_at
                """, claim.tenantId(), stored.subject().type(), stored.subject().id(), version,
                stored.semanticPackId(), stored.semanticPackChecksum(), Timestamp.from(processedAt));
        Instant completionTime = clock.instant();
        int completed = jdbc.update("""
                UPDATE kernel.intent SET status = 'SUCCEEDED', lease_token = NULL, lease_until = NULL,
                    completed_at = ?
                WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                """, Timestamp.from(processedAt), claim.tenantId(), stored.id(), token, Timestamp.from(completionTime));
        if (completed != 1) {
            throw new IllegalStateException("Intent lease is no longer owned");
        }
        jdbc.update("""
                INSERT INTO kernel.intent_audit
                    (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation)
                VALUES (?, ?, ?, 2, 'CLAIMED', 'SUCCEEDED', ?, ?, ?)
                """, UUID.randomUUID(), claim.tenantId(), stored.id(), Timestamp.from(processedAt),
                events.size() + " Event(s) committed", UUID.randomUUID());
        return new Intent(stored.id(), stored.actionOfferId(), IntentStatus.SUCCEEDED, stored.acceptedAt());
    }

    private Intent rejectStale(Claim claim, StoredIntent stored, UUID token, Instant processedAt) {
        int updated = jdbc.update("""
                UPDATE kernel.intent SET status = 'STALE', lease_token = NULL, lease_until = NULL, completed_at = ?
                WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                """, Timestamp.from(processedAt), claim.tenantId(), stored.id(), token, Timestamp.from(clock.instant()));
        if (updated != 1) {
            throw new IllegalStateException("Intent lease is no longer owned");
        }
        jdbc.update("""
                INSERT INTO kernel.intent_audit
                    (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation)
                VALUES (?, ?, ?, 2, 'CLAIMED', 'STALE', ?, 'projected-state-mismatch', ?)
                """, UUID.randomUUID(), claim.tenantId(), stored.id(), Timestamp.from(processedAt), UUID.randomUUID());
        return new Intent(stored.id(), stored.actionOfferId(), IntentStatus.STALE, stored.acceptedAt());
    }

    private StoredIntent load(UUID intentId, UUID token) {
        List<StoredIntent> intents = jdbc.query("""
                SELECT action_offer_id, subject_type, subject_id, action_id, payload_type, payload_version,
                       expected_state_version, expected_state_checksum,
                       semantic_pack_id, semantic_pack_checksum, accepted_at,
                       traceparent, tracestate, prior_intent_id
                FROM kernel.intent
                WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, (result, row) -> new StoredIntent(
                        intentId,
                        result.getObject("action_offer_id", UUID.class),
                        new Subject(result.getString("subject_type"), result.getString("subject_id")),
                        result.getString("action_id"),
                        result.getString("payload_type"), result.getInt("payload_version"),
                        result.getLong("expected_state_version"), result.getString("expected_state_checksum"),
                        result.getString("semantic_pack_id"), result.getString("semantic_pack_checksum"),
                        result.getTimestamp("accepted_at").toInstant(),
                        result.getString("traceparent"), result.getString("tracestate"),
                        result.getObject("prior_intent_id", UUID.class)), intentId, token);
        if (intents.isEmpty()) {
            throw new IllegalStateException("Intent lease is no longer owned");
        }
        StoredIntent intent = intents.getFirst();
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("""
                SELECT name, value FROM kernel.intent_payload_value
                WHERE intent_id = ? ORDER BY name
                """, (result, row) -> Map.entry(result.getString("name"), result.getString("value")), intentId)
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        Optional<W3cTraceContext> trace = intent.traceparent() == null
                ? Optional.empty()
                : Optional.of(new W3cTraceContext(intent.traceparent(), intent.tracestate()));
        return intent.withPayload(new CandidatePayload(
                intent.payloadType(), intent.payloadVersion(), values, trace, Optional.ofNullable(intent.priorIntentId())));
    }

    private ProjectedState currentState(String tenantId, Subject subject) {
        Long version = jdbc.queryForObject("""
                SELECT version FROM kernel.projected_state_version
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                ORDER BY version DESC LIMIT 1
                """, Long.class, tenantId, subject.type(), subject.id());
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("""
                SELECT name, value FROM kernel.projected_state_value
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND version = ? ORDER BY name
                """, (result, row) -> Map.entry(result.getString("name"), result.getString("value")),
                tenantId, subject.type(), subject.id(), version)
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return new ProjectedState(tenantId, subject, version, values);
    }

    private void persistEvent(
            Claim claim, StoredIntent intent, Event event, int sequence, long version, Instant processedAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kernel.event
                    (id, tenant_id, intent_id, sequence, subject_type, subject_id, event_type,
                     semantic_pack_id, semantic_pack_checksum, payload_version, occurred_at, resulting_state_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, claim.tenantId(), intent.id(), sequence, intent.subject().type(), intent.subject().id(),
                event.type(), intent.semanticPackId(), intent.semanticPackChecksum(), event.version(),
                Timestamp.from(processedAt), version);
        event.payload().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                INSERT INTO kernel.event_payload_value (event_id, tenant_id, name, value) VALUES (?, ?, ?, ?)
                """, eventId, claim.tenantId(), entry.getKey(), entry.getValue()));
    }

    private void persistState(ProjectedState state) {
        String checksum = DefaultKernel.stateChecksum(state);
        jdbc.update("""
                INSERT INTO kernel.projected_state_version
                    (tenant_id, subject_type, subject_id, version, checksum) VALUES (?, ?, ?, ?, ?)
                """, state.tenantId(), state.subject().type(), state.subject().id(), state.version(), checksum);
        state.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                INSERT INTO kernel.projected_state_value
                    (tenant_id, subject_type, subject_id, version, name, value) VALUES (?, ?, ?, ?, ?, ?)
                """, state.tenantId(), state.subject().type(), state.subject().id(), state.version(),
                entry.getKey(), entry.getValue()));
    }

    private record Claim(UUID intentId, String tenantId) {}

    private record StoredIntent(
            UUID id,
            UUID actionOfferId,
            Subject subject,
            String actionId,
            String payloadType,
            int payloadVersion,
            long expectedStateVersion,
            String expectedStateChecksum,
            String semanticPackId,
            String semanticPackChecksum,
            Instant acceptedAt,
            String traceparent,
            String tracestate,
            UUID priorIntentId,
            CandidatePayload payload) {

        StoredIntent(
                UUID id,
                UUID actionOfferId,
                Subject subject,
                String actionId,
                String payloadType,
                int payloadVersion,
                long expectedStateVersion,
                String expectedStateChecksum,
                String semanticPackId,
                String semanticPackChecksum,
                Instant acceptedAt,
                String traceparent,
                String tracestate,
                UUID priorIntentId) {
            this(id, actionOfferId, subject, actionId, payloadType, payloadVersion,
                    expectedStateVersion, expectedStateChecksum, semanticPackId, semanticPackChecksum,
                    acceptedAt, traceparent, tracestate, priorIntentId, null);
        }

        StoredIntent withPayload(CandidatePayload replacement) {
            return new StoredIntent(id, actionOfferId, subject, actionId, payloadType, payloadVersion,
                    expectedStateVersion, expectedStateChecksum, semanticPackId, semanticPackChecksum,
                    acceptedAt, traceparent, tracestate, priorIntentId, replacement);
        }
    }
}
