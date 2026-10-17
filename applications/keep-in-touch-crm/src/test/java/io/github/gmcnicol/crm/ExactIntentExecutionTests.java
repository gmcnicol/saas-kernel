package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.InteractionRecordedEventV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.ExactIntentCompletion;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class ExactIntentExecutionTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withInitScript("postgres-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "kernel_test_login");
        properties.add("spring.datasource.password", () -> "kernel-test");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", postgres::getUsername);
        properties.add("spring.flyway.password", postgres::getPassword);
        properties.add("kernel.intent-worker.enabled", () -> "false");
    }

    @Autowired Kernel kernel;
    @Autowired CrmContactQueries contacts;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean(name = "typedRecordInteractionHandler")
    TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, InteractionRecordedEventV1> handler;
    @MockitoSpyBean(name = "typedInteractionRecordedProjector")
    TypedEventProjector<FollowUpProjection, InteractionRecordedEventV1> projector;

    @Test
    void exactExecutionRequiresAnActiveCallerTransactionBeforeAcceptingAnything() {
        Flow flow = flow("requires-transaction");
        UUID intentId = UUID.randomUUID();

        assertThatThrownBy(() -> kernel.execute(
                flow.tenant(), flow.principal(), flow.offerId(), intentId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("not saved")),
                flow.executedAt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active caller transaction");

        assertThat(kernel.findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
        assertThat(contacts.projection(flow.tenant(), flow.contactId()).followUpCompleted()).isFalse();
        assertThat(admin().queryForObject(
                "SELECT count(*) FROM crm_interaction WHERE tenant_id = ?", Integer.class, flow.tenant())).isZero();
    }

    @Test
    void callerCommitPublishesTheSucceededIntentAndApplicationProjectionTogether() {
        Flow flow = flow("commit");
        UUID intentId = UUID.randomUUID();

        ExactIntentCompletion completion = new TransactionTemplate(transactionManager).execute(status ->
                kernel.execute(flow.tenant(), flow.principal(), flow.offerId(), intentId,
                        TypedCrmActions.RECORD_INTERACTION.candidate(
                                new RecordInteractionCandidateV1("completed exactly")),
                        flow.executedAt()));

        assertThat(completion).isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(kernel.findIntents(IntentQuery.tenant(flow.tenant())))
                .singleElement()
                .satisfies(intent -> {
                    assertThat(intent.id()).isEqualTo(intentId);
                    assertThat(intent.status()).isEqualTo(IntentStatus.SUCCEEDED);
                });
        assertThat(contacts.projection(flow.tenant(), flow.contactId()).followUpCompleted()).isTrue();
        assertThat(kernel.findIntentEvents(flow.tenant(), intentId)).hasSize(1);
    }

    @Test
    void asynchronousWorkerCannotSeeTheExactIntentBeforeCallerCommit() throws Exception {
        Flow flow = flow("worker-visibility");
        UUID intentId = UUID.randomUUID();
        var handlerEntered = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            RecordInteractionCandidateV1 candidate = invocation.getArgument(1);
            if (candidate.note().equals("blocked exact request")) {
                handlerEntered.countDown();
                if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release exact handler");
                }
            }
            return invocation.callRealMethod();
        }).when(handler).handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<ExactIntentCompletion> exact = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(
                            status -> execute(flow, intentId, "blocked exact request")));
            assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(kernel.processNext(Instant.parse("2100-01-01T00:00:00Z"))).isEmpty();

            releaseHandler.countDown();
            assertThat(exact.get()).isEqualTo(ExactIntentCompletion.COMPLETED);
        } finally {
            releaseHandler.countDown();
        }
        assertThat(kernel.findIntents(IntentQuery.tenant(flow.tenant())))
                .singleElement().extracting(intent -> intent.status()).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    void exactExecutionCompletesOnlyTheNamedIntentWhenUnrelatedWorkIsDue() {
        Flow unrelated = flow("unrelated-due");
        UUID unrelatedId = UUID.randomUUID();
        kernel.accept(unrelated.tenant(), unrelated.principal(), unrelated.offerId(), unrelatedId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("unrelated")));
        Flow exact = flow("named");
        UUID exactId = UUID.randomUUID();

        ExactIntentCompletion completion = new TransactionTemplate(transactionManager)
                .execute(status -> execute(exact, exactId, "named"));

        assertThat(completion).isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(contacts.projection(exact.tenant(), exact.contactId()).followUpCompleted()).isTrue();
        assertThat(contacts.projection(unrelated.tenant(), unrelated.contactId()).followUpCompleted()).isFalse();
        assertThat(kernel.findIntents(IntentQuery.tenant(unrelated.tenant())))
                .singleElement().extracting(intent -> intent.status()).isEqualTo(IntentStatus.PENDING);
        assertThat(kernel.processNext(Instant.parse("2100-01-01T00:00:00Z"))).get()
                .extracting(intent -> intent.id()).isEqualTo(unrelatedId);
    }

    @Test
    void processDueStillCompletesItsExistingBoundedQueueAfterExactExecution() {
        Flow firstDue = flow("process-due-first");
        Flow secondDue = flow("process-due-second");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        kernel.accept(firstDue.tenant(), firstDue.principal(), firstDue.offerId(), firstId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("first due")));
        kernel.accept(secondDue.tenant(), secondDue.principal(), secondDue.offerId(), secondId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("second due")));
        Flow exact = flow("before-process-due");
        new TransactionTemplate(transactionManager).execute(
                status -> execute(exact, UUID.randomUUID(), "exact before due"));

        assertThat(kernel.processDue(Instant.parse("2100-01-01T00:00:00Z")))
                .extracting(intent -> intent.id())
                .containsExactlyInAnyOrder(firstId, secondId);
        assertThat(contacts.projection(firstDue.tenant(), firstDue.contactId()).followUpCompleted()).isTrue();
        assertThat(contacts.projection(secondDue.tenant(), secondDue.contactId()).followUpCompleted()).isTrue();
    }

    @Test
    void retryableHandlerFailureRollsBackInsteadOfLeavingRunnableWork() {
        Flow flow = flow("handler-failure");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RetryableIntentException("handler failed")).when(handler).handle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> execute(flow, intentId, "handler failure")))
                .isInstanceOf(RetryableIntentException.class)
                .hasMessage("handler failed");

        assertNoExecutionEffect(flow, intentId);
    }

    @Test
    void projectorFailureRollsBackItsApplicationWriteAndEveryKernelEffect() {
        Flow flow = flow("projector-failure");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("projector failed after writing");
        }).when(projector).project(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> execute(flow, intentId, "projector failure")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projector failed after writing");

        assertNoExecutionEffect(flow, intentId);
    }

    @Test
    void callerRollbackRemovesTheIntentEventReceiptAndApplicationChanges() {
        Flow flow = flow("caller-rollback");
        UUID intentId = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(execute(flow, intentId, "rolled back")).isEqualTo(ExactIntentCompletion.COMPLETED);
            status.setRollbackOnly();
        });

        assertNoExecutionEffect(flow, intentId);
    }

    @Test
    void concurrentIdenticalRequestsHaveOneEffectAndReconcileToTheSameOutcome() throws Exception {
        Flow flow = flow("concurrent");
        UUID intentId = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ExactIntentCompletion> request = () -> {
                barrier.await();
                return new TransactionTemplate(transactionManager).execute(status -> kernel.execute(
                        flow.tenant(), flow.principal(), flow.offerId(), intentId,
                        TypedCrmActions.RECORD_INTERACTION.candidate(
                                new RecordInteractionCandidateV1("same request")),
                        flow.executedAt()));
            };
            Future<ExactIntentCompletion> first = executor.submit(request);
            Future<ExactIntentCompletion> second = executor.submit(request);

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            ExactIntentCompletion.COMPLETED,
                            ExactIntentCompletion.ALREADY_SUCCEEDED);
        }
        assertThat(kernel.findIntentEvents(flow.tenant(), intentId)).hasSize(1);
        assertThat(admin().queryForObject(
                "SELECT count(*) FROM crm_interaction WHERE tenant_id = ?", Integer.class, flow.tenant()))
                .isEqualTo(1);
    }

    @Test
    void identicalSucceededRequestReconcilesButChangedCandidateConflicts() {
        Flow flow = flow("request-identity");
        UUID intentId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);

        assertThat(transactions.<ExactIntentCompletion>execute(status -> execute(flow, intentId, "original")))
                .isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThat(transactions.<ExactIntentCompletion>execute(status -> execute(flow, intentId, "original")))
                .isEqualTo(ExactIntentCompletion.ALREADY_SUCCEEDED);
        assertThatThrownBy(() -> transactions.execute(status -> execute(flow, intentId, "changed")))
                .isInstanceOf(IntentConflictException.class);
        assertThatThrownBy(() -> transactions.execute(status -> kernel.execute(
                flow.tenant(), flow.principal(), flow.offerId(), intentId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("original")),
                flow.executedAt().plusSeconds(1))))
                .isInstanceOf(IntentConflictException.class);

        assertThat(kernel.findIntentEvents(flow.tenant(), intentId)).hasSize(1);
        assertThat(admin().queryForObject(
                "SELECT notes FROM crm_interaction WHERE tenant_id = ? AND intent_id = ?",
                String.class, flow.tenant(), intentId)).isEqualTo("original");
    }

    private ExactIntentCompletion execute(Flow flow, UUID intentId, String note) {
        return kernel.execute(flow.tenant(), flow.principal(), flow.offerId(), intentId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1(note)),
                flow.executedAt());
    }

    private void assertNoExecutionEffect(Flow flow, UUID intentId) {
        assertThat(kernel.findIntents(IntentQuery.tenant(flow.tenant()))).isEmpty();
        assertThat(kernel.findIntentEvents(flow.tenant(), intentId)).isEmpty();
        assertThat(contacts.projection(flow.tenant(), flow.contactId()).followUpCompleted()).isFalse();
        assertThat(admin().queryForObject(
                "SELECT count(*) FROM crm_interaction WHERE tenant_id = ?", Integer.class, flow.tenant())).isZero();
    }

    private Flow flow(String suffix) {
        String tenant = "exact-" + suffix;
        String contactId = "contact-" + suffix;
        Instant dueAt = Instant.parse("2020-08-15T09:00:00Z");
        admin().update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES (?, ?, ?, ?, ?)
                """, tenant, contactId, contactId, java.sql.Timestamp.from(dueAt), UUID.randomUUID());
        FollowUpProjection projection = contacts.projection(tenant, contactId);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        Principal principal = new Principal("Owner", "exact-tests");
        UUID offerId = kernel.authorise(tenant, snapshot.id(), principal, dueAt.plusSeconds(2),
                FollowUpProjection.TYPE).actionOffers().getFirst().id();
        return new Flow(tenant, contactId, principal, offerId, dueAt.plusSeconds(3));
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private record Flow(
            String tenant, String contactId, Principal principal, UUID offerId, Instant executedAt) {}
}
