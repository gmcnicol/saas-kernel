package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.Application;
import io.github.gmcnicol.kernel.application.EvaluationSnapshot;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.SemanticPackIdentity;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class KernelApplication implements Application {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final String applicationId;
    private final SemanticPackIdentity semanticPack;
    private final List<FactDerivation> derivations;
    private final List<ApplicabilityPolicy> policies;

    KernelApplication(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            String applicationId,
            SemanticPackIdentity semanticPack,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.applicationId = applicationId;
        this.semanticPack = semanticPack;
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

    private EvaluationSnapshot evaluateInTransaction(ProjectedState state, Instant evaluatedAt) {
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("Evaluation time must be explicit");
        }
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
                state.subject(),
                state.version(),
                evaluatedAt,
                applicationId,
                semanticPack,
                facts,
                actions,
                reevaluateAt);
        return persist(state.tenantId(), snapshot);
    }

    private EvaluationSnapshot persist(String tenantId, EvaluationSnapshot snapshot) {
        int inserted = jdbc.update("""
                INSERT INTO kernel.evaluation_snapshot
                    (id, tenant_id, subject, state_version, evaluated_at, application_id,
                     semantic_pack_id, semantic_pack_checksum, reevaluate_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                snapshot.id(), tenantId, snapshot.subject(), snapshot.projectedStateVersion(),
                Timestamp.from(snapshot.evaluatedAt()), snapshot.applicationId(), snapshot.semanticPack().id(),
                snapshot.semanticPack().checksum(), snapshot.reevaluateAt().map(Timestamp::from).orElse(null));
        if (inserted == 0) {
            UUID existing = jdbc.queryForObject("""
                    SELECT id FROM kernel.evaluation_snapshot
                    WHERE tenant_id = ? AND subject = ? AND state_version = ? AND evaluated_at = ?
                      AND application_id = ? AND semantic_pack_id = ? AND semantic_pack_checksum = ?
                    """, UUID.class, tenantId, snapshot.subject(), snapshot.projectedStateVersion(),
                    Timestamp.from(snapshot.evaluatedAt()), snapshot.applicationId(), snapshot.semanticPack().id(),
                    snapshot.semanticPack().checksum());
            return new EvaluationSnapshot(
                    existing,
                    snapshot.subject(),
                    snapshot.projectedStateVersion(),
                    snapshot.evaluatedAt(),
                    snapshot.applicationId(),
                    snapshot.semanticPack(),
                    snapshot.facts(),
                    snapshot.applicableActions(),
                    snapshot.reevaluateAt());
        }
        IntStream.range(0, snapshot.facts().size()).forEach(index -> persistFact(tenantId, snapshot, index));
        IntStream.range(0, snapshot.applicableActions().size())
                .forEach(index -> persistAction(tenantId, snapshot, index));
        return snapshot;
    }

    private void persistFact(String tenantId, EvaluationSnapshot snapshot, int index) {
        Fact fact = snapshot.facts().get(index);
        jdbc.update("""
                INSERT INTO kernel.evaluation_fact
                    (snapshot_id, tenant_id, position, fact_type, derivation_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), tenantId, index, fact.type(), fact.derivationId());
        fact.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                INSERT INTO kernel.evaluation_fact_value
                    (snapshot_id, tenant_id, fact_position, name, value)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), tenantId, index, entry.getKey(), entry.getValue()));
    }

    private void persistAction(String tenantId, EvaluationSnapshot snapshot, int index) {
        ApplicableAction action = snapshot.applicableActions().get(index);
        jdbc.update("""
                INSERT INTO kernel.evaluation_applicable_action
                    (snapshot_id, tenant_id, position, action_id, policy_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), tenantId, index, action.actionId(), action.policyId());
    }
}
