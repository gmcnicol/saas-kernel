package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.AuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.EvaluationSnapshot;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class DefaultKernel implements Kernel {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final AuthorisationService authorisation;
    private final IntentService intents;
    private final ApplicationVersion applicationVersion;
    private final String kernelVersion;
    private final SemanticPackVersion semanticPackVersion;
    private final List<FactDerivation> derivations;
    private final List<ApplicabilityPolicy> policies;

    DefaultKernel(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            AuthorisationService authorisation,
            IntentService intents,
            ApplicationVersion applicationVersion,
            String kernelVersion,
            SemanticPackVersion semanticPackVersion,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.authorisation = authorisation;
        this.intents = intents;
        this.applicationVersion = applicationVersion;
        this.kernelVersion = kernelVersion;
        this.semanticPackVersion = semanticPackVersion;
        this.derivations = derivations.stream()
                .sorted(Comparator.comparing(FactDerivation::target).thenComparing(FactDerivation::id))
                .toList();
        this.policies = policies.stream()
                .sorted(Comparator.comparing(ApplicabilityPolicy::target).thenComparing(ApplicabilityPolicy::id))
                .toList();
    }

    @Override
    public EvaluationSnapshot evaluate(ProjectedState state, Instant evaluatedAt) {
        return transactions.execute(status -> evaluateInTransaction(state, evaluatedAt));
    }

    @Override
    public AuthorisationEnvelope authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt) {
        return authorisation.authorise(tenantId, snapshotId, principal, authorisedAt);
    }

    @Override
    public Intent accept(UUID actionOfferId, UUID intentId, CandidatePayload payload) {
        return intents.accept(actionOfferId, intentId, payload);
    }

    private EvaluationSnapshot evaluateInTransaction(ProjectedState state, Instant evaluatedAt) {
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("Evaluation time must be explicit");
        }
        TenantContext.use(jdbc, state.tenantId());
        String stateChecksum = persistProjectedState(state);
        List<FactDerivation.Derivation> results = derivations.stream()
                .map(derivation -> derivation.derive(state, evaluatedAt))
                .toList();
        List<Fact> facts = IntStream.range(0, derivations.size())
                .mapToObj(index -> results.get(index).values()
                        .map(values -> new Fact(
                                derivations.get(index).target(), derivations.get(index).id(), values)))
                .flatMap(Optional::stream)
                .toList();
        List<ApplicableAction> actions = policies.stream()
                .filter(policy -> policy.isApplicable(state, facts))
                .map(policy -> new ApplicableAction(policy.target(), policy.id()))
                .toList();
        Optional<Instant> reevaluateAt = results.stream()
                .flatMap(result -> result.reevaluateAt().stream())
                .min(Comparator.naturalOrder());
        var snapshot = new EvaluationSnapshot(
                UUID.randomUUID(),
                state.tenantId(),
                state.subject(),
                state.version(),
                stateChecksum,
                evaluatedAt,
                applicationVersion,
                kernelVersion,
                semanticPackVersion,
                facts,
                actions,
                reevaluateAt);
        return persist(snapshot);
    }

    private EvaluationSnapshot persist(EvaluationSnapshot snapshot) {
        int inserted = jdbc.update("""
                INSERT INTO kernel.evaluation_snapshot
                    (id, tenant_id, subject_type, subject_id, state_version, state_checksum, evaluated_at,
                     application_id, application_version, kernel_version,
                     semantic_pack_id, semantic_pack_checksum, reevaluate_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                snapshot.id(), snapshot.tenantId(), snapshot.subject().type(), snapshot.subject().id(),
                snapshot.projectedStateVersion(), snapshot.projectedStateChecksum(),
                Timestamp.from(snapshot.evaluatedAt()), snapshot.applicationVersion().id(),
                snapshot.applicationVersion().version(), snapshot.kernelVersion(), snapshot.semanticPackVersion().id(),
                snapshot.semanticPackVersion().checksum(), snapshot.reevaluateAt().map(Timestamp::from).orElse(null));
        if (inserted == 0) {
            UUID existing = jdbc.queryForObject("""
                    SELECT id FROM kernel.evaluation_snapshot
                    WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND state_version = ?
                      AND state_checksum = ? AND evaluated_at = ? AND application_id = ? AND application_version = ?
                      AND kernel_version = ? AND semantic_pack_id = ? AND semantic_pack_checksum = ?
                    """, UUID.class, snapshot.tenantId(), snapshot.subject().type(), snapshot.subject().id(),
                    snapshot.projectedStateVersion(), snapshot.projectedStateChecksum(),
                    Timestamp.from(snapshot.evaluatedAt()), snapshot.applicationVersion().id(),
                    snapshot.applicationVersion().version(), snapshot.kernelVersion(), snapshot.semanticPackVersion().id(),
                    snapshot.semanticPackVersion().checksum());
            return new EvaluationSnapshot(
                    existing,
                    snapshot.tenantId(),
                    snapshot.subject(),
                    snapshot.projectedStateVersion(),
                    snapshot.projectedStateChecksum(),
                    snapshot.evaluatedAt(),
                    snapshot.applicationVersion(),
                    snapshot.kernelVersion(),
                    snapshot.semanticPackVersion(),
                    snapshot.facts(),
                    snapshot.applicableActions(),
                    snapshot.reevaluateAt());
        }
        IntStream.range(0, snapshot.facts().size()).forEach(index -> persistFact(snapshot, index));
        IntStream.range(0, snapshot.applicableActions().size())
                .forEach(index -> persistAction(snapshot, index));
        return snapshot;
    }

    private String persistProjectedState(ProjectedState state) {
        String checksum = stateChecksum(state);
        int inserted = jdbc.update("""
                INSERT INTO kernel.projected_state_version
                    (tenant_id, subject_type, subject_id, version, checksum)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, state.tenantId(), state.subject().type(), state.subject().id(), state.version(), checksum);
        String persisted = jdbc.queryForObject("""
                SELECT checksum FROM kernel.projected_state_version
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND version = ?
                """, String.class, state.tenantId(), state.subject().type(), state.subject().id(), state.version());
        if (!checksum.equals(persisted)) {
            throw new IllegalArgumentException("Projected State version already exists with different content");
        }
        if (inserted == 1) {
            state.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                    INSERT INTO kernel.projected_state_value
                        (tenant_id, subject_type, subject_id, version, name, value)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, state.tenantId(), state.subject().type(), state.subject().id(), state.version(),
                    entry.getKey(), entry.getValue()));
        }
        return checksum;
    }

    private static String stateChecksum(ProjectedState state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, state.tenantId());
            update(digest, state.subject().type());
            update(digest, state.subject().id());
            update(digest, Long.toString(state.version()));
            state.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private void persistFact(EvaluationSnapshot snapshot, int index) {
        Fact fact = snapshot.facts().get(index);
        jdbc.update("""
                INSERT INTO kernel.evaluation_fact
                    (snapshot_id, tenant_id, position, fact_type, derivation_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.tenantId(), index, fact.type(), fact.derivationId());
        fact.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                INSERT INTO kernel.evaluation_fact_value
                    (snapshot_id, tenant_id, fact_position, name, value)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.tenantId(), index, entry.getKey(), entry.getValue()));
    }

    private void persistAction(EvaluationSnapshot snapshot, int index) {
        ApplicableAction action = snapshot.applicableActions().get(index);
        jdbc.update("""
                INSERT INTO kernel.evaluation_applicable_action
                    (snapshot_id, tenant_id, position, action_id, policy_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.tenantId(), index, action.actionId(), action.policyId());
    }
}
