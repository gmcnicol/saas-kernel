package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class IntentService {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final EvaluationStore evaluations;
    private final CedarAuthoriser cedar;
    private final TaxiPayloadValidator payloads;
    private final SemanticPackVersion semanticPack;
    private final List<ApplicabilityPolicy> policies;
    private final List<FactDerivation> derivations;
    private final Clock clock;
    private final KernelTelemetry telemetry;
    private final SemanticCompatibility compatibility;

    IntentService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            EvaluationStore evaluations,
            CedarAuthoriser cedar,
            TaxiPayloadValidator payloads,
            SemanticPackVersion semanticPack,
            List<ApplicabilityPolicy> policies,
            List<FactDerivation> derivations,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticCompatibility compatibility) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.evaluations = evaluations;
        this.cedar = cedar;
        this.payloads = payloads;
        this.semanticPack = semanticPack;
        this.policies = policies;
        this.derivations = derivations;
        this.clock = clock;
        this.telemetry = telemetry;
        this.compatibility = compatibility;
    }

    Intent accept(UUID actionOfferId, UUID intentId, CandidatePayload payload) {
        if (actionOfferId == null || intentId == null || payload == null
                || payload.priorIntentId().filter(intentId::equals).isPresent()) {
            throw new IntentRejectedException();
        }
        try {
            return transactions.execute(status -> acceptInTransaction(actionOfferId, intentId, payload));
        } catch (IntentConflictException | IntentRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IntentRejectedException();
        }
    }

    private Intent acceptInTransaction(UUID actionOfferId, UUID intentId, CandidatePayload payload) {
        TenantContext.assumeRuntimeRole(jdbc);
        String tenantId = jdbc.queryForObject(
                "SELECT kernel.resolve_action_offer_tenant(?)", String.class, actionOfferId);
        if (tenantId == null) {
            throw new IntentRejectedException();
        }
        TenantContext.useAfterRole(jdbc, tenantId);
        String requestChecksum = requestChecksum(actionOfferId, payload);
        Intent existing = existing(tenantId, intentId, requestChecksum);
        if (existing != null) {
            return existing;
        }
        payload.priorIntentId().ifPresent(priorIntentId -> {
            Boolean terminal = jdbc.queryForObject("""
                    SELECT status IN ('SUCCEEDED', 'STALE', 'FAILED')
                    FROM kernel.intent WHERE tenant_id = ? AND id = ?
                    """, Boolean.class, tenantId, priorIntentId);
            if (!Boolean.TRUE.equals(terminal)) throw new IntentRejectedException();
        });

        Offer offer = loadOffer(tenantId, actionOfferId);
        TenantContext.lockSubject(jdbc, tenantId, offer.subject());
        StoredEvaluation evaluation = evaluations.load(tenantId, offer.evaluationSnapshotId());
        Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateCurrent(tenantId, offer, evaluation, compatibility.adapt(payload), acceptedAt);
        String traceparent = payload.traceContext().map(W3cTraceContext::traceparent).orElse(null);
        String tracestate = payload.traceContext().map(W3cTraceContext::tracestate).orElse(null);
        String envelopeChecksum = checksum(canonicalStrings(
                requestChecksum,
                offer.evaluationSnapshotId().toString(),
                offer.principal().type(),
                offer.principal().id(),
                offer.subject().type(),
                offer.subject().id(),
                offer.actionId(),
                offer.policyId(),
                Long.toString(offer.stateVersion()),
                evaluation.stateChecksum(),
                evaluation.applicationId(),
                evaluation.applicationVersion(),
                evaluation.kernelVersion(),
                offer.semanticPackId(),
                offer.semanticPackChecksum(),
                offer.bundleId(),
                offer.bundleChecksum(),
                offer.authorisedAt().toString(),
                offer.correlation().toString(),
                traceparent == null ? "" : traceparent,
                tracestate == null ? "" : tracestate,
                payload.priorIntentId().map(UUID::toString).orElse(""),
                acceptedAt.toString()));
        int inserted = jdbc.update("""
                INSERT INTO kernel.intent
                    (id, tenant_id, action_offer_id, evaluation_snapshot_id,
                     subject_type, subject_id, action_id, applicability_policy_id, principal_type, principal_id,
                     expected_state_version, expected_state_checksum,
                     application_id, application_version, kernel_version,
                     semantic_pack_id, semantic_pack_checksum,
                     authorisation_bundle_id, authorisation_bundle_checksum,
                     authorised_at, authorisation_correlation,
                     payload_type, payload_version, request_checksum, envelope_checksum,
                     accepted_at, traceparent, tracestate, prior_intent_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT DO NOTHING
                """, intentId, tenantId, actionOfferId, offer.evaluationSnapshotId(),
                offer.subject().type(), offer.subject().id(), offer.actionId(),
                offer.policyId(),
                offer.principal().type(), offer.principal().id(), offer.stateVersion(), evaluation.stateChecksum(),
                evaluation.applicationId(), evaluation.applicationVersion(), evaluation.kernelVersion(),
                offer.semanticPackId(), offer.semanticPackChecksum(),
                offer.bundleId(), offer.bundleChecksum(), Timestamp.from(offer.authorisedAt()), offer.correlation(),
                payload.type(), payload.version(), requestChecksum, envelopeChecksum, Timestamp.from(acceptedAt),
                traceparent,
                tracestate,
                payload.priorIntentId().orElse(null));
        if (inserted == 0) {
            Intent raced = existing(tenantId, intentId, requestChecksum);
            if (raced != null) {
                return raced;
            }
            throw new IntentConflictException();
        }
        payload.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                jdbc.update("""
                        INSERT INTO kernel.intent_payload_value (intent_id, tenant_id, name, value)
                        VALUES (?, ?, ?, ?)
                        """, intentId, tenantId, entry.getKey(), entry.getValue()));
        jdbc.update("""
                INSERT INTO kernel.intent_audit
                    (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation)
                VALUES (?, ?, ?, 0, NULL, 'PENDING', ?, 'accepted', ?)
                """, UUID.randomUUID(), tenantId, intentId, Timestamp.from(acceptedAt), UUID.randomUUID());
        telemetry.intent(
                tenantId, offer.subject(), actionOfferId, intentId, IntentStatus.PENDING,
                KernelTelemetry.traceId(traceparent, intentId));
        return new Intent(intentId, actionOfferId, IntentStatus.PENDING, acceptedAt);
    }

    private void validateCurrent(
            String tenantId,
            Offer offer,
            StoredEvaluation evaluation,
            CandidatePayload payload,
            Instant acceptedAt) {
        List<StateVersion> current = jdbc.query("""
                SELECT version, checksum FROM kernel.projected_state_version
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                ORDER BY version DESC LIMIT 1
                """, (result, row) -> new StateVersion(result.getLong("version"), result.getString("checksum")),
                tenantId, offer.subject().type(), offer.subject().id());
        if (current.isEmpty()
                || current.getFirst().version() != offer.stateVersion()
                || !current.getFirst().checksum().equals(evaluation.stateChecksum())
                || !offer.semanticPackId().equals(semanticPack.id())
                || !offer.semanticPackChecksum().equals(semanticPack.checksum())
                || !offer.bundleId().equals(cedar.bundleId())
                || !offer.bundleChecksum().equals(cedar.bundleChecksum())) {
            throw new IntentRejectedException();
        }
        var policy = policies.stream()
                .filter(candidate -> candidate.target().equals(offer.actionId())
                        && candidate.id().equals(offer.policyId()))
                .findFirst()
                .orElseThrow(IntentRejectedException::new);
        boolean wasApplicable = evaluation.actions().stream().anyMatch(action ->
                action.actionId().equals(offer.actionId()) && action.policyId().equals(policy.id()));
        var state = new ProjectedState(tenantId, evaluation.subject(), evaluation.stateVersion(), evaluation.state());
        List<Fact> currentFacts = derivations.stream()
                .map(derivation -> java.util.Map.entry(derivation, derivation.derive(state, acceptedAt)))
                .flatMap(result -> result.getValue().values().stream()
                        .map(values -> new Fact(result.getKey().target(), result.getKey().id(), values)))
                .toList();
        if (!wasApplicable
                || !policy.isApplicable(state, currentFacts)
                || !cedar.allows(offer.principal(), evaluation.subject(), offer.actionId())) {
            throw new IntentRejectedException();
        }
        try {
            payloads.validate(offer.actionId(), payload);
        } catch (IllegalArgumentException exception) {
            throw new IntentRejectedException();
        }
    }

    private Offer loadOffer(String tenantId, UUID actionOfferId) {
        List<Offer> offers = jdbc.query("""
                SELECT evaluation_snapshot_id, principal_type, principal_id,
                       subject_type, subject_id, action_id, applicability_policy_id, state_version,
                       semantic_pack_id, semantic_pack_checksum,
                       authorisation_bundle_id, authorisation_bundle_checksum,
                       authorised_at, decision_correlation
                FROM kernel.action_offer WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new Offer(
                        result.getObject("evaluation_snapshot_id", UUID.class),
                        new Principal(result.getString("principal_type"), result.getString("principal_id")),
                        new Subject(result.getString("subject_type"), result.getString("subject_id")),
                        result.getString("action_id"), result.getString("applicability_policy_id"),
                        result.getLong("state_version"),
                        result.getString("semantic_pack_id"), result.getString("semantic_pack_checksum"),
                        result.getString("authorisation_bundle_id"),
                        result.getString("authorisation_bundle_checksum"),
                        result.getTimestamp("authorised_at").toInstant(),
                        result.getObject("decision_correlation", UUID.class)), tenantId, actionOfferId);
        if (offers.isEmpty()) {
            throw new IntentRejectedException();
        }
        return offers.getFirst();
    }

    private Intent existing(String tenantId, UUID intentId, String requestChecksum) {
        List<ExistingIntent> existing = jdbc.query("""
                SELECT action_offer_id, status, accepted_at, request_checksum, failure_reason
                FROM kernel.intent WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new ExistingIntent(
                        result.getObject("action_offer_id", UUID.class),
                        IntentStatus.valueOf(result.getString("status")),
                        result.getTimestamp("accepted_at").toInstant(),
                        result.getString("request_checksum"),
                        result.getString("failure_reason")), tenantId, intentId);
        if (existing.isEmpty()) {
            return null;
        }
        ExistingIntent found = existing.getFirst();
        if (!found.requestChecksum().equals(requestChecksum)) {
            throw new IntentConflictException();
        }
        return new Intent(intentId, found.actionOfferId(), found.status(), found.acceptedAt(),
                Optional.ofNullable(found.failureReason()).map(IntentFailureReason::valueOf));
    }

    private static String canonicalRequest(UUID actionOfferId, CandidatePayload payload) {
        StringBuilder value = new StringBuilder();
        append(value, actionOfferId.toString());
        append(value, payload.type());
        append(value, Integer.toString(payload.version()));
        append(value, payload.traceContext().map(W3cTraceContext::traceparent).orElse(""));
        append(value, payload.traceContext().map(W3cTraceContext::tracestate).orElse(""));
        append(value, payload.priorIntentId().map(UUID::toString).orElse(""));
        payload.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(value, entry.getKey());
                    append(value, entry.getValue());
                });
        return value.toString();
    }

    static String requestChecksum(UUID actionOfferId, CandidatePayload payload) {
        return checksum(canonicalRequest(actionOfferId, payload));
    }

    private static String canonicalStrings(String... values) {
        StringBuilder canonical = new StringBuilder();
        java.util.Arrays.stream(values).forEach(value -> append(canonical, value));
        return canonical.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String checksum(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Offer(
            UUID evaluationSnapshotId,
            Principal principal,
            Subject subject,
            String actionId,
            String policyId,
            long stateVersion,
            String semanticPackId,
            String semanticPackChecksum,
            String bundleId,
            String bundleChecksum,
            Instant authorisedAt,
            UUID correlation) {}

    private record StateVersion(long version, String checksum) {}

    private record ExistingIntent(
            UUID actionOfferId,
            IntentStatus status,
            Instant acceptedAt,
            String requestChecksum,
            String failureReason) {}
}
