package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentAuditEntry;
import io.github.gmcnicol.kernel.application.IntentAuditQuery;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentView;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.TypedAuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import io.github.gmcnicol.kernel.application.TypedEvaluationSnapshot;
import io.github.gmcnicol.kernel.application.TypedIntentEvidence;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class DefaultKernel implements Kernel {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final TypedActionService actions;
    private final IntentQueryService intentQueries;
    private final IntentWorkerProperties worker;
    private final Clock clock;
    private final ApplicationVersion applicationVersion;
    private final String kernelVersion;
    private final SemanticPackVersion semanticPackVersion;
    private final List<TypedFactDerivation<?, ?>> derivations;
    private final List<TypedApplicabilityPolicy<?>> policies;
    private final Map<String, ProjectionType<?, ?>> projections;
    private final SemanticCodec canonical;
    private final KernelTelemetry telemetry;

    DefaultKernel(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            TypedActionService actions,
            IntentQueryService intentQueries,
            IntentWorkerProperties worker,
            Clock clock,
            ApplicationVersion applicationVersion,
            String kernelVersion,
            SemanticPackVersion semanticPackVersion,
            List<TypedFactDerivation<?, ?>> derivations,
            List<TypedApplicabilityPolicy<?>> policies,
            List<SemanticBindings> bindings,
            SemanticCodec canonical,
            KernelTelemetry telemetry) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.actions = actions;
        this.intentQueries = intentQueries;
        this.worker = worker;
        this.clock = clock;
        this.applicationVersion = applicationVersion;
        this.kernelVersion = kernelVersion;
        this.semanticPackVersion = semanticPackVersion;
        this.canonical = canonical;
        this.telemetry = telemetry;
        this.derivations = derivations.stream()
                .sorted(Comparator.comparing(derivation -> derivation.factType().qualifiedName()))
                .toList();
        this.policies = List.copyOf(policies);
        this.projections = bindings.stream().flatMap(binding -> binding.projections().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(DefaultKernel::key, type -> type));
        validateBindings(bindings);
    }

    private void validateBindings(List<SemanticBindings> bindings) {
        var facts = new LinkedHashMap<String, FactType<?>>();
        bindings.forEach(binding -> binding.facts().forEach(fact -> {
            canonical.requireRegistered(fact);
            if (facts.putIfAbsent(key(fact), fact) != null) {
                throw new IllegalStateException("Duplicate generated Fact descriptor: " + key(fact));
            }
        }));
        var derived = new LinkedHashMap<String, FactType<?>>();
        derivations.forEach(derivation -> {
            FactType<?> fact = derivation.factType();
            if (derived.putIfAbsent(key(fact), fact) != null || facts.get(key(fact)) != fact) {
                throw new IllegalStateException("Typed Fact derivations do not match generated bindings");
            }
        });
        if (!derived.keySet().equals(facts.keySet())) {
            throw new IllegalStateException("Typed Fact derivations do not match generated bindings");
        }
        policies.forEach(policy -> {
            if (projections.get(key(policy.projectionType())) != policy.projectionType()) {
                throw new IllegalStateException("Typed policy references an unregistered Projection");
            }
        });
    }

    @Override
    public <I, P> TypedEvaluationSnapshot<I, P> evaluate(TypedProjectedState<I, P> state, Instant evaluatedAt) {
        return telemetry.observe("kernel.evaluation",
                () -> transactions.execute(status -> evaluateInTransaction(state, evaluatedAt)));
    }

    private <I, P> TypedEvaluationSnapshot<I, P> evaluateInTransaction(
            TypedProjectedState<I, P> state, Instant evaluatedAt) {
        if (state == null || evaluatedAt == null) {
            throw new IllegalArgumentException("Projected State and evaluation time are required");
        }
        canonical.requireRegistered(state.type());
        if (projections.get(key(state.type())) != state.type()) {
            throw new IllegalArgumentException("Projection descriptor is not generated");
        }
        TenantContext.use(jdbc, state.tenantId());
        TenantContext.lockSubject(jdbc, state.tenantId(), new Subject(
                state.subject().type().qualifiedName(), state.subject().externalId()));
        CanonicalEvidence projection = canonical.encode(state.type(), state.value());
        persistProjection(state, projection);

        List<DerivedFact> derived = derivations.stream()
                .filter(derivation -> derivation.factType().projectionType() == state.type())
                .map(derivation -> derive(derivation, state.value(), evaluatedAt))
                .toList();
        var factValues = new LinkedHashMap<FactType<?>, Object>();
        derived.forEach(fact -> fact.value().ifPresent(value -> factValues.put(fact.type(), value)));
        FactSet facts = FactSet.of(factValues);
        var applicable = new ArrayList<ActionType<P, ?, ?>>();
        policies.stream()
                .filter(policy -> policy.projectionType() == state.type())
                .filter(policy -> applicable(policy, state.value(), facts))
                .forEach(policy -> applicable.add(action(policy)));
        Optional<Instant> reevaluateAt = java.util.stream.Stream.concat(
                        derived.stream().flatMap(result -> result.reevaluateAt().stream()),
                        policies.stream().filter(policy -> policy.projectionType() == state.type())
                                .flatMap(policy -> nextChange(policy, state.value(), facts, evaluatedAt).stream()))
                .filter(evaluatedAt::isBefore)
                .min(Comparator.naturalOrder());
        var snapshot = new TypedEvaluationSnapshot<>(
                UUID.randomUUID(), state.tenantId(), state.subject(), state.version(), state.type(),
                projection.checksum(), evaluatedAt, applicationVersion, kernelVersion,
                semanticPackVersion, facts, List.copyOf(applicable), reevaluateAt);
        TypedEvaluationSnapshot<I, P> persisted = persistSnapshot(snapshot, derived);
        telemetry.evaluation(persisted);
        return persisted;
    }

    @SuppressWarnings("unchecked")
    private DerivedFact derive(TypedFactDerivation<?, ?> derivation, Object projection, Instant evaluatedAt) {
        var typed = (TypedFactDerivation<Object, Object>) derivation;
        TypedFactDerivation.Result<Object> result = typed.derive(projection, evaluatedAt);
        Optional<CanonicalEvidence> evidence = result.value().map(value -> canonical.encode(typed.factType(), value));
        return new DerivedFact(typed.factType(), typed.id(), result.value(), evidence, result.reevaluateAt());
    }

    @SuppressWarnings("unchecked")
    private static boolean applicable(TypedApplicabilityPolicy<?> policy, Object projection, FactSet facts) {
        return ((TypedApplicabilityPolicy<Object>) policy).isApplicable(projection, facts);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Instant> nextChange(
            TypedApplicabilityPolicy<?> policy, Object projection, FactSet facts, Instant evaluatedAt) {
        return ((TypedApplicabilityPolicy<Object>) policy).nextChange(projection, facts, evaluatedAt);
    }

    @SuppressWarnings("unchecked")
    private static <P> ActionType<P, ?, ?> action(TypedApplicabilityPolicy<?> policy) {
        return (ActionType<P, ?, ?>) policy.actionType();
    }

    private <I, P> void persistProjection(TypedProjectedState<I, P> state, CanonicalEvidence evidence) {
        int inserted = jdbc.update("""
                INSERT INTO kernel.typed_projected_state
                    (tenant_id, subject_type, subject_id, state_version, projection_type,
                     contract_version, format_version, content, checksum)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, state.tenantId(), state.subject().type().qualifiedName(), state.subject().externalId(),
                state.version(), evidence.qualifiedType(), evidence.contractVersion(), evidence.formatVersion(),
                evidence.canonicalJson(), evidence.checksum());
        String persisted = jdbc.queryForObject("""
                SELECT checksum FROM kernel.typed_projected_state
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND state_version = ?
                  AND projection_type = ? AND contract_version = ?
                """, String.class, state.tenantId(), state.subject().type().qualifiedName(),
                state.subject().externalId(), state.version(), evidence.qualifiedType(), evidence.contractVersion());
        if (inserted == 0 && !evidence.checksum().equals(persisted)) {
            throw new IllegalArgumentException("Projected State version already exists with different content");
        }
    }

    private <I, P> TypedEvaluationSnapshot<I, P> persistSnapshot(
            TypedEvaluationSnapshot<I, P> snapshot, List<DerivedFact> facts) {
        int inserted = jdbc.update("""
                INSERT INTO kernel.typed_evaluation_snapshot
                    (id, tenant_id, subject_type, subject_id, state_version, projection_type,
                     projection_contract_version, state_checksum, evaluated_at, application_id,
                     application_version, kernel_version, semantic_pack_id, semantic_pack_checksum, reevaluate_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, snapshot.id(), snapshot.tenantId(), snapshot.subject().type().qualifiedName(),
                snapshot.subject().externalId(), snapshot.projectedStateVersion(),
                snapshot.projectionType().qualifiedName(), snapshot.projectionType().contractVersion(),
                snapshot.projectedStateChecksum(), Timestamp.from(snapshot.evaluatedAt()),
                snapshot.applicationVersion().id(), snapshot.applicationVersion().version(), snapshot.kernelVersion(),
                snapshot.semanticPackVersion().id(), snapshot.semanticPackVersion().checksum(),
                snapshot.reevaluateAt().map(Timestamp::from).orElse(null));
        if (inserted == 0) {
            UUID id = jdbc.queryForObject("""
                    SELECT id FROM kernel.typed_evaluation_snapshot
                    WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND state_version = ?
                      AND projection_type = ? AND projection_contract_version = ? AND state_checksum = ?
                      AND evaluated_at = ? AND application_id = ? AND application_version = ?
                      AND kernel_version = ? AND semantic_pack_id = ? AND semantic_pack_checksum = ?
                    """, UUID.class, snapshot.tenantId(), snapshot.subject().type().qualifiedName(),
                    snapshot.subject().externalId(), snapshot.projectedStateVersion(),
                    snapshot.projectionType().qualifiedName(), snapshot.projectionType().contractVersion(),
                    snapshot.projectedStateChecksum(), Timestamp.from(snapshot.evaluatedAt()),
                    snapshot.applicationVersion().id(), snapshot.applicationVersion().version(),
                    snapshot.kernelVersion(), snapshot.semanticPackVersion().id(),
                    snapshot.semanticPackVersion().checksum());
            return new TypedEvaluationSnapshot<>(id, snapshot.tenantId(), snapshot.subject(),
                    snapshot.projectedStateVersion(), snapshot.projectionType(), snapshot.projectedStateChecksum(),
                    snapshot.evaluatedAt(), snapshot.applicationVersion(), snapshot.kernelVersion(),
                    snapshot.semanticPackVersion(), snapshot.facts(), snapshot.applicableActions(),
                    snapshot.reevaluateAt());
        }
        int position = 0;
        for (DerivedFact fact : facts) {
            if (fact.evidence().isEmpty()) continue;
            CanonicalEvidence evidence = fact.evidence().orElseThrow();
            jdbc.update("""
                    INSERT INTO kernel.typed_evaluation_fact
                        (snapshot_id, tenant_id, position, fact_type, contract_version,
                         format_version, derivation_id, content, checksum)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.id(), snapshot.tenantId(), position++, evidence.qualifiedType(),
                    evidence.contractVersion(), evidence.formatVersion(), fact.derivationId(),
                    evidence.canonicalJson(), evidence.checksum());
        }
        IntStream.range(0, snapshot.applicableActions().size()).forEach(index -> {
            ActionType<P, ?, ?> action = snapshot.applicableActions().get(index);
            jdbc.update("""
                    INSERT INTO kernel.typed_evaluation_applicable_action
                        (snapshot_id, tenant_id, position, action_id, policy_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, snapshot.id(), snapshot.tenantId(), index, action.qualifiedName(),
                    action.qualifiedName() + ".applicability");
        });
        return snapshot;
    }

    @Override
    public <I, P> TypedAuthorisationEnvelope<I, P> authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt,
            ProjectionType<I, P> projectionType) {
        return telemetry.observe("kernel.authorisation",
                () -> actions.authorise(tenantId, snapshotId, principal, authorisedAt, projectionType));
    }

    @Override
    public <I, P> TypedPresentationEnvelope<I, P> present(
            String tenantId, UUID snapshotId, Principal principal, Instant presentedAt,
            ProjectionType<I, P> projectionType) {
        return telemetry.observe("kernel.authorisation",
                () -> actions.present(tenantId, snapshotId, principal, presentedAt, projectionType));
    }

    @Override
    public <C> Intent accept(UUID actionOfferId, UUID intentId, TypedCandidatePayload<C> payload) {
        return telemetry.observe("kernel.intent.acceptance", () -> actions.accept(actionOfferId, intentId, payload));
    }

    @Override
    public Intent accept(
            String tenantId, Principal principal, UUID actionOfferId, UUID intentId,
            TypedCandidatePayload<?> payload) {
        return telemetry.observe("kernel.intent.acceptance",
                () -> actions.accept(tenantId, principal, actionOfferId, intentId, payload));
    }

    @Override
    public <P, C, E> TypedIntentEvidence<C, E> readIntentEvidence(
            String tenantId, UUID intentId, ActionType<P, C, E> actionType) {
        return actions.readIntentEvidence(tenantId, intentId, actionType);
    }

    @Override
    public Optional<Intent> processNext(Instant processedAt) {
        return actions.processNext(processedAt);
    }

    @Override
    public List<Intent> processDue(Instant processedAt) {
        return processDue(processedAt, () -> true);
    }

    List<Intent> processDue(Instant processedAt, BooleanSupplier acceptingWork) {
        var processed = new ArrayList<Intent>();
        while (processed.size() < worker.claimBatchSize() && acceptingWork.getAsBoolean()) {
            Optional<Intent> next = actions.processNext(processedAt);
            if (next.isEmpty()) break;
            processed.add(next.orElseThrow());
        }
        return List.copyOf(processed);
    }

    @Override
    public Optional<TypedEvaluationSnapshot<?, ?>> processNextReevaluation(Instant evaluatedAt) {
        return Optional.ofNullable(processReevaluation(evaluatedAt).snapshot());
    }

    boolean processNextReevaluationWork(Instant evaluatedAt) {
        return processReevaluation(evaluatedAt).claimed();
    }

    private ReevaluationOutcome processReevaluation(Instant evaluatedAt) {
        if (evaluatedAt == null) throw new IllegalArgumentException("Evaluation time must be explicit");
        return telemetry.observe("kernel.reevaluation",
                () -> transactions.execute(status -> reevaluateInTransaction(evaluatedAt)));
    }

    private ReevaluationOutcome reevaluateInTransaction(Instant evaluatedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        List<ReevaluationClaim> claims = jdbc.query("""
                SELECT snapshot_id, tenant_id, due_at
                FROM kernel.claim_due_typed_reevaluation(?)
                """, (result, row) -> new ReevaluationClaim(
                        result.getObject("snapshot_id", UUID.class), result.getString("tenant_id"),
                        result.getTimestamp("due_at").toInstant()), Timestamp.from(evaluatedAt));
        if (claims.isEmpty()) {
            telemetry.reevaluation("empty", null);
            return new ReevaluationOutcome(false, null);
        }
        ReevaluationClaim claim = claims.getFirst();
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredProjection stored = jdbc.queryForObject("""
                SELECT snapshot.id, snapshot.tenant_id, snapshot.subject_type, snapshot.subject_id,
                       snapshot.state_version, snapshot.projection_type, snapshot.projection_contract_version,
                       snapshot.state_checksum, snapshot.semantic_pack_id,
                       snapshot.semantic_pack_checksum, state.format_version, state.content
                FROM kernel.typed_evaluation_snapshot snapshot
                JOIN kernel.typed_projected_state state
                  ON state.tenant_id = snapshot.tenant_id AND state.subject_type = snapshot.subject_type
                 AND state.subject_id = snapshot.subject_id AND state.state_version = snapshot.state_version
                 AND state.projection_type = snapshot.projection_type
                 AND state.contract_version = snapshot.projection_contract_version
                 AND state.checksum = snapshot.state_checksum
                WHERE snapshot.id = ?
                """, (result, row) -> new StoredProjection(
                        result.getObject("id", UUID.class), result.getString("tenant_id"),
                        result.getString("subject_type"), result.getString("subject_id"),
                        result.getLong("state_version"), result.getString("projection_type"),
                        result.getInt("projection_contract_version"), result.getString("state_checksum"),
                        claim.dueAt(), result.getString("semantic_pack_id"),
                        result.getString("semantic_pack_checksum"), result.getInt("format_version"),
                        result.getString("content")), claim.id());
        telemetry.reevaluation("claimed", java.time.Duration.between(stored.dueAt(), evaluatedAt));
        ProjectionType<?, ?> type = projections.get(stored.projectionType() + "@" + stored.projectionVersion());
        Long latest = jdbc.queryForObject("""
                SELECT max(state_version) FROM kernel.typed_projected_state
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                  AND projection_type = ? AND contract_version = ?
                """, Long.class, stored.tenantId(), stored.subjectType(), stored.subjectId(),
                stored.projectionType(), stored.projectionVersion());
        if (type == null || !semanticPackVersion.id().equals(stored.semanticPackId())
                || !semanticPackVersion.checksum().equals(stored.semanticPackChecksum())
                || !java.util.Objects.equals(latest, stored.version())) {
            telemetry.reevaluation("stale", null);
            return new ReevaluationOutcome(true, null);
        }
        CanonicalEvidence evidence = new CanonicalEvidence(
                stored.projectionType(), stored.projectionVersion(), stored.formatVersion(),
                stored.content().getBytes(StandardCharsets.UTF_8), stored.checksum());
        TypedEvaluationSnapshot<?, ?> snapshot = evaluateStored(stored, type, evidence, evaluatedAt);
        telemetry.reevaluation("completed", null);
        return new ReevaluationOutcome(true, snapshot);
    }

    @SuppressWarnings("unchecked")
    private <I, P> TypedEvaluationSnapshot<I, P> evaluateStored(
            StoredProjection stored, ProjectionType<?, ?> descriptor,
            CanonicalEvidence evidence, Instant evaluatedAt) {
        ProjectionType<I, P> type = (ProjectionType<I, P>) descriptor;
        P projection = canonical.decode(type, evidence);
        I subject = type.subjectType().fromExternalId(stored.subjectId());
        return evaluateInTransaction(new TypedProjectedState<>(
                stored.tenantId(), new TypedSubject<>(type.subjectType(), subject), stored.version(), type, projection),
                evaluatedAt);
    }

    @Override
    public List<IntentView> findIntents(IntentQuery query) {
        return intentQueries.intents(query);
    }

    @Override
    public List<IntentAuditEntry> findIntentAudit(IntentAuditQuery query) {
        return intentQueries.audit(query);
    }

    private static String key(SemanticType<?> descriptor) {
        return descriptor.qualifiedName() + "@" + descriptor.contractVersion();
    }

    private record DerivedFact(
            FactType<?> type, String derivationId, Optional<Object> value,
            Optional<CanonicalEvidence> evidence, Optional<Instant> reevaluateAt) {}

    private record StoredProjection(
            UUID id, String tenantId, String subjectType, String subjectId, long version,
            String projectionType, int projectionVersion, String checksum, Instant dueAt,
            String semanticPackId, String semanticPackChecksum, int formatVersion, String content) {}

    private record ReevaluationOutcome(boolean claimed, TypedEvaluationSnapshot<?, ?> snapshot) {}

    private record ReevaluationClaim(UUID id, String tenantId, Instant dueAt) {}
}
