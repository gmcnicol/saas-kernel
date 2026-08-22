package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpDue;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.contract.TypedKernelBehaviourContract;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.time.Instant;
import java.util.UUID;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class TypedWorkflowTests extends TypedKernelBehaviourContract {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CrmContactQueries contacts;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean java.time.Clock clock;
    @MockitoSpyBean(name = "typedRecordInteractionHandler")
    TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.InteractionRecordedEventV1> handler;
    @MockitoSpyBean(name = "typedRecordInteractionApplicability")
    TypedApplicabilityPolicy<FollowUpProjection> applicability;

    @Override
    protected Kernel kernel() {
        return kernel;
    }

    @Override
    protected Flow flow(String uniqueId) {
        String tenant = "crm-contract-" + uniqueId;
        String id = "contact-" + uniqueId;
        Instant dueAt = Instant.parse("2041-08-15T09:00:00Z");
        seedContact(tenant, id, dueAt, false);
        FollowUpProjection projection = contacts.projection(tenant, id);
        var subject = new TypedSubject<>(ContactId.TYPE, projection.contactId());
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                tenant, subject, 1, FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        var offer = kernel.authorise(
                tenant, snapshot.id(), new Principal("Owner", "contract"), dueAt.plusSeconds(2),
                FollowUpProjection.TYPE).actionOffers().getFirst();
        Instant processAt = dueAt.plusSeconds(3);
        return new Flow(
                tenant, processAt,
                intentId -> {
                    org.mockito.Mockito.doReturn(dueAt.plusSeconds(2)).when(clock).instant();
                    return kernel.accept(offer.id(), intentId, TypedCrmActions.RECORD_INTERACTION.candidate(
                            new RecordInteractionCandidateV1("contract")));
                },
                at -> org.mockito.Mockito.doReturn(at).when(clock).instant(),
                () -> kernel.evaluate(new TypedProjectedState<>(
                        tenant, subject, 2, FollowUpProjection.TYPE, projection), processAt.minusSeconds(1)),
                intent -> admin().update("""
                        UPDATE kernel.typed_intent SET status = 'CLAIMED', attempt_count = 1,
                            lease_token = ?, lease_until = ? WHERE id = ?
                        """, UUID.randomUUID(), java.sql.Timestamp.from(processAt.minusSeconds(1)), intent.id()),
                () -> org.mockito.Mockito.doThrow(new RetryableIntentException("once"))
                        .doCallRealMethod().when(handler).handle(
                                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()),
                () -> kernel.readIntentEvidence(
                        tenant, kernel.findIntents(io.github.gmcnicol.kernel.application.IntentQuery.tenant(tenant))
                                .getFirst().id(), TypedCrmActions.RECORD_INTERACTION).events().size(),
                () -> contacts.projection(tenant, id).followUpCompleted(),
                () -> !meters.find("kernel.intent.outcomes").counters().isEmpty());
    }

    @Override
    protected boolean hasNoOffer(String uniqueId) {
        String tenant = "crm-contract-" + uniqueId;
        String id = "contact-" + uniqueId;
        Instant dueAt = Instant.parse("2041-09-15T09:00:00Z");
        seedContact(tenant, id, dueAt, true);
        FollowUpProjection projection = contacts.projection(tenant, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        return kernel.authorise(tenant, snapshot.id(), new Principal("Owner", "contract"),
                dueAt.plusSeconds(2), FollowUpProjection.TYPE).actionOffers().isEmpty();
    }

    @Override
    protected boolean failsClosedAfterPolicyChange(String uniqueId) {
        Flow flow = flow(uniqueId);
        flow.accept().apply(UUID.randomUUID());
        org.mockito.Mockito.doReturn(false).when(applicability).isApplicable(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        flow.clock().accept(flow.processAt());
        try {
            return kernel.processNext(flow.processAt()).orElseThrow().status() == IntentStatus.FAILED
                    && flow.eventCount().getAsInt() == 0;
        } finally {
            org.mockito.Mockito.reset(applicability);
        }
    }

    @Override
    protected boolean schedulesAndRunsReevaluation(String uniqueId) {
        String tenant = "crm-contract-" + uniqueId;
        String id = "contact-" + uniqueId;
        Instant dueAt = Instant.parse("2041-10-15T09:00:00Z");
        seedContact(tenant, id, dueAt, false);
        FollowUpProjection projection = contacts.projection(tenant, id);
        var first = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), dueAt.minusSeconds(1));
        var next = kernel.processNextReevaluation(dueAt).orElseThrow();
        return first.reevaluateAt().equals(java.util.Optional.of(dueAt))
                && next.facts().find(FollowUpDue.TYPE).isPresent();
    }

    @Override
    protected boolean filtersAuthority(String uniqueId) {
        String tenant = "crm-contract-" + uniqueId;
        String id = "contact-" + uniqueId;
        Instant dueAt = Instant.parse("2041-11-15T09:00:00Z");
        seedContact(tenant, id, dueAt, false);
        FollowUpProjection projection = contacts.projection(tenant, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        return kernel.authorise(tenant, snapshot.id(), new Principal("Viewer", "contract"),
                dueAt.plusSeconds(2), FollowUpProjection.TYPE).actionOffers().isEmpty();
    }

    @Test
    void evaluatesAuthorisesExecutesAndProjectsOneTypedFollowUp() {
        Instant dueAt = Instant.parse("2040-08-15T09:00:00Z");
        seedContact("tenant-one", "alex", dueAt, false);
        var projection = contacts.projection("tenant-one", "alex");
        var contactId = projection.contactId();
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(ContactId.TYPE, contactId), 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"), dueAt.plusSeconds(2),
                        FollowUpProjection.TYPE)
                .actionOffers().getFirst();
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(2)).when(clock).instant();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("Spoke today")));

        org.mockito.Mockito.doReturn(dueAt.plusSeconds(3)).when(clock).instant();
        var completed = kernel.processNext(dueAt.plusSeconds(3)).orElseThrow();

        assertThat(completed.id()).isEqualTo(intent.id());
        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.readIntentEvidence("tenant-one", intent.id(), TypedCrmActions.RECORD_INTERACTION)
                        .events())
                .singleElement().extracting(event -> event.contactId().value()).isEqualTo("alex");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', 'tenant-one', true)", String.class);
            assertThat(jdbc.queryForObject("""
                    SELECT open_follow_up_id IS NULL FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'alex'
                    """, Boolean.class)).isTrue();
        });
    }

    private static void seedContact(String tenant, String contactId, Instant dueAt, boolean complete) {
        var admin = admin();
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES (?, ?, ?, ?, ?)
                """, tenant, contactId, contactId, java.sql.Timestamp.from(dueAt),
                complete ? null : UUID.randomUUID());
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
