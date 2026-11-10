package io.github.gmcnicol.kernel.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.ExactIntentCompletion;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reusable public-{@link Kernel} acceptance contract for exact Intent execution.
 *
 * <p>Acceptance Fixtures implement only the transaction boundary and product-shaped setup below. The inherited
 * tests exercise behavior through {@link Kernel}; fixture-specific persistence remains hidden behind
 * {@link ExactFlow}'s observable Application outcomes.
 */
public interface ExactIntentExecutionContract {

    /** Creates an isolated, offered Action whose projection has not yet been updated. */
    ExactFlow exactFlow(String uniqueId);

    /** Supplies the fixture's transaction boundary while hiding fixture-specific setup from inherited tests. */
    CallerTransactions callerTransactions();

    /** Adapts the Spring transaction manager shared by an Application and the Kernel. */
    static CallerTransactions springTransactions(PlatformTransactionManager transactionManager) {
        var transactions = new TransactionTemplate(transactionManager);
        return new CallerTransactions() {
            @Override public <T> T commit(Supplier<T> work) {
                return transactions.execute(status -> work.get());
            }

            @Override public void rollback(Runnable work) {
                transactions.executeWithoutResult(status -> {
                    work.run();
                    status.setRollbackOnly();
                });
            }
        };
    }

    @Test
    default void exactExecutionRequiresACallerTransaction() {
        ExactFlow flow = exactFlow("requires-transaction");
        UUID intentId = UUID.randomUUID();

        assertThatThrownBy(() -> execute(flow, intentId, CandidateVariation.ORIGINAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active caller transaction");

        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
        assertThat(flow.projectionUpdated().getAsBoolean()).isFalse();
        assertThat(flow.applicationChangeCount().getAsInt()).isZero();
    }

    @Test
    default void exactExecutionCompletesOnlyTheNamedIntent() {
        ExactFlow unrelated = exactFlow("unrelated-due");
        UUID unrelatedId = UUID.randomUUID();
        unrelated.kernel().accept(
                unrelated.tenant(), unrelated.principal(), unrelated.actionOfferId(), unrelatedId,
                unrelated.candidate().apply(CandidateVariation.ORIGINAL));
        ExactFlow exact = exactFlow("named");
        UUID exactId = UUID.randomUUID();

        ExactIntentCompletion completion = inCallerTransaction(
                () -> execute(exact, exactId, CandidateVariation.ORIGINAL));

        assertThat(completion).isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(exact.projectionUpdated().getAsBoolean()).isTrue();
        assertThat(unrelated.projectionUpdated().getAsBoolean()).isFalse();
        assertThat(unrelated.kernel().findIntents(IntentQuery.tenant(unrelated.tenant())))
                .singleElement().extracting(intent -> intent.status()).isEqualTo(IntentStatus.PENDING);
        assertThat(unrelated.kernel().processNext(Instant.parse("2100-01-01T00:00:00Z")))
                .get().extracting(intent -> intent.id()).isEqualTo(unrelatedId);
        assertThat(unrelated.projectionUpdated().getAsBoolean()).isTrue();
    }

    @Test
    default void outerRollbackRemovesEveryKernelAndApplicationEffect() {
        ExactFlow flow = exactFlow("rollback");
        UUID intentId = UUID.randomUUID();

        inRollbackOnlyTransaction(() -> assertThat(execute(flow, intentId, CandidateVariation.ORIGINAL))
                .isEqualTo(ExactIntentCompletion.COMPLETED));

        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intentId)).isEmpty();
        assertThat(flow.projectionUpdated().getAsBoolean()).isFalse();
        assertThat(flow.applicationChangeCount().getAsInt()).isZero();
    }

    @Test
    default void committedReplayReconcilesWithoutDuplicatesAndChangedContentConflicts() {
        ExactFlow flow = exactFlow("request-identity");
        UUID intentId = UUID.randomUUID();

        assertThat(inCallerTransaction(() -> execute(flow, intentId, CandidateVariation.ORIGINAL)))
                .isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(inCallerTransaction(() -> execute(flow, intentId, CandidateVariation.ORIGINAL)))
                .isEqualTo(ExactIntentCompletion.ALREADY_SUCCEEDED);
        assertThatThrownBy(() -> inCallerTransaction(() -> execute(flow, intentId, CandidateVariation.CHANGED)))
                .isInstanceOf(IntentConflictException.class);

        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intentId)).hasSize(1);
        assertThat(flow.applicationChangeCount().getAsInt()).isEqualTo(1);
    }

    @Test
    default void asynchronousWorkerCannotObserveAnUncommittedExactIntent() throws Exception {
        ExactFlow flow = exactFlow("worker-visibility");
        UUID intentId = UUID.randomUUID();
        var exactCompleted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var exact = executor.submit(() -> inCallerTransaction(() -> {
                ExactIntentCompletion completion = execute(flow, intentId, CandidateVariation.ORIGINAL);
                exactCompleted.countDown();
                await(allowCommit);
                return completion;
            }));
            assertThat(exactCompleted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(flow.kernel().processNext(Instant.parse("2100-01-01T00:00:00Z"))).isEmpty();
            assertThat(flow.projectionUpdated().getAsBoolean()).isFalse();
            assertThat(flow.applicationChangeCount().getAsInt()).isZero();

            allowCommit.countDown();
            assertThat(exact.get(5, TimeUnit.SECONDS)).isEqualTo(ExactIntentCompletion.COMPLETED);
            assertThat(flow.projectionUpdated().getAsBoolean()).isTrue();
            assertThat(flow.applicationChangeCount().getAsInt()).isEqualTo(1);
        } finally {
            allowCommit.countDown();
        }
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant())))
                .singleElement().extracting(intent -> intent.status()).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    default void ordinaryAsynchronousProcessDueStillCompletesQueuedIntents() {
        ExactFlow first = exactFlow("process-due-first");
        ExactFlow second = exactFlow("process-due-second");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        first.kernel().accept(
                first.tenant(), first.principal(), first.actionOfferId(), firstId,
                first.candidate().apply(CandidateVariation.ORIGINAL));
        second.kernel().accept(
                second.tenant(), second.principal(), second.actionOfferId(), secondId,
                second.candidate().apply(CandidateVariation.ORIGINAL));

        assertThat(first.kernel().processDue(Instant.parse("2100-01-01T00:00:00Z")))
                .extracting(intent -> intent.id())
                .containsExactlyInAnyOrder(firstId, secondId);
        assertThat(first.projectionUpdated().getAsBoolean()).isTrue();
        assertThat(second.projectionUpdated().getAsBoolean()).isTrue();
    }

    @Test
    default void callerCommitPublishesKernelSuccessAndApplicationChangesTogether() {
        ExactFlow flow = exactFlow("commit");
        UUID intentId = UUID.randomUUID();

        ExactIntentCompletion completion = inCallerTransaction(
                () -> execute(flow, intentId, CandidateVariation.ORIGINAL));

        assertThat(completion).isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(flow.kernel().findIntents(IntentQuery.tenant(flow.tenant())))
                .singleElement()
                .satisfies(intent -> {
                    assertThat(intent.id()).isEqualTo(intentId);
                    assertThat(intent.status()).isEqualTo(IntentStatus.SUCCEEDED);
                });
        assertThat(flow.kernel().findIntentEvents(flow.tenant(), intentId)).hasSize(1);
        assertThat(flow.projectionUpdated().getAsBoolean()).isTrue();
        assertThat(flow.applicationChangeCount().getAsInt()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for acceptance-contract barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for acceptance-contract barrier", exception);
        }
    }

    private <T> T inCallerTransaction(Supplier<T> work) {
        return callerTransactions().commit(work);
    }

    private void inRollbackOnlyTransaction(Runnable work) {
        callerTransactions().rollback(work);
    }

    private static ExactIntentCompletion execute(
            ExactFlow flow, UUID intentId, CandidateVariation variation) {
        return flow.kernel().execute(
                flow.tenant(), flow.principal(), flow.actionOfferId(), intentId,
                flow.candidate().apply(variation), flow.executedAt());
    }

    enum CandidateVariation { ORIGINAL, CHANGED }

    interface CallerTransactions {
        <T> T commit(Supplier<T> work);
        void rollback(Runnable work);
    }

    /**
     * Product-neutral inputs and observable Application outcomes for one exact execution scenario.
     * Candidate construction, projection reads, and domain-record reads are supplied by each Acceptance Fixture.
     */
    record ExactFlow(
            Kernel kernel,
            String tenant,
            Principal principal,
            UUID actionOfferId,
            Instant executedAt,
            Function<CandidateVariation, TypedCandidatePayload<?>> candidate,
            BooleanSupplier projectionUpdated,
            IntSupplier applicationChangeCount) {}
}
