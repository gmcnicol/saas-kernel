package io.github.gmcnicol.kernel.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentAuditQuery;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Kernel;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/** Reactor-only reusable public-seam contract for generated typed Kernel Applications. */
public abstract class TypedKernelBehaviourContract {

    protected abstract Kernel kernel();

    protected abstract Flow flow(String uniqueId);

    protected abstract boolean hasNoOffer(String uniqueId);

    protected abstract boolean failsClosedAfterPolicyChange(String uniqueId);

    protected abstract boolean schedulesAndRunsReevaluation(String uniqueId);

    protected abstract boolean filtersAuthority(String uniqueId);

    @Test
    final void completesPersistsAndProjectsOrderedTypedEvidence() {
        Flow flow = flow("complete");
        Intent intent = flow.accept().apply(UUID.randomUUID());
        flow.clock().accept(flow.processAt());

        Intent completed = kernel().processNext(flow.processAt()).orElseThrow();

        assertThat(completed.id()).isEqualTo(intent.id());
        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(flow.eventCount().getAsInt()).isPositive();
        assertThat(flow.projectionUpdated().getAsBoolean()).isTrue();
        assertThat(flow.observed().getAsBoolean()).isTrue();
        assertThat(kernel().findIntentAudit(IntentAuditQuery.firstPage(flow.tenant(), intent.id())))
                .extracting(entry -> entry.toStatus())
                .containsSequence(IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.SUCCEEDED);
    }

    @Test
    final void acceptanceIsIdempotentAndTenantQueriesAreIsolated() {
        Flow flow = flow("duplicate");
        UUID id = UUID.randomUUID();

        assertThat(flow.accept().apply(id)).isEqualTo(flow.accept().apply(id));
        assertThat(kernel().findIntents(IntentQuery.tenant(flow.tenant())))
                .filteredOn(view -> view.id().equals(id)).hasSize(1);
        assertThat(kernel().findIntents(IntentQuery.tenant(flow.tenant() + "-other"))).isEmpty();
        flow.clock().accept(flow.processAt());
        assertThat(kernel().processNext(flow.processAt())).isPresent();
    }

    @Test
    final void staleStateFailsClosedBeforeApplicationProjection() {
        Flow flow = flow("stale");
        flow.accept().apply(UUID.randomUUID());
        flow.makeStale().run();
        flow.clock().accept(flow.processAt());

        assertThat(kernel().processNext(flow.processAt()).orElseThrow().status()).isEqualTo(IntentStatus.STALE);
        assertThat(flow.eventCount().getAsInt()).isZero();
        assertThat(flow.projectionUpdated().getAsBoolean()).isFalse();
    }

    @Test
    final void crashedWorkerLeaseIsReclaimedWithoutDuplicateEvent() {
        Flow flow = flow("reclaim");
        Intent intent = flow.accept().apply(UUID.randomUUID());
        flow.expireLease().accept(intent);
        flow.clock().accept(flow.processAt());

        assertThat(kernel().processNext(flow.processAt()).orElseThrow().status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(flow.eventCount().getAsInt()).isEqualTo(1);
    }

    @Test
    final void transientFailureRetriesThroughTheSameDurableIntent() {
        Flow flow = flow("retry");
        flow.failOnce().run();
        Intent intent = flow.accept().apply(UUID.randomUUID());
        flow.clock().accept(flow.processAt());
        assertThat(kernel().processNext(flow.processAt()).orElseThrow().status())
                .isEqualTo(IntentStatus.RETRY_WAIT);

        Instant retryAt = flow.processAt().plusSeconds(61);
        flow.clock().accept(retryAt);
        assertThat(kernel().processNext(retryAt).orElseThrow())
                .satisfies(retried -> {
                    assertThat(retried.id()).isEqualTo(intent.id());
                    assertThat(retried.status()).isEqualTo(IntentStatus.SUCCEEDED);
                });
        assertThat(flow.eventCount().getAsInt()).isEqualTo(1);
    }

    @Test
    final void policyCanProduceNoOffer() {
        assertThat(hasNoOffer("no-offer")).isTrue();
    }

    @Test
    final void persistedIntentFailsClosedAfterPolicyChange() {
        assertThat(failsClosedAfterPolicyChange("policy-change")).isTrue();
    }

    @Test
    final void authorityPolicyFiltersActionOffers() {
        assertThat(filtersAuthority("authority")).isTrue();
    }

    @Test
    final void scheduledFactsAreReevaluated() {
        assertThat(schedulesAndRunsReevaluation("reevaluation")).isTrue();
    }

    public record Flow(
            String tenant,
            Instant processAt,
            Function<UUID, Intent> accept,
            Consumer<Instant> clock,
            Runnable makeStale,
            Consumer<Intent> expireLease,
            Runnable failOnce,
            IntSupplier eventCount,
            BooleanSupplier projectionUpdated,
            BooleanSupplier observed) {}
}
