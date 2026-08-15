package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

final class IntentExecutionService {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final Map<String, IntentHandler> handlers;
    private final TaxiPayloadValidator payloads;
    private final SemanticPackVersion semanticPack;
    private final List<ApplicabilityPolicy> policies;
    private final List<FactDerivation> derivations;
    private final CedarAuthoriser cedar;
    private final IntentWorkerProperties worker;
    private final IntentInvariantValidator invariants;
    private final FatalInvariantHandler fatalInvariants;
    private final Clock clock;

    IntentExecutionService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            List<IntentHandler> handlers,
            TaxiPayloadValidator payloads,
            SemanticPackVersion semanticPack,
            List<ApplicabilityPolicy> policies,
            List<FactDerivation> derivations,
            CedarAuthoriser cedar,
            IntentWorkerProperties worker,
            IntentInvariantValidator invariants,
            FatalInvariantHandler fatalInvariants,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.handlers = handlers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                IntentHandler::target, handler -> handler));
        this.payloads = payloads;
        this.semanticPack = semanticPack;
        this.policies = List.copyOf(policies);
        this.derivations = List.copyOf(derivations);
        this.cedar = cedar;
        this.worker = worker;
        this.invariants = invariants;
        this.fatalInvariants = fatalInvariants;
        this.clock = clock;
    }

    Optional<Intent> processNext(Instant processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("Processing time must be explicit");
        }
        UUID token = UUID.randomUUID();
        Instant claimedAt = clock.instant();
        Claim claim = inTransaction(status -> claim(token, processedAt, claimedAt));
        if (claim == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(inTransaction(status -> complete(claim, token, processedAt)));
        } catch (RuntimeException exception) {
            return Optional.of(inTransaction(status -> failAttempt(claim, token, processedAt, exception)));
        }
    }

    List<Intent> processDue(Instant processedAt) {
        java.util.ArrayList<Intent> processed = new java.util.ArrayList<>();
        while (processed.size() < worker.claimBatchSize()) {
            Optional<Intent> next = processNext(processedAt);
            if (next.isEmpty()) break;
            processed.add(next.get());
        }
        return List.copyOf(processed);
    }

    private Claim claim(UUID token, Instant dueAt, Instant claimedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        List<Claim> claims = jdbc.query("""
                SELECT intent_id, tenant_id, previous_status FROM kernel.claim_due_intent(?, ?, ?, ?, ?, ?)
                """, (result, row) -> new Claim(
                        result.getObject("intent_id", UUID.class), result.getString("tenant_id"),
                        IntentStatus.valueOf(result.getString("previous_status"))),
                token, Timestamp.from(dueAt), Timestamp.from(claimedAt),
                Timestamp.from(claimedAt.plus(worker.leaseDuration())),
                UUID.randomUUID(), UUID.randomUUID());
        if (claims.isEmpty()) return null;
        Claim claim = claims.getFirst();
        invariants.transition(claim.previousStatus(), IntentStatus.CLAIMED);
        return claim;
    }

    private Intent complete(Claim claim, UUID token, Instant processedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredIntent stored = load(claim.intentId(), token);
        TenantContext.lockSubject(jdbc, claim.tenantId(), stored.subject());
        ProjectedState state = currentState(claim.tenantId(), stored.subject());
        Intent claimed = new Intent(stored.id(), stored.actionOfferId(), IntentStatus.CLAIMED, stored.acceptedAt());
        if (state.version() != stored.expectedStateVersion()
                || !DefaultKernel.stateChecksum(state).equals(stored.expectedStateChecksum())
                || !semanticPack.id().equals(stored.semanticPackId())
                || !semanticPack.checksum().equals(stored.semanticPackChecksum())) {
            return reject(claim, stored, token, processedAt, state, null, null, null,
                    IntentStatus.STALE, IntentFailureReason.STATE_OR_SEMANTIC_STALE);
        }
        if (stored.attemptCount() > worker.maximumAttempts()) {
            return reject(claim, stored, token, processedAt, state, null, null, null,
                    IntentStatus.FAILED, IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED);
        }
        ExecutionEvidence evidence = evidence(stored, state, processedAt);
        if (!evidence.applicable()) {
            return reject(claim, stored, token, processedAt, state, evidence.policy(), false, evidence.authorised(),
                    IntentStatus.FAILED, IntentFailureReason.NOT_APPLICABLE);
        }
        if (!evidence.authorised()) {
            return reject(claim, stored, token, processedAt, state, evidence.policy(), true, false,
                    IntentStatus.FAILED, IntentFailureReason.AUTHORISATION_DENIED);
        }
        IntentHandler handler = Optional.ofNullable(handlers.get(stored.actionId()))
                .orElseThrow(() -> new IllegalStateException("Missing Intent handler: " + stored.actionId()));
        List<Event> events = List.copyOf(handler.handle(claimed, stored.payload(), state));
        if (events.isEmpty()) {
            throw new IllegalStateException("Successful Intent handling must emit at least one Event");
        }
        invariants.eventSequence(java.util.stream.IntStream.rangeClosed(1, events.size()).boxed().toList());

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
        invariants.transition(IntentStatus.CLAIMED, IntentStatus.SUCCEEDED);
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
                VALUES (?, ?, ?, ?, 'CLAIMED', 'SUCCEEDED', ?, ?, ?)
                """, UUID.randomUUID(), claim.tenantId(), stored.id(), nextAuditSequence(stored.id()),
                Timestamp.from(processedAt),
                events.size() + " Event(s) committed", UUID.randomUUID());
        return new Intent(stored.id(), stored.actionOfferId(), IntentStatus.SUCCEEDED, stored.acceptedAt());
    }

    private Intent reject(
            Claim claim,
            StoredIntent stored,
            UUID token,
            Instant processedAt,
            ProjectedState state,
            ApplicabilityPolicy policy,
            Boolean applicable,
            Boolean authorised,
            IntentStatus status,
            IntentFailureReason reason) {
        invariants.transition(IntentStatus.CLAIMED, status);
        int updated = jdbc.update("""
                UPDATE kernel.intent SET status = ?, failure_reason = ?, lease_token = NULL, lease_until = NULL,
                    completed_at = ?
                WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                """, status.name(), reason.name(), Timestamp.from(processedAt), claim.tenantId(), stored.id(), token,
                Timestamp.from(clock.instant()));
        if (updated != 1) {
            throw new IllegalStateException("Intent lease is no longer owned");
        }
        jdbc.update("""
                INSERT INTO kernel.intent_audit
                    (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation,
                     failure_reason, evidence_state_version, evidence_state_checksum,
                     semantic_pack_id, semantic_pack_checksum, applicability_policy_id, applicability_result,
                     authorisation_bundle_id, authorisation_bundle_checksum,
                     authorisation_allowed, authorisation_correlation)
                VALUES (?, ?, ?, ?, 'CLAIMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), claim.tenantId(), stored.id(), nextAuditSequence(stored.id()),
                status.name(), Timestamp.from(processedAt),
                reason.name(), UUID.randomUUID(), reason.name(), state.version(), DefaultKernel.stateChecksum(state),
                semanticPack.id(), semanticPack.checksum(), policy == null ? null : policy.id(), applicable,
                cedar.bundleId(), cedar.bundleChecksum(), authorised, UUID.randomUUID());
        return new Intent(stored.id(), stored.actionOfferId(), status, stored.acceptedAt(), Optional.of(reason));
    }

    private Intent failAttempt(
            Claim claim, UUID token, Instant processedAt, RuntimeException exception) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredIntent stored = load(claim.intentId(), token);
        boolean retryable = exception instanceof RetryableIntentException
                || exception instanceof TransientDataAccessException;
        if (retryable && stored.attemptCount() < worker.maximumAttempts()) {
            Instant failedAt = clock.instant();
            invariants.transition(IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT);
            int updated = jdbc.update("""
                    UPDATE kernel.intent SET status = 'RETRY_WAIT', lease_token = NULL, lease_until = NULL,
                        next_attempt_at = ?
                    WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                    """, Timestamp.from(failedAt.plus(worker.retryBackoff())), claim.tenantId(), stored.id(), token,
                    Timestamp.from(failedAt));
            if (updated != 1) {
                throw new IllegalStateException("Intent lease is no longer owned");
            }
            jdbc.update("""
                    INSERT INTO kernel.intent_audit
                        (id, tenant_id, intent_id, sequence, from_status, to_status,
                         occurred_at, reason, correlation)
                    VALUES (?, ?, ?, ?, 'CLAIMED', 'RETRY_WAIT', ?, 'transient failure', ?)
                    """, UUID.randomUUID(), claim.tenantId(), stored.id(), nextAuditSequence(stored.id()),
                    Timestamp.from(failedAt), UUID.randomUUID());
            return new Intent(stored.id(), stored.actionOfferId(), IntentStatus.RETRY_WAIT, stored.acceptedAt());
        }
        TenantContext.lockSubject(jdbc, claim.tenantId(), stored.subject());
        ProjectedState state = currentState(claim.tenantId(), stored.subject());
        ExecutionEvidence evidence;
        try {
            evidence = evidence(stored, state, processedAt);
        } catch (RuntimeException ignored) {
            evidence = new ExecutionEvidence(null, null, null);
        }
        IntentFailureReason reason = retryable
                ? IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED
                : IntentFailureReason.DETERMINISTIC_FAILURE;
        return reject(claim, stored, token, processedAt, state, evidence.policy(),
                evidence.applicable(), evidence.authorised(), IntentStatus.FAILED, reason);
    }

    private ExecutionEvidence evidence(StoredIntent stored, ProjectedState state, Instant processedAt) {
        List<Fact> facts = derivations.stream()
                .map(derivation -> java.util.Map.entry(derivation, derivation.derive(state, processedAt)))
                .flatMap(result -> result.getValue().values().stream()
                        .map(values -> new Fact(result.getKey().target(), result.getKey().id(), values)))
                .toList();
        ApplicabilityPolicy policy = policies.stream()
                .filter(candidate -> candidate.target().equals(stored.actionId())
                        && candidate.id().equals(stored.policyId()))
                .findFirst()
                .orElse(null);
        return new ExecutionEvidence(
                policy, policy != null && policy.isApplicable(state, facts),
                cedar.allows(stored.principal(), stored.subject(), stored.actionId()));
    }

    private int nextAuditSequence(UUID intentId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), -1) + 1 FROM kernel.intent_audit WHERE intent_id = ?",
                Integer.class, intentId);
    }

    private static FatalInvariantError databaseInvariant(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("invalid Intent transition")
                    || message.contains("invalid Event sequence"))) {
                return new FatalInvariantError(message);
            }
        }
        return null;
    }

    private <T> T inTransaction(TransactionCallback<T> action) {
        try {
            return transactions.execute(action);
        } catch (FatalInvariantError error) {
            fatalInvariants.terminate(error);
            throw error;
        } catch (RuntimeException exception) {
            FatalInvariantError invariant = databaseInvariant(exception);
            if (invariant == null) throw exception;
            fatalInvariants.terminate(invariant);
            throw invariant;
        }
    }

    private StoredIntent load(UUID intentId, UUID token) {
        List<StoredIntent> intents = jdbc.query("""
                SELECT action_offer_id, subject_type, subject_id, action_id, payload_type, payload_version,
                       expected_state_version, expected_state_checksum,
                       semantic_pack_id, semantic_pack_checksum, accepted_at,
                       applicability_policy_id, principal_type, principal_id,
                       authorisation_bundle_id, authorisation_bundle_checksum, attempt_count,
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
                        result.getString("applicability_policy_id"),
                        new Principal(result.getString("principal_type"), result.getString("principal_id")),
                        result.getString("authorisation_bundle_id"),
                        result.getString("authorisation_bundle_checksum"),
                        result.getInt("attempt_count"),
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

    private record Claim(UUID intentId, String tenantId, IntentStatus previousStatus) {}

    private record ExecutionEvidence(ApplicabilityPolicy policy, Boolean applicable, Boolean authorised) {}

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
            String policyId,
            Principal principal,
            String bundleId,
            String bundleChecksum,
            int attemptCount,
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
                String policyId,
                Principal principal,
                String bundleId,
                String bundleChecksum,
                int attemptCount,
                String traceparent,
                String tracestate,
                UUID priorIntentId) {
            this(id, actionOfferId, subject, actionId, payloadType, payloadVersion,
                    expectedStateVersion, expectedStateChecksum, semanticPackId, semanticPackChecksum,
                    acceptedAt, policyId, principal, bundleId, bundleChecksum, attemptCount,
                    traceparent, tracestate, priorIntentId, null);
        }

        StoredIntent withPayload(CandidatePayload replacement) {
            return new StoredIntent(id, actionOfferId, subject, actionId, payloadType, payloadVersion,
                    expectedStateVersion, expectedStateChecksum, semanticPackId, semanticPackChecksum,
                    acceptedAt, policyId, principal, bundleId, bundleChecksum, attemptCount,
                    traceparent, tracestate, priorIntentId, replacement);
        }
    }
}
