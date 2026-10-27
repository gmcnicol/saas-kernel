package io.github.gmcnicol.kernel.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.stream.Stream;

/** Public workflow seam, with an Application-owned synthetic grant source, real Cedar and PostgreSQL adapters. */
public abstract class EffectiveCapabilityContract {
    protected abstract Flow flow(String id);
    protected abstract ApplicationGrants grants();
    protected abstract TestClock clock();
    protected abstract TypedCapability<?> unrelatedCapability();
    protected abstract TypedCapability<?> capabilityWithRemaining(int remaining);
    protected abstract void restrictResource(Flow flow);
    protected abstract void removeResource(Flow flow);

    public static final class TestClock extends Clock {
        private Instant now = Instant.parse("2031-01-01T00:00:00Z");
        public void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public record Flow(Kernel kernel, String tenant, Principal principal, UUID snapshot,
                          ProjectionType<?, ?> projection, TypedCandidatePayload<?> candidate,
                          Consumer<Runnable> transaction) {
        public TypedAuthorisationEnvelope<?, ?> discover() {
            return kernel.authorise(tenant, snapshot, principal, Instant.parse("2020-01-02T00:00:00Z"), projection);
        }
        public Intent accept(UUID offer, UUID intent) {
            return kernel.accept(tenant, principal, offer, intent, candidate);
        }
        public void execute(UUID offer, UUID intent) {
            transaction.accept(() -> kernel.execute(tenant, principal, offer, intent, candidate,
                    Instant.parse("2031-01-01T00:00:00Z")));
        }
    }

    @Test
    void paidAndPreviewSupplyEquivalentEffectiveCapabilities() {
        Flow paid = flow(UUID.randomUUID().toString());
        grants().grant(paid.tenant(), "paid", Instant.MAX);
        Flow preview = flow(UUID.randomUUID().toString());
        grants().grant(preview.tenant(), "preview", Instant.MAX);
        assertThat(paid.discover().actionOffers()).singleElement()
                .satisfies(offer -> assertThat(offer.actionType()).isSameAs(paid.candidate().actionType()));
        assertThat(preview.discover().actionOffers()).singleElement()
                .satisfies(offer -> assertThat(offer.actionType()).isSameAs(preview.candidate().actionType()));
    }

    @Test
    void cedarEvaluationErrorCannotPermitThroughAnotherMatchingPermit() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        grants().replace(flow.tenant(), capabilityWithRemaining(3));
        var envelope = flow.discover();
        assertThat(envelope.actionOffers()).isEmpty();
        assertThat(envelope.fields()).isEmpty();
        assertThat(envelope.facts()).isEmpty();
    }

    @Test
    void cedarEvaluationErrorAfterDiscoveryRejectsAcceptanceAndExactExecution() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        grants().replace(flow.tenant(), capabilityWithRemaining(3));
        assertThatThrownBy(() -> flow.accept(offer, UUID.randomUUID())).isInstanceOf(IntentRejectedException.class)
                .hasMessage("Intent rejected");
        assertThatThrownBy(() -> flow.execute(offer, UUID.randomUUID())).isInstanceOf(IntentRejectedException.class)
                .hasMessage("Intent rejected");
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
    }

    @Test
    void cedarEvaluationErrorAfterAcceptanceDeniesAsyncExecution() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        Intent accepted = flow.accept(flow.discover().actionOffers().getFirst().id(), UUID.randomUUID());
        grants().replace(flow.tenant(), capabilityWithRemaining(3));
        Intent completed = flow.kernel().processNext(clock().instant()).orElseThrow();
        assertThat(completed.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), accepted.id())).isEmpty();
    }

    @Test
    void overlappingGrantsSurviveOneExpiryButNotLastRevocation() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "preview", clock().instant().plusSeconds(1));
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        assertThat(flow.discover().actionOffers()).hasSize(1);
        clock().advanceSeconds(2);
        assertThat(flow.discover().actionOffers()).hasSize(1);
        grants().revoke(flow.tenant());
        assertThatThrownBy(flow::discover).isInstanceOf(AuthorisationDeniedException.class);
    }

    @Test
    void expiredGrantIsNotCurrentAuthorityEvenForAnOldSnapshot() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "preview", clock().instant().minusSeconds(1));
        assertThatThrownBy(flow::discover).isInstanceOf(AuthorisationDeniedException.class);
    }

    @Test
    void absentGrantDenies() {
        Flow flow = flow(UUID.randomUUID().toString());
        assertThatThrownBy(flow::discover).isInstanceOf(AuthorisationDeniedException.class);
    }

    @Test
    void unrelatedCapabilityCannotEscalate() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        grants().replace(flow.tenant(), unrelatedCapability());
        assertThatThrownBy(flow::discover).isInstanceOf(AuthorisationDeniedException.class);
    }

    @Test
    void capabilityDoesNotOverrideRestrictiveResourceMembership() {
        Flow flow = flow("restricted-" + UUID.randomUUID());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        var envelope = flow.discover();
        assertThat(envelope.actionOffers()).isEmpty();
        assertThat(envelope.fields()).isEmpty();
        assertThat(envelope.facts()).isEmpty();
    }

    @Test
    void revocationAfterDiscoveryRejectsOldOffersOnBothAcceptanceRoutes() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        grants().revoke(flow.tenant());
        assertThatThrownBy(() -> flow.accept(offer, UUID.randomUUID())).isInstanceOf(IntentRejectedException.class);
        assertThatThrownBy(() -> flow.kernel().accept(offer, UUID.randomUUID(), flow.candidate()))
                .isInstanceOf(IntentRejectedException.class);
    }

    @Test
    void acceptanceRetryStillRequiresCurrentAuthority() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.accept(offer, intent);
        grants().revoke(flow.tenant());
        assertThatThrownBy(() -> flow.accept(offer, intent)).isInstanceOf(IntentRejectedException.class);
        assertThat(flow.kernel().processNext(clock().instant()).orElseThrow().id()).isEqualTo(intent);
    }

    @Test
    void acceptanceRetryRejectsPresentButExhaustedCapability() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.accept(offer, intent);
        for (int remaining : List.of(0, 3)) { // Exhausted authority, then a policy evaluation error.
            grants().replace(flow.tenant(), capabilityWithRemaining(remaining));
            assertThatThrownBy(() -> flow.accept(offer, intent)).isInstanceOf(IntentRejectedException.class)
                    .hasMessage("Intent rejected");
            assertThatThrownBy(() -> flow.kernel().accept(offer, intent, flow.candidate()))
                    .isInstanceOf(IntentRejectedException.class).hasMessage("Intent rejected");
        }
        grants().replace(flow.tenant(), capabilityWithRemaining(2));
        assertThat(flow.accept(offer, intent).id()).isEqualTo(intent);
        assertThat(flow.kernel().processNext(clock().instant()).orElseThrow().status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
    }

    @Test
    void revocationAfterAcceptanceDeniesAsyncExecution() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "preview", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        Intent accepted = flow.accept(offer, UUID.randomUUID());
        grants().revoke(flow.tenant());
        Intent completed = flow.kernel().processNext(clock().instant()).orElseThrow();
        assertThat(completed.id()).isEqualTo(accepted.id());
        assertThat(completed.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), accepted.id())).isEmpty();
    }

    @Test
    void expiryAfterAcceptanceDeniesEvenAtHistoricalProcessingTime() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "preview", clock().instant().plusSeconds(1));
        Intent accepted = flow.accept(flow.discover().actionOffers().getFirst().id(), UUID.randomUUID());
        clock().advanceSeconds(2);
        Intent completed = flow.kernel().processNext(accepted.acceptedAt()).orElseThrow();
        assertThat(completed.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), accepted.id())).isEmpty();
    }

    @Test
    void expiryAfterDiscoveryRejectsExactExecutionAtHistoricalTime() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "preview", clock().instant().plusSeconds(1));
        UUID offer = flow.discover().actionOffers().getFirst().id();
        clock().advanceSeconds(2);
        assertThatThrownBy(() -> flow.execute(offer, UUID.randomUUID())).isInstanceOf(IntentRejectedException.class);
    }

    @Test
    void revocationBetweenExactAcceptanceAndExecutionRollsBack() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        grants().revokeAfterResolution(flow.tenant());
        assertThatThrownBy(() -> flow.execute(offer, intent)).isInstanceOf(IntentRejectedException.class);
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).isEmpty();
    }

    @Test
    void exactReconciliationStillRequiresCurrentAuthorityWithoutRecallingCommittedWork() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.execute(offer, intent);
        grants().revoke(flow.tenant());
        assertThatThrownBy(() -> flow.execute(offer, intent)).isInstanceOf(IntentRejectedException.class);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
    }

    @Test
    void exactReconciliationRejectsExhaustedCapabilityWithoutRecallingOrRepeatingWork() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.execute(offer, intent);
        for (int remaining : List.of(0, 3)) { // Neither denial may recall the already committed effect.
            grants().replace(flow.tenant(), capabilityWithRemaining(remaining));
            assertThatThrownBy(() -> flow.execute(offer, intent)).isInstanceOf(IntentRejectedException.class)
                    .hasMessage("Intent rejected");
            assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
        }
        grants().replace(flow.tenant(), capabilityWithRemaining(2));
        flow.execute(offer, intent);
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).singleElement()
                .extracting(IntentView::status).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    void acceptanceRetryUsesCurrentResourcePolicy() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.accept(offer, intent);
        restrictResource(flow);
        assertThatThrownBy(() -> flow.accept(offer, intent)).isInstanceOf(IntentRejectedException.class)
                .hasMessage("Intent rejected");
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).singleElement()
                .extracting(IntentView::status).isEqualTo(IntentStatus.PENDING);
        // Drain this fixture's now-stale pending work through the public worker seam.
        assertThat(flow.kernel().processNext(clock().instant()).orElseThrow().id()).isEqualTo(intent);
    }

    @Test
    void exactReconciliationUsesCurrentResourcePolicyWithoutRecallingWork() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.execute(offer, intent);
        restrictResource(flow);
        assertThatThrownBy(() -> flow.execute(offer, intent)).isInstanceOf(IntentRejectedException.class)
                .hasMessage("Intent rejected");
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).singleElement()
                .extracting(IntentView::status).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    void exactReconciliationDeniesMissingCurrentResourceWithoutFabricatingHistory() {
        Flow flow = flow(UUID.randomUUID().toString());
        grants().grant(flow.tenant(), "paid", Instant.MAX);
        UUID offer = flow.discover().actionOffers().getFirst().id();
        UUID intent = UUID.randomUUID();
        flow.execute(offer, intent);
        removeResource(flow);
        assertThatThrownBy(() -> flow.execute(offer, intent)).isInstanceOf(IntentRejectedException.class)
                .hasMessage("Intent rejected").hasNoCause();
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intent)).hasSize(1);
    }

    @Test
    void validCurrentAuthorityAllowsAsyncAndExactExecution() {
        Flow async = flow(UUID.randomUUID().toString());
        grants().grant(async.tenant(), "paid", Instant.MAX);
        Intent accepted = async.accept(async.discover().actionOffers().getFirst().id(), UUID.randomUUID());
        assertThat(async.kernel().processNext(clock().instant()).orElseThrow().status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(async.kernel().findIntentEvents(async.tenant(), accepted.id())).hasSize(1);
        Flow exact = flow(UUID.randomUUID().toString());
        grants().grant(exact.tenant(), "preview", Instant.MAX);
        UUID intent = UUID.randomUUID();
        exact.execute(exact.discover().actionOffers().getFirst().id(), intent);
        assertThat(exact.kernel().findIntentEvents(exact.tenant(), intent)).hasSize(1);
    }

    @TestFactory
    Stream<DynamicTest> untrustedOrStaleAuthorityDenies() {
        return Arrays.stream(Corruption.values()).map(corruption -> DynamicTest.dynamicTest(corruption.name(), () -> {
            Flow flow = flow(UUID.randomUUID().toString());
            grants().grant(flow.tenant(), "paid", Instant.MAX);
            grants().corrupt(flow.tenant(), corruption);
            assertThatThrownBy(flow::discover).isInstanceOf(AuthorisationDeniedException.class)
                    .hasMessage("Authorisation denied");
        }));
    }

    public enum Corruption {
        ABSENT, VERSION, EXPIRED, HISTORICAL_TIME, FUTURE_TIME, STALE_REVISION, BLANK_REVISION,
        TENANT, PRINCIPAL, RESOURCE, RESOURCE_TYPE, DUPLICATE, FORGED_DESCRIPTOR, FAILURE;

        EffectiveCapabilityResolver.Resolution apply(EffectiveCapabilityResolver.Resolution resolution) {
            if (this == ABSENT) return null;
            if (this == FAILURE) throw new IllegalStateException("private resolver failure");
            var c = resolution.context();
            var scope = c.scope();
            scope = switch (this) {
                case TENANT -> new EffectiveCapabilityResolver.Scope("other", scope.principal(), scope.resource());
                case PRINCIPAL -> new EffectiveCapabilityResolver.Scope(scope.tenantId(), new Principal("Owner", "other"), scope.resource());
                case RESOURCE -> new EffectiveCapabilityResolver.Scope(scope.tenantId(), scope.principal(),
                        subject(scope.resource().type(), "other"));
                case RESOURCE_TYPE -> new EffectiveCapabilityResolver.Scope(scope.tenantId(), scope.principal(),
                        new TypedSubject<>(new SubjectType<>("other.Resource", String.class, value -> value), "member"));
                default -> scope;
            };
            return new EffectiveCapabilityResolver.Resolution(resolution.currentRevision(),
                    new EffectiveCapabilityResolver.Context(this == VERSION ? 99 : 1, scope,
                            this == HISTORICAL_TIME ? Instant.parse("2020-01-02T00:00:00Z")
                                    : this == FUTURE_TIME ? c.evaluatedAt().plusSeconds(1) : c.evaluatedAt(),
                            this == EXPIRED ? c.evaluatedAt() : c.expiresAt(),
                            this == STALE_REVISION ? "old" : this == BLANK_REVISION ? "" : c.revision(),
                            this == DUPLICATE ? List.of(c.capabilities().getFirst(), c.capabilities().getFirst())
                                    : this == FORGED_DESCRIPTOR ? List.of(forge(c.capabilities().getFirst())) : c.capabilities()));
        }
        private static <I> TypedSubject<I> subject(SubjectType<I> type, String id) {
            return new TypedSubject<>(type, type.fromExternalId(id));
        }
        private static <C> TypedCapability<C> forge(TypedCapability<C> capability) {
            var type = capability.type();
            return new TypedCapability<>(new CapabilityType<>(type.qualifiedName(), type.contractVersion(),
                    type.javaType(), type.fields()), capability.value());
        }
    }

    /** External Application adapter: independent grants collapse to one effective typed capability. */
    public static final class ApplicationGrants implements EffectiveCapabilityResolver {
        private record Grant(String source, Instant expiresAt) {}
        private final Map<String, List<Grant>> grants = new ConcurrentHashMap<>();
        private final Map<String, Corruption> corruptions = new ConcurrentHashMap<>();
        private final Map<String, Integer> revisions = new ConcurrentHashMap<>();
        private final Set<String> revokeAfterResolution = ConcurrentHashMap.newKeySet();
        private final Map<String, TypedCapability<?>> replacements = new ConcurrentHashMap<>();
        private final TypedCapability<?> capability;
        private final Map<String, TypedCapability<?>> additions = new ConcurrentHashMap<>();
        public void include(String tenant, TypedCapability<?> unrelated) {
            additions.put(tenant, unrelated);
            revisions.merge(tenant, 1, Integer::sum);
        }
        public ApplicationGrants(TypedCapability<?> capability) { this.capability = capability; }
        public void grant(String tenant, String source, Instant expiresAt) {
            grants.compute(tenant, (ignored, existing) -> {
                var updated = new ArrayList<>(existing == null ? List.<Grant>of() : existing);
                updated.add(new Grant(source, expiresAt));
                return List.copyOf(updated);
            });
            revisions.merge(tenant, 1, Integer::sum);
        }
        public void revoke(String tenant) { grants.remove(tenant); revisions.merge(tenant, 1, Integer::sum); }
        public void revoke(String tenant, String source) {
            grants.computeIfPresent(tenant, (ignored, existing) -> existing.stream()
                    .filter(grant -> !grant.source().equals(source)).toList());
            revisions.merge(tenant, 1, Integer::sum);
        }
        public void revokeAfterResolution(String tenant) { revokeAfterResolution.add(tenant); }
        public void replace(String tenant, TypedCapability<?> value) { replacements.put(tenant, value); }
        public void corrupt(String tenant, Corruption corruption) { corruptions.put(tenant, corruption); }
        @Override public Resolution resolve(Request request) {
            Instant effectiveUntil = grants.getOrDefault(request.scope().tenantId(), List.of()).stream()
                    .map(Grant::expiresAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
            boolean active = effectiveUntil.isAfter(request.evaluatedAt());
            Instant expiresAt = active && effectiveUntil.isBefore(request.evaluatedAt().plusSeconds(60))
                    ? effectiveUntil : request.evaluatedAt().plusSeconds(60);
            String tenant = request.scope().tenantId();
            String revision = "revision-" + revisions.getOrDefault(tenant, 0);
            var values = new ArrayList<TypedCapability<?>>();
            if (active) values.add(replacements.getOrDefault(tenant, capability));
            if (additions.containsKey(tenant)) values.add(additions.get(tenant));
            Resolution result = new Resolution(revision, new Context(1, request.scope(), request.evaluatedAt(),
                    expiresAt, revision, values));
            if (revokeAfterResolution.remove(tenant)) revoke(tenant);
            return corruptions.containsKey(request.scope().tenantId())
                    ? corruptions.get(request.scope().tenantId()).apply(result) : result;
        }
    }
}
