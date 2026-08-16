package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.AuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.EvaluationSnapshot;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentAuditEntry;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentView;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.application.TypedEvaluationSnapshot;
import io.github.gmcnicol.kernel.application.TypedAuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class DefaultKernel implements Kernel {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final AuthorisationService authorisation;
    private final IntentService intents;
    private final IntentExecutionService execution;
    private final TypedActionService typedActions;
    private final IntentQueryService intentQueries;
    private final IntentWorkerProperties worker;
    private final Clock clock;
    private final ApplicationVersion applicationVersion;
    private final String kernelVersion;
    private final SemanticPackVersion semanticPackVersion;
    private final List<FactDerivation> derivations;
    private final List<ApplicabilityPolicy> policies;
    private final List<TypedFactDerivation<?, ?>> typedDerivations;
    private final List<TypedApplicabilityPolicy<?>> typedPolicies;
    private final TypedSemanticCompatibility canonical;
    private final KernelTelemetry telemetry;
    private final AtomicInteger intentQueueTurn = new AtomicInteger();

    DefaultKernel(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            AuthorisationService authorisation,
            IntentService intents,
            IntentExecutionService execution,
            TypedActionService typedActions,
            IntentQueryService intentQueries,
            IntentWorkerProperties worker,
            Clock clock,
            ApplicationVersion applicationVersion,
            String kernelVersion,
            SemanticPackVersion semanticPackVersion,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<SemanticBindings> typedBindings,
            CanonicalCodec.Limits canonicalLimits,
            KernelTelemetry telemetry) {
        this(jdbc, transactions, authorisation, intents, execution, typedActions, intentQueries, worker, clock,
                applicationVersion, kernelVersion, semanticPackVersion, derivations, policies, typedDerivations,
                typedPolicies, typedBindings,
                new TypedSemanticCompatibility(typedBindings, List.of(), canonicalLimits), telemetry);
    }

    DefaultKernel(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            AuthorisationService authorisation,
            IntentService intents,
            IntentExecutionService execution,
            TypedActionService typedActions,
            IntentQueryService intentQueries,
            IntentWorkerProperties worker,
            Clock clock,
            ApplicationVersion applicationVersion,
            String kernelVersion,
            SemanticPackVersion semanticPackVersion,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<SemanticBindings> typedBindings,
            TypedSemanticCompatibility canonical,
            KernelTelemetry telemetry) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.authorisation = authorisation;
        this.intents = intents;
        this.execution = execution;
        this.typedActions = typedActions;
        this.intentQueries = intentQueries;
        this.worker = worker;
        this.clock = clock;
        this.applicationVersion = applicationVersion;
        this.kernelVersion = kernelVersion;
        this.semanticPackVersion = semanticPackVersion;
        this.telemetry = telemetry;
        this.derivations = derivations.stream()
                .sorted(Comparator.comparing(FactDerivation::target).thenComparing(FactDerivation::id))
                .toList();
        this.policies = policies.stream()
                .sorted(Comparator.comparing(ApplicabilityPolicy::target).thenComparing(ApplicabilityPolicy::id))
                .toList();
        this.typedDerivations = typedDerivations.stream()
                .sorted(Comparator.comparing(derivation -> derivation.factType().qualifiedName()))
                .toList();
        var factKeys = new java.util.HashSet<String>();
        this.typedDerivations.forEach(derivation -> {
            String key = descriptorKey(derivation.factType());
            if (!factKeys.add(key)) throw new IllegalStateException("Duplicate typed Fact derivation: " + key);
        });
        this.typedPolicies = typedPolicies.stream()
                .sorted(Comparator.comparing((TypedApplicabilityPolicy<?> policy) -> policy.target())
                        .thenComparing(policy -> policy.id()))
                .toList();
        var descriptors = new LinkedHashMap<String, SemanticType<?>>();
        var expectedFacts = new LinkedHashMap<String, FactType<?>>();
        typedBindings.forEach(bindings -> bindings.projections().forEach(projection -> {
                addDescriptor(descriptors, projection);
            }));
        typedBindings.forEach(bindings -> bindings.facts().forEach(fact -> {
                addDescriptor(descriptors, fact);
                if (canonical.isCurrent(fact) && expectedFacts.putIfAbsent(descriptorKey(fact), fact) != null) {
                    throw new IllegalStateException("Duplicate generated Fact descriptor: " + descriptorKey(fact));
                }
                if (descriptors.get(descriptorKey(fact.projectionType())) != fact.projectionType()) {
                    throw new IllegalStateException("Fact references an unregistered Projection: " + fact.qualifiedName());
                }
            }));
        if (!expectedFacts.keySet().equals(factKeys)
                || this.typedDerivations.stream().anyMatch(
                        derivation -> expectedFacts.get(descriptorKey(derivation.factType())) != derivation.factType())) {
            throw new IllegalStateException("Typed Fact derivations do not match generated bindings");
        }
        this.typedPolicies.forEach(policy -> {
            if (descriptors.get(descriptorKey(policy.projectionType())) != policy.projectionType()) {
                throw new IllegalStateException(
                        "Typed policy references an unregistered Projection: " + policy.id());
            }
        });
        this.canonical = canonical;
    }

    @Override
    public EvaluationSnapshot evaluate(ProjectedState state, Instant evaluatedAt) {
        return telemetry.observe(
                "kernel.evaluation", () -> transactions.execute(status -> evaluateInTransaction(state, evaluatedAt)));
    }

    @Override
    public <I, P> TypedEvaluationSnapshot<I, P> evaluate(TypedProjectedState<I, P> state, Instant evaluatedAt) {
        return telemetry.observe(
                "kernel.evaluation", () -> transactions.execute(status -> evaluateTypedInTransaction(state, evaluatedAt)));
    }

    private <I, P> TypedEvaluationSnapshot<I, P> evaluateTypedInTransaction(
            TypedProjectedState<I, P> state, Instant evaluatedAt) {
        if (evaluatedAt == null) throw new IllegalArgumentException("Evaluation time must be explicit");
        canonical.requireCurrent(state.type());
        TenantContext.use(jdbc, state.tenantId());
        var legacySubject = new io.github.gmcnicol.kernel.application.Subject(
                state.subject().type().qualifiedName(), state.subject().externalId());
        TenantContext.lockSubject(jdbc, state.tenantId(), legacySubject);
        CanonicalEvidence projectionEvidence = canonical.encode(state.type(), state.value());
        persistTypedProjection(state, projectionEvidence);

        List<TypedDerivedFact> derived = typedDerivations.stream()
                .filter(derivation -> derivation.factType().projectionType() == state.type())
                .map(derivation -> derive(derivation, state.value(), evaluatedAt))
                .toList();
        var values = new LinkedHashMap<FactType<?>, Object>();
        derived.forEach(fact -> fact.value().ifPresent(value -> values.put(fact.type(), value)));
        FactSet facts = FactSet.of(values);
        List<ApplicableAction> actions = typedPolicies.stream()
                .filter(policy -> policy.projectionType() == state.type())
                .filter(policy -> applicable(policy, state.value(), facts))
                .map(policy -> new ApplicableAction(policy.target(), policy.id()))
                .toList();
        Optional<Instant> reevaluateAt = java.util.stream.Stream.concat(
                        derived.stream().flatMap(result -> result.reevaluateAt().stream()),
                        typedPolicies.stream()
                                .filter(policy -> policy.projectionType() == state.type())
                                .flatMap(policy -> nextChange(policy, state.value(), facts, evaluatedAt).stream()))
                .filter(evaluatedAt::isBefore)
                .min(Comparator.naturalOrder());
        var snapshot = new TypedEvaluationSnapshot<>(
                UUID.randomUUID(), state.tenantId(), state.subject(), state.version(), state.type(),
                projectionEvidence.checksum(), evaluatedAt, applicationVersion, kernelVersion,
                semanticPackVersion, facts, actions, reevaluateAt);
        TypedEvaluationSnapshot<I, P> persisted = persistTypedSnapshot(snapshot, derived);
        telemetry.evaluation(persisted);
        return persisted;
    }

    @SuppressWarnings("unchecked")
    private TypedDerivedFact derive(
            TypedFactDerivation<?, ?> derivation, Object projection, Instant evaluatedAt) {
        var typed = (TypedFactDerivation<Object, Object>) derivation;
        TypedFactDerivation.Result<Object> result = typed.derive(projection, evaluatedAt);
        Optional<CanonicalEvidence> evidence = result.value().map(value -> encodeFact(typed.factType(), value));
        return new TypedDerivedFact(typed.factType(), typed.id(), result.value(), evidence, result.reevaluateAt());
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
    private CanonicalEvidence encodeFact(FactType<?> type, Object value) {
        return canonical.encode((FactType<Object>) type, value);
    }

    private <I, P> void persistTypedProjection(
            TypedProjectedState<I, P> state, CanonicalEvidence evidence) {
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
            throw new IllegalArgumentException("Typed Projected State version already exists with different content");
        }
    }

    private <I, P> TypedEvaluationSnapshot<I, P> persistTypedSnapshot(
            TypedEvaluationSnapshot<I, P> snapshot, List<TypedDerivedFact> facts) {
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
            return new TypedEvaluationSnapshot<>(
                    id, snapshot.tenantId(), snapshot.subject(), snapshot.projectedStateVersion(),
                    snapshot.projectionType(), snapshot.projectedStateChecksum(), snapshot.evaluatedAt(),
                    snapshot.applicationVersion(), snapshot.kernelVersion(), snapshot.semanticPackVersion(),
                    snapshot.facts(), snapshot.applicableActions(), snapshot.reevaluateAt());
        }
        int position = 0;
        for (TypedDerivedFact fact : facts) {
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
            ApplicableAction action = snapshot.applicableActions().get(index);
            jdbc.update("""
                    INSERT INTO kernel.typed_evaluation_applicable_action
                        (snapshot_id, tenant_id, position, action_id, policy_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, snapshot.id(), snapshot.tenantId(), index, action.actionId(), action.policyId());
        });
        return snapshot;
    }

    private static void addDescriptor(Map<String, SemanticType<?>> descriptors, SemanticType<?> descriptor) {
        String key = descriptorKey(descriptor);
        SemanticType<?> previous = descriptors.putIfAbsent(key, descriptor);
        if (previous != null && previous != descriptor) {
            throw new IllegalStateException("Conflicting semantic descriptor: " + key);
        }
    }

    private static String descriptorKey(SemanticType<?> descriptor) {
        return descriptor.qualifiedName() + "@" + descriptor.contractVersion();
    }

    private record TypedDerivedFact(
            FactType<?> type,
            String derivationId,
            Optional<Object> value,
            Optional<CanonicalEvidence> evidence,
            Optional<Instant> reevaluateAt) {}

    @Override
    public AuthorisationEnvelope authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt) {
        return telemetry.observe(
                "kernel.authorisation", () -> typedActions.authorise(tenantId, snapshotId, principal, authorisedAt)
                        .orElseGet(() -> authorisation.authorise(tenantId, snapshotId, principal, authorisedAt)));
    }

    @Override
    public <I, P> TypedAuthorisationEnvelope<I, P> authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt,
            io.github.gmcnicol.kernel.application.ProjectionType<I, P> projectionType) {
        return telemetry.observe("kernel.authorisation",
                () -> typedActions.authorise(tenantId, snapshotId, principal, authorisedAt, projectionType));
    }

    @Override
    public PresentationEnvelope present(
            String tenantId, UUID snapshotId, Principal principal, Instant presentedAt) {
        return telemetry.observe(
                "kernel.authorisation", () -> authorisation.present(tenantId, snapshotId, principal, presentedAt));
    }

    @Override
    public <I, P> TypedPresentationEnvelope<I, P> present(
            String tenantId, UUID snapshotId, Principal principal, Instant presentedAt,
            io.github.gmcnicol.kernel.application.ProjectionType<I, P> projectionType) {
        return telemetry.observe("kernel.authorisation",
                () -> typedActions.present(tenantId, snapshotId, principal, presentedAt, projectionType));
    }

    @Override
    public Intent accept(UUID actionOfferId, UUID intentId, CandidatePayload payload) {
        return telemetry.observe(
                "kernel.intent.acceptance", () -> intents.accept(actionOfferId, intentId, payload));
    }

    @Override
    public <C> Intent accept(UUID actionOfferId, UUID intentId, TypedCandidatePayload<C> payload) {
        return telemetry.observe(
                "kernel.intent.acceptance", () -> typedActions.accept(actionOfferId, intentId, payload));
    }

    @Override
    public Intent accept(
            String tenantId, Principal principal, UUID actionOfferId, UUID intentId,
            TypedCandidatePayload<?> payload) {
        return telemetry.observe("kernel.intent.acceptance",
                () -> typedActions.accept(tenantId, principal, actionOfferId, intentId, payload));
    }

    @Override
    public <P, C, E> io.github.gmcnicol.kernel.application.TypedIntentEvidence<C, E> readIntentEvidence(
            String tenantId, UUID intentId,
            io.github.gmcnicol.kernel.application.ActionType<P, C, E> actionType) {
        return typedActions.readIntentEvidence(tenantId, intentId, actionType);
    }

    @Override
    public Optional<Intent> processNext(Instant processedAt) {
        if ((intentQueueTurn.getAndIncrement() & 1) == 0) {
            return typedActions.processNext(processedAt).or(() -> execution.processNext(processedAt));
        }
        return execution.processNext(processedAt).or(() -> typedActions.processNext(processedAt));
    }

    @Override
    public List<Intent> processDue(Instant processedAt) {
        return processDue(processedAt, () -> true);
    }

    List<Intent> processDue(Instant processedAt, BooleanSupplier acceptingWork) {
        var processed = new java.util.ArrayList<Intent>();
        while (processed.size() < worker.claimBatchSize() && acceptingWork.getAsBoolean()) {
            Optional<Intent> next = processNext(processedAt);
            if (next.isEmpty()) break;
            processed.add(next.orElseThrow());
        }
        return List.copyOf(processed);
    }

    @Override
    public Optional<EvaluationSnapshot> processNextReevaluation(Instant evaluatedAt) {
        return Optional.ofNullable(processReevaluation(evaluatedAt).snapshot());
    }

    boolean processNextReevaluationWork(Instant evaluatedAt) {
        return processReevaluation(evaluatedAt).claimed();
    }

    private ReevaluationOutcome processReevaluation(Instant evaluatedAt) {
        if (evaluatedAt == null) throw new IllegalArgumentException("Reevaluation time must be explicit");
        return telemetry.observe("kernel.reevaluation", () -> processReevaluationObserved(evaluatedAt));
    }

    private ReevaluationOutcome processReevaluationObserved(Instant evaluatedAt) {
        UUID token = UUID.randomUUID();
        Instant claimedAt = clock.instant();
        ReevaluationClaim claim = transactions.execute(status -> claimReevaluation(
                token, evaluatedAt, claimedAt, claimedAt.plus(worker.leaseDuration())));
        if (claim == null) {
            telemetry.reevaluation("empty", null);
            return new ReevaluationOutcome(false, null);
        }
        telemetry.reevaluation("claimed", java.time.Duration.between(claim.dueAt(), evaluatedAt));
        try {
            EvaluationSnapshot snapshot = transactions.execute(status -> reevaluate(claim, token, evaluatedAt));
            telemetry.reevaluation(snapshot == null ? "stale" : "completed", null);
            return new ReevaluationOutcome(true, snapshot);
        } catch (ReevaluationLeaseLostException ignored) {
            telemetry.reevaluation("lease_lost", null);
            return new ReevaluationOutcome(true, null);
        }
    }

    @Override
    public List<IntentView> findIntents(IntentQuery query) {
        return intentQueries.intents(query);
    }

    @Override
    public List<IntentAuditEntry> findIntentAudit(io.github.gmcnicol.kernel.application.IntentAuditQuery query) {
        return intentQueries.audit(query);
    }

    private EvaluationSnapshot evaluateInTransaction(ProjectedState state, Instant evaluatedAt) {
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("Evaluation time must be explicit");
        }
        TenantContext.use(jdbc, state.tenantId());
        TenantContext.lockSubject(jdbc, state.tenantId(), state.subject());
        EvaluationSnapshot snapshot = evaluateSnapshot(state, evaluatedAt);
        EvaluationSnapshot persisted = persist(snapshot);
        scheduleReevaluation(persisted);
        telemetry.evaluation(persisted);
        return persisted;
    }

    private EvaluationSnapshot evaluateSnapshot(ProjectedState state, Instant evaluatedAt) {
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
        Optional<Instant> reevaluateAt = java.util.stream.Stream.concat(
                        results.stream().flatMap(result -> result.reevaluateAt().stream()),
                        policies.stream().flatMap(policy -> policy.nextChange(state, facts, evaluatedAt).stream()))
                .filter(evaluatedAt::isBefore)
                .min(Comparator.naturalOrder());
        return new EvaluationSnapshot(
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
    }

    private ReevaluationClaim claimReevaluation(UUID token, Instant dueAt, Instant claimedAt, Instant claimUntil) {
        TenantContext.assumeWorkerRole(jdbc);
        List<ReevaluationClaim> claims = jdbc.query("""
                SELECT tenant_id, subject_type, subject_id, expected_state_version,
                       semantic_pack_id, semantic_pack_checksum
                FROM kernel.claim_due_reevaluation(?, ?, ?, ?)
                """, (result, row) -> new ReevaluationClaim(
                        result.getString("tenant_id"),
                        new io.github.gmcnicol.kernel.application.Subject(
                                result.getString("subject_type"), result.getString("subject_id")),
                        result.getLong("expected_state_version"), result.getString("semantic_pack_id"),
                        result.getString("semantic_pack_checksum")),
                token, Timestamp.from(dueAt), Timestamp.from(claimedAt), Timestamp.from(claimUntil));
        if (claims.isEmpty()) return null;
        ReevaluationClaim claim = claims.getFirst();
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        Instant scheduledAt = jdbc.queryForObject("""
                SELECT due_at FROM kernel.reevaluation_request
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND lease_token = ?
                """, Timestamp.class, claim.tenantId(), claim.subject().type(), claim.subject().id(), token).toInstant();
        return claim.withDueAt(scheduledAt);
    }

    private EvaluationSnapshot reevaluate(ReevaluationClaim claim, UUID token, Instant evaluatedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        TenantContext.lockSubject(jdbc, claim.tenantId(), claim.subject());
        List<Instant> leases = jdbc.query("""
                SELECT lease_until FROM kernel.reevaluation_request
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                  AND lease_token = ? AND lease_until >= ?
                FOR UPDATE
                """, (result, row) -> result.getTimestamp("lease_until").toInstant(),
                claim.tenantId(), claim.subject().type(), claim.subject().id(), token, Timestamp.from(clock.instant()));
        if (leases.isEmpty()) return null;
        ProjectedState state = execution.currentState(claim.tenantId(), claim.subject());
        if (state.version() != claim.expectedStateVersion()
                || !semanticPackVersion.id().equals(claim.semanticPackId())
                || !semanticPackVersion.checksum().equals(claim.semanticPackChecksum())) {
            jdbc.update("""
                    DELETE FROM kernel.reevaluation_request
                    WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND lease_token = ?
                    """, claim.tenantId(), claim.subject().type(), claim.subject().id(), token);
            return null;
        }
        TenantContext.use(jdbc, claim.tenantId());
        EvaluationSnapshot persisted = persist(evaluateSnapshot(state, evaluatedAt));
        Integer owned = jdbc.queryForObject("""
                SELECT count(*) FROM kernel.reevaluation_request
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                  AND lease_token = ? AND lease_until >= ?
                """, Integer.class, claim.tenantId(), claim.subject().type(), claim.subject().id(), token,
                Timestamp.from(clock.instant()));
        if (owned == null || owned != 1) {
            throw new ReevaluationLeaseLostException();
        }
        scheduleReevaluation(persisted);
        return persisted;
    }

    private void scheduleReevaluation(EvaluationSnapshot snapshot) {
        if (snapshot.reevaluateAt().isEmpty()) {
            jdbc.update("""
                    DELETE FROM kernel.reevaluation_request
                    WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                    """, snapshot.tenantId(), snapshot.subject().type(), snapshot.subject().id());
            return;
        }
        jdbc.update("""
                INSERT INTO kernel.reevaluation_request
                    (tenant_id, subject_type, subject_id, expected_state_version,
                     semantic_pack_id, semantic_pack_checksum, due_at, lease_token, lease_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                ON CONFLICT (tenant_id, subject_type, subject_id) DO UPDATE SET
                    expected_state_version = EXCLUDED.expected_state_version,
                    semantic_pack_id = EXCLUDED.semantic_pack_id,
                    semantic_pack_checksum = EXCLUDED.semantic_pack_checksum,
                    due_at = EXCLUDED.due_at,
                    lease_token = NULL,
                    lease_until = NULL
                """, snapshot.tenantId(), snapshot.subject().type(), snapshot.subject().id(),
                snapshot.projectedStateVersion(), snapshot.semanticPackVersion().id(),
                snapshot.semanticPackVersion().checksum(), Timestamp.from(snapshot.reevaluateAt().orElseThrow()));
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

    static String stateChecksum(ProjectedState state) {
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

    private record ReevaluationClaim(
            String tenantId,
            io.github.gmcnicol.kernel.application.Subject subject,
            long expectedStateVersion,
            String semanticPackId,
            String semanticPackChecksum,
            Instant dueAt) {

        ReevaluationClaim(
                String tenantId,
                io.github.gmcnicol.kernel.application.Subject subject,
                long expectedStateVersion,
                String semanticPackId,
                String semanticPackChecksum) {
            this(tenantId, subject, expectedStateVersion, semanticPackId, semanticPackChecksum, null);
        }

        ReevaluationClaim withDueAt(Instant replacement) {
            return new ReevaluationClaim(
                    tenantId, subject, expectedStateVersion, semanticPackId, semanticPackChecksum, replacement);
        }
    }

    private record ReevaluationOutcome(boolean claimed, EvaluationSnapshot snapshot) {}

    private static final class ReevaluationLeaseLostException extends RuntimeException {}
}
