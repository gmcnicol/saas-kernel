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
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
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
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean(name = "typedRecordInteractionHandler")
    TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, InteractionRecordedEventV1> handler;
    @MockitoSpyBean(name = "typedRecordInteractionApplicability")
    TypedApplicabilityPolicy<FollowUpProjection> applicability;
    @MockitoSpyBean
    TypedAuthorisationModel<FollowUpProjection> authorisationModel;
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
    void aBoundKernelDatasourceConnectionWithoutARealDatasourceTransactionIsRejected() {
        Flow flow = flow("resource-only-transaction");
        UUID intentId = UUID.randomUUID();
        var resourceOnlyManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                try {
                    TransactionSynchronizationManager.bindResource(
                            dataSource, new ConnectionHolder(dataSource.getConnection()));
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
            @Override protected void doCleanupAfterCompletion(Object transaction) {
                var holder = (ConnectionHolder) TransactionSynchronizationManager.unbindResource(dataSource);
                try {
                    holder.getConnection().close();
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };

        new TransactionTemplate(resourceOnlyManager).executeWithoutResult(status ->
                assertThatThrownBy(() -> execute(flow, intentId, "not saved"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("active caller transaction"));

        assertNoExecutionEffect(flow, intentId);
    }

    @Test
    void exactSuccessTelemetryIsPublishedOnlyAfterCommitAndDroppedOnRollback(CapturedOutput output) {
        Flow committed = flow("telemetry-commit");
        UUID committedIntentId = UUID.randomUUID();
        double beforeCommit = succeededOutcomeCount();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(execute(committed, committedIntentId, "committed telemetry"))
                    .isEqualTo(ExactIntentCompletion.COMPLETED);
            assertThat(succeededOutcomeCount()).isEqualTo(beforeCommit);
            assertThat(output).doesNotContain(committedIntentId.toString());
        });
        assertThat(succeededOutcomeCount()).isEqualTo(beforeCommit + 1);
        assertThat(output).contains(committedIntentId.toString(), "\"intent_outcome\":\"succeeded\"");

        Flow rolledBack = flow("telemetry-rollback");
        UUID rolledBackIntentId = UUID.randomUUID();
        double beforeRollback = succeededOutcomeCount();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(execute(rolledBack, rolledBackIntentId, "rolled back telemetry"))
                    .isEqualTo(ExactIntentCompletion.COMPLETED);
            status.setRollbackOnly();
        });
        assertThat(succeededOutcomeCount()).isEqualTo(beforeRollback);
        assertThat(output).doesNotContain(rolledBackIntentId.toString());
    }

    @Test
    void exactExecutionRestoresTheCallerDatabaseRoleBeforeReturning() {
        Flow flow = flow("caller-role");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String callerRole = jdbc.queryForObject("SELECT current_user", String.class);
            jdbc.execute("CREATE TEMP TABLE exact_caller_role_probe(value integer) ON COMMIT DROP");

            assertThat(execute(flow, UUID.randomUUID(), "restore caller role"))
                    .isEqualTo(ExactIntentCompletion.COMPLETED);

            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(callerRole);
            jdbc.update("INSERT INTO exact_caller_role_probe(value) VALUES (1)");
            assertThat(jdbc.queryForObject(
                    "SELECT sum(value) FROM exact_caller_role_probe", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void staleProjectedStateFailsTheCallAndRollsBackTheCallerTransaction() {
        Flow flow = flow("stale-state");
        UUID intentId = UUID.randomUUID();
        admin().update("""
                UPDATE crm_contact_engagement_projection SET state_version = 2
                WHERE tenant_id = ? AND contact_id = ?
                """, flow.tenant(), flow.contactId());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            makeApplicationChange(flow);
            execute(flow, intentId, "stale request");
        })).isInstanceOf(IntentRejectedException.class);

        assertNoExecutionEffect(flow, intentId);
        assertApplicationChangeRolledBack(flow);
    }

    @Test
    void changedApplicabilityFailsTheCallAndRollsBackTheCallerTransaction() {
        Flow flow = flow("changed-applicability");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doReturn(false).when(applicability).isApplicable(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                makeApplicationChange(flow);
                execute(flow, intentId, "no longer applicable");
            })).isInstanceOf(IntentRejectedException.class);
        } finally {
            org.mockito.Mockito.reset(applicability);
        }

        assertNoExecutionEffect(flow, intentId);
        assertApplicationChangeRolledBack(flow);
    }

    @Test
    void cedarDenialFailsTheCallAndRollsBackTheCallerTransaction() {
        Flow flow = flow("cedar-denial");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("Cedar input unavailable"))
                .when(authorisationModel).fields();

        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                makeApplicationChange(flow);
                execute(flow, intentId, "denied request");
            })).isInstanceOf(IntentRejectedException.class);
        } finally {
            org.mockito.Mockito.reset(authorisationModel);
        }

        assertNoExecutionEffect(flow, intentId);
        assertApplicationChangeRolledBack(flow);
    }

    @Test
    void retryableHandlerFailureRollsBackInsteadOfLeavingRunnableWork() {
        Flow flow = flow("handler-failure");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RetryableIntentException("handler failed")).when(handler).handle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            makeApplicationChange(flow);
            execute(flow, intentId, "handler failure");
        }))
                .isInstanceOf(RetryableIntentException.class)
                .hasMessage("handler failed");

        assertNoExecutionEffect(flow, intentId);
        assertApplicationChangeRolledBack(flow);
    }

    @Test
    void projectorFailureRollsBackItsApplicationWriteAndEveryKernelEffect() {
        Flow flow = flow("projector-failure");
        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("projector failed after writing");
        }).when(projector).project(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            makeApplicationChange(flow);
            execute(flow, intentId, "projector failure");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projector failed after writing");

        assertNoExecutionEffect(flow, intentId);
        assertApplicationChangeRolledBack(flow);
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
    void changedExecutionTimeUnderTheSameIntentIdentityConflicts() {
        Flow flow = flow("execution-time-identity");
        UUID intentId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);

        assertThat(transactions.<ExactIntentCompletion>execute(status -> execute(flow, intentId, "original")))
                .isEqualTo(ExactIntentCompletion.COMPLETED);
        assertThatThrownBy(() -> transactions.execute(status -> kernel.execute(
                flow.tenant(), flow.principal(), flow.offerId(), intentId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("original")),
                flow.executedAt().plusSeconds(1))))
                .isInstanceOf(IntentConflictException.class);

        assertThat(kernel.findIntentEvents(flow.tenant(), intentId)).hasSize(1);
    }

    private ExactIntentCompletion execute(Flow flow, UUID intentId, String note) {
        return kernel.execute(flow.tenant(), flow.principal(), flow.offerId(), intentId,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1(note)),
                flow.executedAt());
    }

    private void makeApplicationChange(Flow flow) {
        jdbc.execute("SET LOCAL ROLE kernel_worker");
        jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, flow.tenant());
        jdbc.update("""
                UPDATE crm_contact_engagement_projection SET display_name = 'transient caller change'
                WHERE tenant_id = ? AND contact_id = ?
                """, flow.tenant(), flow.contactId());
    }

    private void assertApplicationChangeRolledBack(Flow flow) {
        assertThat(admin().queryForObject("""
                SELECT display_name FROM crm_contact_engagement_projection
                WHERE tenant_id = ? AND contact_id = ?
                """, String.class, flow.tenant(), flow.contactId())).isEqualTo(flow.contactId());
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

    private double succeededOutcomeCount() {
        var counter = meters.find("kernel.intent.outcomes").tag("outcome", "succeeded").counter();
        return counter == null ? 0 : counter.count();
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private record Flow(
            String tenant, String contactId, Principal principal, UUID offerId, Instant executedAt) {}
}
