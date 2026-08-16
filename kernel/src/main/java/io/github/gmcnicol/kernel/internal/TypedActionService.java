package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import io.github.gmcnicol.kernel.application.TypedActionOffer;
import io.github.gmcnicol.kernel.application.TypedAuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedFact;
import io.github.gmcnicol.kernel.application.TypedFieldValue;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.application.TypedStateTransition;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.application.TypedTransitionProvenance;
import io.github.gmcnicol.kernel.application.TypedIntentEvidence;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** Typed Action offer, acceptance and execution path. */
final class TypedActionService {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final CedarAuthoriser cedar;
    private final IntentWorkerProperties worker;
    private final Clock clock;
    private final KernelTelemetry telemetry;
    private final SemanticPackVersion semanticPack;
    private final SemanticCodec canonical;
    private final Map<String, ActionType<?, ?, ?>> actions;
    private final Map<String, FactType<?>> factTypes;
    private final Map<String, TypedIntentHandler<?, ?, ?>> handlers;
    private final Map<String, TypedEventProjector<?, ?>> projectors;
    private final List<TypedApplicabilityPolicy<?>> policies;
    private final List<TypedFactDerivation<?, ?>> derivations;

    TypedActionService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            CedarAuthoriser cedar,
            IntentWorkerProperties worker,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticPackVersion semanticPack,
            List<SemanticBindings> bindings,
            List<TypedIntentHandler<?, ?, ?>> handlers,
            List<TypedEventProjector<?, ?>> projectors,
            List<TypedApplicabilityPolicy<?>> policies,
            List<TypedFactDerivation<?, ?>> derivations,
            CanonicalCodec.Limits limits) {
        this(jdbc, transactions, cedar, worker, clock, telemetry, semanticPack, bindings, handlers, projectors,
                policies, derivations, new SemanticCodec(bindings, limits));
    }

    TypedActionService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            CedarAuthoriser cedar,
            IntentWorkerProperties worker,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticPackVersion semanticPack,
            List<SemanticBindings> bindings,
            List<TypedIntentHandler<?, ?, ?>> handlers,
            List<TypedEventProjector<?, ?>> projectors,
            List<TypedApplicabilityPolicy<?>> policies,
            List<TypedFactDerivation<?, ?>> derivations,
            SemanticCodec canonical) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cedar = cedar;
        this.worker = worker;
        this.clock = clock;
        this.telemetry = telemetry;
        this.semanticPack = semanticPack;
        this.canonical = canonical;
        this.actions = bindings.stream().flatMap(binding -> binding.actions().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ActionType::qualifiedName, action -> action));
        this.factTypes = bindings.stream().flatMap(binding -> binding.facts().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(TypedActionService::key, fact -> fact));
        this.handlers = handlers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                handler -> handler.actionType().qualifiedName(), handler -> handler));
        this.projectors = projectors.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                projector -> projector.eventType().qualifiedName(), projector -> projector));
        this.policies = List.copyOf(policies);
        this.derivations = List.copyOf(derivations);
        var descriptors = new LinkedHashMap<String, SemanticType<?>>();
        bindings.forEach(binding -> {
            binding.projections().forEach(type -> addDescriptor(descriptors, type));
            binding.facts().forEach(type -> addDescriptor(descriptors, type));
            binding.candidates().forEach(type -> addDescriptor(descriptors, type));
            binding.events().forEach(type -> addDescriptor(descriptors, type));
        });
        this.actions.values().forEach(action -> {
            cedar.model(action.projectionType());
            requireIdentity(descriptors.get(key(action.projectionType())), action.projectionType(), "Action Projection");
            requireIdentity(descriptors.get(key(action.candidateType())), action.candidateType(), "Action Candidate");
            action.eventTypes().forEach(event ->
                    requireIdentity(descriptors.get(key(event)), event, "Action Event"));
        });
        handlers.forEach(handler -> requireIdentity(
                this.actions.get(handler.actionType().qualifiedName()), handler.actionType(), "handler Action"));
        projectors.forEach(projector -> requireIdentity(
                descriptors.get(key(projector.eventType())), projector.eventType(), "projector Event"));
        policies.forEach(policy -> {
            ActionType<?, ?, ?> action = this.actions.get(policy.actionType().qualifiedName());
            if (action != policy.actionType()) {
                throw new IllegalStateException("Typed applicability policy is not bound to a generated Action");
            }
        });
        this.actions.values().forEach(action -> {
            List<TypedApplicabilityPolicy<?>> actionPolicies = policies.stream()
                    .filter(policy -> policy.actionType() == action)
                    .toList();
            if (actionPolicies.size() != 1) {
                throw new IllegalStateException(
                        "Generated Action requires exactly one applicability binding: " + action.qualifiedName());
            }
            requireIdentity(this.handlers.containsKey(action.qualifiedName())
                    ? this.handlers.get(action.qualifiedName()).actionType() : null, action, "handler Action");
            action.eventTypes().forEach(event -> requireIdentity(
                    this.projectors.containsKey(event.qualifiedName())
                            ? this.projectors.get(event.qualifiedName()).eventType() : null,
                    event, "projector Event"));
        });
    }

    <P, C, E> TypedIntentEvidence<C, E> readIntentEvidence(
            String tenantId, UUID intentId, ActionType<P, C, E> actionType) {
        if (intentId == null || actionType == null
                || actions.get(actionType.qualifiedName()) != actionType) {
            throw new IllegalArgumentException("Intent evidence identity is required");
        }
        canonical.requireRegistered(actionType.candidateType());
        actionType.eventTypes().forEach(canonical::requireRegistered);
        return transactions.execute(status -> readIntentEvidenceInTransaction(
                tenantId, intentId, actionType));
    }

    private <P, C, E> TypedIntentEvidence<C, E> readIntentEvidenceInTransaction(
            String tenantId, UUID intentId, ActionType<P, C, E> actionType) {
        TenantContext.use(jdbc, tenantId);
        List<StoredCanonicalCandidate> candidates = jdbc.query("""
                SELECT action_id, payload_type, payload_contract_version, payload_format_version,
                       payload_content, payload_checksum
                FROM kernel.typed_intent WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new StoredCanonicalCandidate(
                        result.getString("action_id"), new CanonicalEvidence(
                                result.getString("payload_type"), result.getInt("payload_contract_version"),
                                result.getInt("payload_format_version"),
                                result.getString("payload_content").getBytes(StandardCharsets.UTF_8),
                                result.getString("payload_checksum"))), tenantId, intentId);
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Intent evidence is missing or ambiguous");
        }
        StoredCanonicalCandidate stored = candidates.getFirst();
        requireAction(stored.actionId(), actionType);
        C candidate = canonical.decode(stored.evidence(), actionType.candidateType());
        List<E> events = jdbc.query("""
                SELECT event_type, event_contract_version, event_format_version,
                       event_content, event_checksum
                FROM kernel.typed_event WHERE tenant_id = ? AND intent_id = ? ORDER BY sequence
                """, (result, row) -> canonical.decodeEvent(new CanonicalEvidence(
                        result.getString("event_type"), result.getInt("event_contract_version"),
                        result.getInt("event_format_version"),
                        result.getString("event_content").getBytes(StandardCharsets.UTF_8),
                        result.getString("event_checksum")), actionType.eventTypes()), tenantId, intentId);
        return new TypedIntentEvidence<>(candidate, events);
    }

    private static void requireAction(String storedActionId, ActionType<?, ?, ?> actionType) {
        if (!actionType.qualifiedName().equals(storedActionId)) {
            throw new IllegalArgumentException("Intent evidence Action does not match generated descriptor");
        }
    }

    <I, P> TypedAuthorisationEnvelope<I, P> authorise(
            String tenantId,
            UUID snapshotId,
            Principal principal,
            Instant authorisedAt,
            ProjectionType<I, P> projectionType) {
        if (snapshotId == null || principal == null || authorisedAt == null || projectionType == null) {
            throw new io.github.gmcnicol.kernel.application.AuthorisationDeniedException();
        }
        try {
            return transactions.execute(status -> authoriseTypedInTransaction(
                    tenantId, snapshotId, principal, authorisedAt, projectionType));
        } catch (io.github.gmcnicol.kernel.application.AuthorisationDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new io.github.gmcnicol.kernel.application.AuthorisationDeniedException();
        }
    }

    <I, P> TypedPresentationEnvelope<I, P> present(
            String tenantId,
            UUID snapshotId,
            Principal principal,
            Instant presentedAt,
            ProjectionType<I, P> projectionType) {
        TypedAuthorisationEnvelope<I, P> authorised = authorise(
                tenantId, snapshotId, principal, presentedAt, projectionType);
        return new TypedPresentationEnvelope<>(
                1, authorised.subject(), projectionType, authorised.evaluationSnapshotId(),
                authorised.evaluatedAt(), authorised.semanticPackId(), authorised.fields(), authorised.facts(),
                authorised.actionOffers());
    }

    private <I, P> TypedAuthorisationEnvelope<I, P> authoriseTypedInTransaction(
            String tenantId,
            UUID snapshotId,
            Principal principal,
            Instant authorisedAt,
            ProjectionType<I, P> projectionType) {
        TenantContext.use(jdbc, tenantId);
        List<AuthorisationSnapshot> snapshots = jdbc.query("""
                SELECT snapshot.subject_type, snapshot.subject_id, snapshot.state_version,
                       snapshot.state_checksum, snapshot.projection_type, snapshot.projection_contract_version,
                       snapshot.evaluated_at, snapshot.semantic_pack_id, snapshot.semantic_pack_checksum,
                       state.format_version, state.content
                FROM kernel.typed_evaluation_snapshot snapshot
                JOIN kernel.typed_projected_state state
                  ON state.tenant_id = snapshot.tenant_id AND state.subject_type = snapshot.subject_type
                 AND state.subject_id = snapshot.subject_id AND state.state_version = snapshot.state_version
                 AND state.projection_type = snapshot.projection_type
                 AND state.contract_version = snapshot.projection_contract_version
                 AND state.checksum = snapshot.state_checksum
                WHERE snapshot.tenant_id = ? AND snapshot.id = ?
                """, (result, row) -> new AuthorisationSnapshot(
                        result.getString("subject_type"), result.getString("subject_id"),
                        result.getLong("state_version"), result.getString("state_checksum"),
                        result.getString("projection_type"), result.getInt("projection_contract_version"),
                        result.getTimestamp("evaluated_at").toInstant(), result.getString("semantic_pack_id"),
                        result.getString("semantic_pack_checksum"), result.getInt("format_version"),
                        result.getString("content")), tenantId, snapshotId);
        if (snapshots.size() != 1) throw new io.github.gmcnicol.kernel.application.AuthorisationDeniedException();
        AuthorisationSnapshot snapshot = snapshots.getFirst();
        if (!snapshot.subjectType().equals(projectionType.subjectType().qualifiedName())) {
            throw new io.github.gmcnicol.kernel.application.AuthorisationDeniedException();
        }
        P projection = canonical.decode(projectionType, new CanonicalEvidence(
                snapshot.projectionType(), snapshot.projectionVersion(), snapshot.formatVersion(),
                snapshot.content().getBytes(StandardCharsets.UTF_8), snapshot.stateChecksum()));
        TypedSubject<I> subject = typedSubject(projectionType.subjectType(), snapshot.subjectId());
        TypedAuthorisationModel<P> model = cedar.model(projectionType);
        List<TypedFact<?>> derivedFacts = jdbc.query("""
                SELECT fact_type, contract_version, format_version, content, checksum
                FROM kernel.typed_evaluation_fact
                WHERE tenant_id = ? AND snapshot_id = ? ORDER BY position
                """, (result, row) -> decodeFact(new CanonicalEvidence(
                        result.getString("fact_type"), result.getInt("contract_version"),
                        result.getInt("format_version"), result.getString("content").getBytes(StandardCharsets.UTF_8),
                        result.getString("checksum"))), tenantId, snapshotId);
        FactSet authorisationFacts = factSet(derivedFacts);
        var fields = new ArrayList<TypedFieldValue<P, ?>>();
        model.fields().stream()
                .sorted(Comparator.comparing(io.github.gmcnicol.kernel.application.FieldType::qualifiedName))
                .filter(field -> cedar.allows(
                        principal, subject, projectionType, projection, authorisationFacts, field))
                .forEach(field -> fields.add(fieldValue(field, projection)));
        List<TypedFact<?>> facts = derivedFacts.stream().filter(fact -> cedar.allows(
                principal, subject, projectionType, projection, authorisationFacts, fact.type())).toList();
        UUID correlation = UUID.randomUUID();
        var offers = new ArrayList<TypedActionOffer<P, ?, ?>>();
        jdbc.query("""
                SELECT action_id, policy_id FROM kernel.typed_evaluation_applicable_action
                WHERE tenant_id = ? AND snapshot_id = ? ORDER BY position
                """, (result, row) -> new ActionEntry(
                        result.getString("action_id"), result.getString("policy_id")), tenantId, snapshotId).stream()
                .map(entry -> Map.entry(entry, actions.get(entry.actionId())))
                .filter(entry -> entry.getValue() != null && entry.getValue().projectionType() == projectionType)
                .filter(entry -> cedar.allows(
                        principal, subject, projectionType, projection, authorisationFacts, entry.getValue()))
                .map(entry -> typedOffer(persistOffer(
                        tenantId, snapshotId, snapshot.offerSnapshot(), principal, entry.getKey().actionId(),
                        entry.getKey().policyId(), authorisedAt, correlation), entry.getValue()))
                .forEach(offer -> offers.add(castOffer(offer)));
        return new TypedAuthorisationEnvelope<>(snapshotId, snapshot.evaluatedAt(), snapshot.semanticPackId(),
                subject, projectionType, fields, facts, offers);
    }

    private UUID persistOffer(
            String tenantId,
            UUID snapshotId,
            TypedSnapshot snapshot,
            Principal principal,
            String actionId,
            String policyId,
            Instant authorisedAt,
            UUID correlation) {
        ActionType<?, ?, ?> action = Optional.ofNullable(actions.get(actionId))
                .orElseThrow(() -> new IllegalStateException("Unknown generated Action: " + actionId));
        if (!snapshot.projectionType().equals(action.projectionType().qualifiedName())
                || snapshot.projectionVersion() != action.projectionType().contractVersion()
                || !snapshot.subjectType().equals(action.projectionType().subjectType().qualifiedName())) {
            throw new IllegalStateException("Generated Action does not target snapshot Projection");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kernel.typed_action_offer
                    (id, tenant_id, evaluation_snapshot_id, principal_type, principal_id,
                     subject_type, subject_id, action_id, policy_id, state_version, state_checksum,
                     payload_type, payload_contract_version, semantic_pack_id, semantic_pack_checksum,
                     authorisation_bundle_id, authorisation_bundle_checksum, authorised_at, decision_correlation)
                SELECT ?, tenant_id, id, ?, ?, subject_type, subject_id, ?, ?, state_version, state_checksum,
                       ?, ?, semantic_pack_id, semantic_pack_checksum, ?, ?, ?, ?
                FROM kernel.typed_evaluation_snapshot WHERE tenant_id = ? AND id = ?
                """, id, principal.type(), principal.id(), actionId, policyId,
                action.candidateType().qualifiedName(), action.candidateType().contractVersion(),
                cedar.bundleId(), cedar.bundleChecksum(), Timestamp.from(authorisedAt), correlation,
                tenantId, snapshotId);
        telemetry.actionOffer(tenantId, new Subject(snapshot.subjectType(), snapshot.subjectId()), snapshotId, id,
                correlation);
        return id;
    }

    <C> Intent accept(UUID offerId, UUID intentId, TypedCandidatePayload<C> payload) {
        if (offerId == null || intentId == null || payload == null
                || payload.priorIntentId().filter(intentId::equals).isPresent()) throw new IntentRejectedException();
        try {
            return transactions.execute(status -> acceptInTransaction(null, null, offerId, intentId, payload));
        } catch (IntentConflictException | IntentRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IntentRejectedException();
        }
    }

    Intent accept(
            String tenantId, Principal principal, UUID offerId, UUID intentId, TypedCandidatePayload<?> payload) {
        if (tenantId == null || principal == null || offerId == null || intentId == null || payload == null
                || payload.priorIntentId().filter(intentId::equals).isPresent()) throw new IntentRejectedException();
        try {
            return transactions.execute(
                    status -> acceptInTransaction(tenantId, principal, offerId, intentId, payload));
        } catch (IntentConflictException | IntentRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IntentRejectedException();
        }
    }

    private <C> Intent acceptInTransaction(
            String expectedTenant,
            Principal expectedPrincipal,
            UUID offerId,
            UUID intentId,
            TypedCandidatePayload<C> payload) {
        TenantContext.assumeRuntimeRole(jdbc);
        String tenantId = jdbc.queryForObject(
                "SELECT kernel.resolve_typed_action_offer_tenant(?)", String.class, offerId);
        if (tenantId == null || expectedTenant != null && !expectedTenant.equals(tenantId)) {
            throw new IntentRejectedException();
        }
        TenantContext.useAfterRole(jdbc, tenantId);
        TypedOffer offer = jdbc.queryForObject("""
                SELECT evaluation_snapshot_id, subject_type, subject_id, action_id, policy_id,
                       state_version, state_checksum, payload_type, payload_contract_version,
                       principal_type, principal_id, semantic_pack_id, semantic_pack_checksum,
                       authorisation_bundle_id, authorisation_bundle_checksum
                FROM kernel.typed_action_offer WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new TypedOffer(
                        result.getObject("evaluation_snapshot_id", UUID.class),
                        result.getString("subject_type"), result.getString("subject_id"),
                        result.getString("action_id"), result.getString("policy_id"),
                        result.getLong("state_version"), result.getString("state_checksum"),
                        result.getString("payload_type"), result.getInt("payload_contract_version"),
                        new Principal(result.getString("principal_type"), result.getString("principal_id")),
                        result.getString("semantic_pack_id"), result.getString("semantic_pack_checksum"),
                        result.getString("authorisation_bundle_id"),
                        result.getString("authorisation_bundle_checksum")),
                tenantId, offerId);
        if (expectedPrincipal != null && !expectedPrincipal.equals(offer.principal())) {
            throw new IntentRejectedException();
        }
        ActionType<?, C, ?> action = typedAction(payload.actionType());
        if (action != payload.actionType()
                || !offer.actionId().equals(action.qualifiedName())
                || !offer.payloadType().equals(action.candidateType().qualifiedName())
                || offer.payloadVersion() != action.candidateType().contractVersion()
                || !currentSemanticPack(offer.semanticPackId(), offer.semanticPackChecksum())
                || !currentBundle(offer.bundleId(), offer.bundleChecksum())) throw new IntentRejectedException();
        State state = currentState(tenantId, offer.subjectType(), offer.subjectId(), action.projectionType());
        FactSet authorisationFacts = facts(action.projectionType(), state.value(), clock.instant());
        if (state.version() != offer.stateVersion() || !state.evidence().checksum().equals(offer.stateChecksum())
                || !currentlyApplicable(action, offer.policyId(), state.value(), authorisationFacts)
                || !cedarAllows(offer.principal(), offer.subjectId(), action, state.value(), authorisationFacts)) {
            throw new IntentRejectedException();
        }
        CanonicalEvidence evidence = canonical.encode(action.candidateType(), payload.value());
        String traceparent = payload.traceContext().map(W3cTraceContext::traceparent).orElse(null);
        String tracestate = payload.traceContext().map(W3cTraceContext::tracestate).orElse(null);
        String requestChecksum = requestChecksum(offerId, evidence, traceparent, tracestate, payload.priorIntentId());
        List<Intent> existing = existing(tenantId, intentId, offerId, requestChecksum);
        if (!existing.isEmpty()) return existing.getFirst();
        payload.priorIntentId().ifPresent(priorIntentId -> {
            Boolean terminal = jdbc.queryForObject("""
                    SELECT status IN ('SUCCEEDED', 'STALE', 'FAILED')
                    FROM kernel.typed_intent WHERE tenant_id = ? AND id = ?
                    """, Boolean.class, tenantId, priorIntentId);
            if (!Boolean.TRUE.equals(terminal)) throw new IntentRejectedException();
        });
        Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int inserted = jdbc.update("""
                INSERT INTO kernel.typed_intent
                    (id, tenant_id, action_offer_id, subject_type, subject_id, action_id, policy_id,
                     expected_state_version, expected_state_checksum, projection_type, projection_contract_version,
                     payload_type, payload_contract_version, payload_format_version, payload_content,
                     payload_checksum, request_checksum, accepted_at, traceparent, tracestate,
                     prior_intent_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT DO NOTHING
                """, intentId, tenantId, offerId, offer.subjectType(), offer.subjectId(), offer.actionId(),
                offer.policyId(), offer.stateVersion(), offer.stateChecksum(), action.projectionType().qualifiedName(),
                action.projectionType().contractVersion(), evidence.qualifiedType(), evidence.contractVersion(),
                evidence.formatVersion(), evidence.canonicalJson(), evidence.checksum(), requestChecksum,
                Timestamp.from(acceptedAt), traceparent, tracestate, payload.priorIntentId().orElse(null));
        if (inserted == 0) {
            existing = existing(tenantId, intentId, offerId, requestChecksum);
            if (existing.isEmpty()) throw new IntentConflictException();
            return existing.getFirst();
        }
        audit(intentId, tenantId, 0, null, IntentStatus.PENDING, acceptedAt, "accepted");
        telemetry.intent(tenantId, new Subject(offer.subjectType(), offer.subjectId()), offerId, intentId,
                IntentStatus.PENDING, KernelTelemetry.traceId(traceparent, intentId));
        return new Intent(intentId, offerId, IntentStatus.PENDING, acceptedAt);
    }

    Optional<Intent> processNext(Instant processedAt) {
        if (processedAt == null) throw new IllegalArgumentException("Processing time must be explicit");
        UUID token = UUID.randomUUID();
        Instant claimedAt = clock.instant();
        Claim claim = transactions.execute(status -> {
            TenantContext.assumeWorkerRole(jdbc);
            List<Claim> claims = jdbc.query(
                    "SELECT intent_id, tenant_id, previous_status FROM kernel.claim_due_typed_intent(?, ?, ?, ?)",
                    (result, row) -> new Claim(result.getObject("intent_id", UUID.class), result.getString("tenant_id"),
                            IntentStatus.valueOf(result.getString("previous_status"))),
                    token, Timestamp.from(processedAt), Timestamp.from(claimedAt),
                    Timestamp.from(claimedAt.plus(worker.leaseDuration())));
            if (claims.isEmpty()) return null;
            Claim selected = claims.getFirst();
            TenantContext.useAfterRole(jdbc, selected.tenantId());
            Claim enriched = jdbc.queryForObject("""
                    SELECT action_offer_id, subject_type, subject_id, accepted_at, traceparent
                    FROM kernel.typed_intent WHERE tenant_id = ? AND id = ?
                    """, (result, row) -> selected.withTelemetry(
                            result.getObject("action_offer_id", UUID.class),
                            new Subject(result.getString("subject_type"), result.getString("subject_id")),
                            result.getTimestamp("accepted_at").toInstant(), result.getString("traceparent")),
                    selected.tenantId(), selected.id());
            telemetry.lease(enriched.previousStatus() == IntentStatus.CLAIMED);
            telemetry.backlogAge(Duration.between(enriched.acceptedAt(), processedAt));
            return enriched;
        });
        if (claim == null) return Optional.empty();
        return telemetry.observeLinked("kernel.intent.attempt", claim.traceparent(), () -> {
            Intent result;
            try {
                result = transactions.execute(status -> complete(claim, token, processedAt));
            } catch (RuntimeException exception) {
                result = transactions.execute(status -> fail(claim, token, exception));
            }
            telemetry.outcome(result.status(), Duration.between(claim.acceptedAt(), clock.instant()));
            telemetry.intent(claim.tenantId(), claim.subject(), claim.offerId(), claim.id(), result.status(),
                    KernelTelemetry.traceId(claim.traceparent(), claim.id()));
            return Optional.of(result);
        });
    }

    private Intent complete(Claim claim, UUID token, Instant processedAt) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredIntent stored = load(claim, token);
        ActionType<?, ?, ?> action = actions.get(stored.actionId());
        if (!currentSemanticPack(stored.semanticPackId(), stored.semanticPackChecksum())
                || action == null
                || !stored.subjectType().equals(action.projectionType().subjectType().qualifiedName())) {
            return terminal(stored, token, IntentStatus.STALE, IntentFailureReason.STATE_OR_SEMANTIC_STALE, processedAt);
        }
        State state = evidenceState(claim.tenantId(), stored, action.projectionType());
        Long latestVersion = jdbc.queryForObject("""
                SELECT max(state_version) FROM kernel.typed_projected_state
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                """, Long.class, claim.tenantId(), stored.subjectType(), stored.subjectId());
        if (!java.util.Objects.equals(latestVersion, stored.stateVersion())
                || !state.evidence().checksum().equals(stored.stateChecksum())) {
            return terminal(stored, token, IntentStatus.STALE, IntentFailureReason.STATE_OR_SEMANTIC_STALE, processedAt);
        }
        FactSet authorisationFacts = facts(action.projectionType(), state.value(), processedAt);
        if (!currentlyApplicable(action, stored.policyId(), state.value(), authorisationFacts)) {
            return terminal(stored, token, IntentStatus.FAILED, IntentFailureReason.NOT_APPLICABLE, processedAt);
        }
        if (!currentBundle(stored.bundleId(), stored.bundleChecksum())
                || !cedarAllows(
                        stored.principal(), stored.subjectId(), action, state.value(), authorisationFacts)) {
            return terminal(stored, token, IntentStatus.FAILED, IntentFailureReason.AUTHORISATION_DENIED, processedAt);
        }
        Intent claimed = new Intent(stored.id(), stored.offerId(), IntentStatus.CLAIMED, stored.acceptedAt());
        Object payload = canonicalDecode(action.candidateType(), stored.payloadEvidence());
        List<TypedStateTransition<Object, Object>> transitions = telemetry.observe(
                "kernel.intent.handler", () -> handle(action, claimed, payload, state.value()));
        if (transitions.isEmpty()) throw new IllegalStateException("Successful typed handler must emit an Event");
        List<PreparedTransition> prepared = prepare(action, transitions);
        Object previous = state.value();
        long version = state.version();
        for (int index = 0; index < prepared.size(); index++) {
            PreparedTransition transition = prepared.get(index);
            int sequence = index + 1;
            version++;
            Object previousProjection = previous;
            long resultingVersion = version;
            telemetry.observe("kernel.event.projection.commit", () -> {
                project(transition.eventType(), new TypedTransitionProvenance<>(
                        claim.tenantId(), stored.id(), stored.offerId(),
                        typedSubject(action.projectionType(), stored.subjectId()), stored.actionId(), sequence,
                        processedAt, previousProjection, transition.transition().event(),
                        transition.transition().resultingProjection(), transition.eventEvidence(),
                        transition.projectionEvidence()));
                persistState(claim.tenantId(), stored, resultingVersion, transition.projectionEvidence());
                persistEvent(claim, stored, sequence, resultingVersion, processedAt, transition);
                return null;
            });
            previous = transition.transition().resultingProjection();
        }
        int updated = jdbc.update("""
                UPDATE kernel.typed_intent SET status = 'SUCCEEDED', lease_token = NULL, lease_until = NULL,
                    completed_at = ? WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND lease_token = ?
                    AND lease_until >= ?
                """, Timestamp.from(processedAt), claim.tenantId(), stored.id(), token, Timestamp.from(clock.instant()));
        if (updated != 1) {
            telemetry.leaseLost();
            throw new IllegalStateException("Typed Intent lease is no longer owned");
        }
        audit(stored.id(), claim.tenantId(), nextAudit(stored.id()), IntentStatus.CLAIMED, IntentStatus.SUCCEEDED,
                processedAt, prepared.size() + " Event(s) committed");
        return new Intent(stored.id(), stored.offerId(), IntentStatus.SUCCEEDED, stored.acceptedAt());
    }

    private Intent fail(Claim claim, UUID token, RuntimeException exception) {
        TenantContext.assumeWorkerRole(jdbc);
        TenantContext.useAfterRole(jdbc, claim.tenantId());
        StoredIntent stored = load(claim, token);
        boolean retry = (exception instanceof RetryableIntentException || exception instanceof TransientDataAccessException)
                && stored.attempts() < worker.maximumAttempts();
        IntentStatus status = retry ? IntentStatus.RETRY_WAIT : IntentStatus.FAILED;
        IntentFailureReason reason = retry ? null : (exception instanceof RetryableIntentException
                ? IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED : IntentFailureReason.DETERMINISTIC_FAILURE);
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE kernel.typed_intent SET status = ?, failure_reason = ?, lease_token = NULL, lease_until = NULL,
                    next_attempt_at = ?, completed_at = ? WHERE tenant_id = ? AND id = ?
                    AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                """, status.name(), reason == null ? null : reason.name(),
                retry ? Timestamp.from(now.plus(worker.retryBackoff())) : null,
                retry ? null : Timestamp.from(now), claim.tenantId(), stored.id(), token, Timestamp.from(now));
        if (updated != 1) {
            telemetry.leaseLost();
            throw new IllegalStateException("Typed Intent lease is no longer owned");
        }
        audit(stored.id(), claim.tenantId(), nextAudit(stored.id()), IntentStatus.CLAIMED, status, now,
                retry ? "transient failure" : reason.name());
        if (retry) telemetry.retry();
        return new Intent(stored.id(), stored.offerId(), status, stored.acceptedAt(), Optional.ofNullable(reason));
    }

    private Intent terminal(
            StoredIntent stored, UUID token, IntentStatus status, IntentFailureReason reason, Instant at) {
        int updated = jdbc.update("""
                UPDATE kernel.typed_intent SET status = ?, failure_reason = ?, lease_token = NULL,
                    lease_until = NULL, completed_at = ? WHERE tenant_id = ? AND id = ?
                    AND status = 'CLAIMED' AND lease_token = ? AND lease_until >= ?
                """, status.name(), reason.name(), Timestamp.from(at), stored.tenantId(), stored.id(), token,
                Timestamp.from(clock.instant()));
        if (updated != 1) {
            telemetry.leaseLost();
            throw new IllegalStateException("Typed Intent lease is no longer owned");
        }
        audit(stored.id(), stored.tenantId(), nextAudit(stored.id()), IntentStatus.CLAIMED, status, at, reason.name());
        return new Intent(stored.id(), stored.offerId(), status, stored.acceptedAt(), Optional.of(reason));
    }

    private List<PreparedTransition> prepare(
            ActionType<?, ?, ?> action, List<TypedStateTransition<Object, Object>> transitions) {
        var prepared = new ArrayList<PreparedTransition>();
        for (TypedStateTransition<Object, Object> transition : transitions) {
            EventType<Object> eventType = eventType(action, transition.event());
            action.projectionType().javaType().cast(transition.resultingProjection());
            prepared.add(new PreparedTransition(
                    transition, eventType, canonical.encode(eventType, transition.event()),
                    encodeProjection(action.projectionType(), transition.resultingProjection())));
        }
        return List.copyOf(prepared);
    }

    @SuppressWarnings("unchecked")
    private static EventType<Object> eventType(ActionType<?, ?, ?> action, Object event) {
        return (EventType<Object>) action.eventTypes().stream()
                .filter(type -> type.javaType().isInstance(event)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Handler returned an Event outside Action contract"));
    }

    @SuppressWarnings("unchecked")
    private CanonicalEvidence encodeProjection(ProjectionType<?, ?> type, Object value) {
        return canonical.encode((ProjectionType<Object, Object>) type, value);
    }

    @SuppressWarnings("unchecked")
    private List<TypedStateTransition<Object, Object>> handle(
            ActionType<?, ?, ?> action, Intent intent, Object payload, Object projection) {
        TypedIntentHandler<Object, Object, Object> handler =
                (TypedIntentHandler<Object, Object, Object>) handlers.get(action.qualifiedName());
        if (handler == null || handler.actionType() != action) {
            throw new IllegalStateException("Missing typed Intent handler: " + action.qualifiedName());
        }
        return List.copyOf(handler.handle(intent, payload, projection));
    }

    @SuppressWarnings("unchecked")
    private void project(EventType<?> type, TypedTransitionProvenance<Object, Object> provenance) {
        TypedEventProjector<Object, Object> projector =
                (TypedEventProjector<Object, Object>) projectors.get(type.qualifiedName());
        if (projector == null || projector.eventType() != type) {
            throw new IllegalStateException("Missing typed Event projector: " + type.qualifiedName());
        }
        projector.project(provenance);
    }

    @SuppressWarnings("unchecked")
    private boolean currentlyApplicable(
            ActionType<?, ?, ?> action, String policyId, Object projection, FactSet facts) {
        TypedApplicabilityPolicy<Object> policy = (TypedApplicabilityPolicy<Object>) policies.stream()
                .filter(candidate -> candidate.actionType() == action
                        && policyId.equals(action.qualifiedName() + ".applicability"))
                .findFirst().orElse(null);
        return policy != null && policy.isApplicable(projection, facts);
    }

    @SuppressWarnings("unchecked")
    private FactSet facts(ProjectionType<?, ?> projectionType, Object projection, Instant at) {
        var values = new LinkedHashMap<FactType<?>, Object>();
        derivations.stream().filter(derivation -> derivation.factType().projectionType() == projectionType)
                .forEach(derivation -> {
                    TypedFactDerivation<Object, Object> typed = (TypedFactDerivation<Object, Object>) derivation;
                    typed.derive(projection, at).value().ifPresent(value -> values.put(typed.factType(), value));
                });
        return FactSet.of(values);
    }

    private static FactSet factSet(List<TypedFact<?>> facts) {
        var values = new LinkedHashMap<FactType<?>, Object>();
        facts.forEach(fact -> values.put(fact.type(), fact.value()));
        return FactSet.of(values);
    }

    @SuppressWarnings("unchecked")
    private boolean cedarAllows(
            Principal principal, String subjectId, ActionType<?, ?, ?> action,
            Object projection, FactSet facts) {
        ProjectionType<?, Object> type = (ProjectionType<?, Object>) action.projectionType();
        return cedar.allows(principal, typedSubject(type, subjectId), type, projection, facts, action);
    }

    private State currentState(String tenantId, String subjectType, String subjectId, ProjectionType<?, ?> type) {
        return jdbc.queryForObject("""
                SELECT state_version, format_version, content, checksum FROM kernel.typed_projected_state
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND projection_type = ?
                  AND contract_version = ? ORDER BY state_version DESC LIMIT 1
                """, (result, row) -> {
                    CanonicalEvidence evidence = evidence(type, result.getInt("format_version"),
                            result.getString("content"), result.getString("checksum"));
                    return new State(result.getLong("state_version"), canonicalDecode(type, evidence), evidence);
                }, tenantId, subjectType, subjectId, type.qualifiedName(), type.contractVersion());
    }

    private State evidenceState(String tenantId, StoredIntent stored, ProjectionType<?, ?> target) {
        return jdbc.queryForObject("""
                SELECT format_version, content, checksum FROM kernel.typed_projected_state
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND state_version = ?
                  AND projection_type = ? AND contract_version = ? AND checksum = ?
                """, (result, row) -> {
                    CanonicalEvidence evidence = new CanonicalEvidence(
                            stored.projectionType(), stored.projectionVersion(), result.getInt("format_version"),
                            result.getString("content").getBytes(StandardCharsets.UTF_8), result.getString("checksum"));
                    return new State(stored.stateVersion(), canonicalDecode(target, evidence), evidence);
                }, tenantId, stored.subjectType(), stored.subjectId(), stored.stateVersion(),
                stored.projectionType(), stored.projectionVersion(), stored.stateChecksum());
    }

    @SuppressWarnings("unchecked")
    private Object canonicalDecode(SemanticType<?> type, CanonicalEvidence evidence) {
        return canonical.decode((SemanticType<Object>) type, evidence);
    }

    private StoredIntent load(Claim claim, UUID token) {
        return jdbc.queryForObject("""
                SELECT intent.id, intent.tenant_id, intent.action_offer_id, intent.subject_type, intent.subject_id,
                       intent.action_id, intent.policy_id, intent.expected_state_version,
                       intent.expected_state_checksum, intent.projection_type, intent.projection_contract_version,
                       intent.payload_type, intent.payload_contract_version, intent.payload_format_version,
                       intent.payload_content, intent.payload_checksum, intent.accepted_at, intent.attempt_count,
                       offer.principal_type, offer.principal_id, offer.semantic_pack_id,
                       offer.semantic_pack_checksum, offer.authorisation_bundle_id,
                       offer.authorisation_bundle_checksum
                FROM kernel.typed_intent intent JOIN kernel.typed_action_offer offer
                  ON offer.tenant_id = intent.tenant_id AND offer.id = intent.action_offer_id
                WHERE intent.tenant_id = ? AND intent.id = ? AND intent.status = 'CLAIMED'
                  AND intent.lease_token = ?
                """, (result, row) -> {
                    CanonicalEvidence payloadEvidence = new CanonicalEvidence(
                            result.getString("payload_type"), result.getInt("payload_contract_version"),
                            result.getInt("payload_format_version"),
                            result.getString("payload_content").getBytes(StandardCharsets.UTF_8),
                            result.getString("payload_checksum"));
                    return new StoredIntent(
                            result.getObject("id", UUID.class), result.getString("tenant_id"),
                            result.getObject("action_offer_id", UUID.class), result.getString("subject_type"),
                            result.getString("subject_id"), result.getString("action_id"),
                            result.getString("policy_id"), result.getLong("expected_state_version"),
                            result.getString("expected_state_checksum"), result.getString("projection_type"),
                            result.getInt("projection_contract_version"), payloadEvidence,
                            result.getTimestamp("accepted_at").toInstant(), result.getInt("attempt_count"),
                            new Principal(result.getString("principal_type"), result.getString("principal_id")),
                            result.getString("semantic_pack_id"), result.getString("semantic_pack_checksum"),
                            result.getString("authorisation_bundle_id"),
                            result.getString("authorisation_bundle_checksum"));
                }, claim.tenantId(), claim.id(), token);
    }

    private void persistState(String tenantId, StoredIntent intent, long version, CanonicalEvidence evidence) {
        jdbc.update("""
                INSERT INTO kernel.typed_projected_state
                    (tenant_id, subject_type, subject_id, state_version, projection_type,
                     contract_version, format_version, content, checksum)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, intent.subjectType(), intent.subjectId(), version, evidence.qualifiedType(),
                evidence.contractVersion(), evidence.formatVersion(), evidence.canonicalJson(), evidence.checksum());
    }

    private void persistEvent(
            Claim claim, StoredIntent intent, int sequence, long version, Instant at, PreparedTransition transition) {
        CanonicalEvidence event = transition.eventEvidence();
        CanonicalEvidence projection = transition.projectionEvidence();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kernel.typed_event
                    (id, intent_id, tenant_id, sequence, event_type, event_contract_version, event_format_version,
                     event_content, event_checksum, resulting_state_version, projection_type,
                     projection_contract_version, projection_format_version, projection_content,
                     projection_checksum, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, intent.id(), claim.tenantId(), sequence, event.qualifiedType(), event.contractVersion(),
                event.formatVersion(), event.canonicalJson(), event.checksum(), version, projection.qualifiedType(),
                projection.contractVersion(), projection.formatVersion(), projection.canonicalJson(),
                projection.checksum(), Timestamp.from(at));
        telemetry.event(claim.tenantId(), claim.subject(), claim.id(), eventId,
                KernelTelemetry.traceId(claim.traceparent(), claim.id()));
    }

    private List<Intent> existing(String tenantId, UUID id, UUID offerId, String checksum) {
        return jdbc.query("""
                SELECT action_offer_id, status, accepted_at, request_checksum, failure_reason
                FROM kernel.typed_intent WHERE tenant_id = ? AND id = ?
                """, (result, row) -> {
                    if (!offerId.equals(result.getObject("action_offer_id", UUID.class))
                            || !checksum.equals(result.getString("request_checksum"))) throw new IntentConflictException();
                    String reason = result.getString("failure_reason");
                    return new Intent(id, offerId, IntentStatus.valueOf(result.getString("status")),
                            result.getTimestamp("accepted_at").toInstant(), reason == null
                                    ? Optional.empty() : Optional.of(IntentFailureReason.valueOf(reason)));
                }, tenantId, id);
    }

    private void audit(
            UUID intent, String tenant, int sequence, IntentStatus from, IntentStatus to, Instant at, String reason) {
        jdbc.update("""
                INSERT INTO kernel.typed_intent_audit
                    (id, intent_id, tenant_id, sequence, from_status, to_status, occurred_at, reason, correlation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), intent, tenant, sequence, from == null ? null : from.name(), to.name(),
                Timestamp.from(at), reason, intent);
    }

    private int nextAudit(UUID intent) {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), -1) + 1 FROM kernel.typed_intent_audit WHERE intent_id = ?",
                Integer.class, intent);
    }

    @SuppressWarnings("unchecked")
    private <C> ActionType<?, C, ?> typedAction(ActionType<?, C, ?> action) {
        return (ActionType<?, C, ?>) Optional.ofNullable(actions.get(action.qualifiedName()))
                .orElseThrow(IntentRejectedException::new);
    }

    private static CanonicalEvidence evidence(
            SemanticType<?> type, int format, String content, String checksum) {
        return new CanonicalEvidence(type.qualifiedName(), type.contractVersion(), format,
                content.getBytes(StandardCharsets.UTF_8), checksum);
    }

    private static void requireIdentity(Object expected, Object actual, String name) {
        if (expected != actual) throw new IllegalStateException("Unregistered generated " + name);
    }

    private static TypedSubject<?> typedSubject(ProjectionType<?, ?> projection, String externalId) {
        return typedSubject(projection.subjectType(), externalId);
    }

    private static <I> TypedSubject<I> typedSubject(
            io.github.gmcnicol.kernel.application.SubjectType<I> type, String externalId) {
        return new TypedSubject<>(type, type.fromExternalId(externalId));
    }

    private static <P, V> TypedFieldValue<P, V> fieldValue(
            io.github.gmcnicol.kernel.application.FieldType<P, V> field, P projection) {
        return new TypedFieldValue<>(field, field.value(projection));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TypedFact<?> decodeFact(CanonicalEvidence evidence) {
        FactType source = Optional.ofNullable(factTypes.get(
                        evidence.qualifiedType() + "@" + evidence.contractVersion()))
                .orElseThrow(() -> new IllegalArgumentException("Unregistered generated Fact evidence"));
        return new TypedFact<>(source, canonical.decode(source, evidence));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <P> TypedActionOffer<P, ?, ?> typedOffer(UUID id, ActionType<?, ?, ?> action) {
        return new TypedActionOffer(id, action);
    }

    @SuppressWarnings("unchecked")
    private static <P> TypedActionOffer<P, ?, ?> castOffer(TypedActionOffer<?, ?, ?> offer) {
        return (TypedActionOffer<P, ?, ?>) offer;
    }

    private static void addDescriptor(Map<String, SemanticType<?>> descriptors, SemanticType<?> descriptor) {
        SemanticType<?> previous = descriptors.putIfAbsent(key(descriptor), descriptor);
        if (previous != null && previous != descriptor) {
            throw new IllegalStateException("Conflicting generated semantic descriptor: " + key(descriptor));
        }
    }

    private boolean currentSemanticPack(String id, String checksum) {
        return semanticPack.id().equals(id) && semanticPack.checksum().equals(checksum);
    }

    private boolean currentBundle(String id, String checksum) {
        return cedar.bundleId().equals(id) && cedar.bundleChecksum().equals(checksum);
    }

    private static String key(SemanticType<?> type) {
        return type.qualifiedName() + "@" + type.contractVersion();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String requestChecksum(
            UUID offerId,
            CanonicalEvidence evidence,
            String traceparent,
            String tracestate,
            Optional<UUID> priorIntentId) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, offerId.toString());
        append(canonical, evidence.qualifiedType());
        append(canonical, Integer.toString(evidence.contractVersion()));
        append(canonical, Integer.toString(evidence.formatVersion()));
        append(canonical, evidence.checksum());
        append(canonical, traceparent == null ? "" : traceparent);
        append(canonical, tracestate == null ? "" : tracestate);
        append(canonical, priorIntentId.map(UUID::toString).orElse(""));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value);
    }

    private record TypedSnapshot(
            String subjectType, String subjectId, long version, String checksum,
            String projectionType, int projectionVersion) {}
    private record AuthorisationSnapshot(
            String subjectType,
            String subjectId,
            long version,
            String stateChecksum,
            String projectionType,
            int projectionVersion,
            Instant evaluatedAt,
            String semanticPackId,
            String semanticPackChecksum,
            int formatVersion,
            String content) {
        private TypedSnapshot offerSnapshot() {
            return new TypedSnapshot(subjectType, subjectId, version, stateChecksum, projectionType, projectionVersion);
        }
    }
    private record StoredCanonicalCandidate(String actionId, CanonicalEvidence evidence) {}
    private record ActionEntry(String actionId, String policyId) {}
    private record TypedOffer(
            UUID snapshotId, String subjectType, String subjectId, String actionId, String policyId,
            long stateVersion, String stateChecksum, String payloadType, int payloadVersion,
            Principal principal, String semanticPackId, String semanticPackChecksum,
            String bundleId, String bundleChecksum) {}
    private record Claim(
            UUID id, String tenantId, IntentStatus previousStatus, UUID offerId, Subject subject,
            Instant acceptedAt, String traceparent) {
        private Claim(UUID id, String tenantId, IntentStatus previousStatus) {
            this(id, tenantId, previousStatus, null, null, null, null);
        }

        private Claim withTelemetry(UUID offerId, Subject subject, Instant acceptedAt, String traceparent) {
            return new Claim(id, tenantId, previousStatus, offerId, subject, acceptedAt, traceparent);
        }
    }
    private record State(long version, Object value, CanonicalEvidence evidence) {}
    private record StoredIntent(
            UUID id, String tenantId, UUID offerId, String subjectType, String subjectId, String actionId,
            String policyId, long stateVersion, String stateChecksum, String projectionType, int projectionVersion,
            CanonicalEvidence payloadEvidence, Instant acceptedAt, int attempts,
            Principal principal, String semanticPackId, String semanticPackChecksum,
            String bundleId, String bundleChecksum) {}
    private record PreparedTransition(
            TypedStateTransition<Object, Object> transition,
            EventType<Object> eventType,
            CanonicalEvidence eventEvidence,
            CanonicalEvidence projectionEvidence) {}
}
