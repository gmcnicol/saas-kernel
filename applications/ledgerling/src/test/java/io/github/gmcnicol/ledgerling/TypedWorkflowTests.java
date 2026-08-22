package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.contract.TypedKernelBehaviourContract;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.ClientReference;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.DocumentRequestId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingDueSoon;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.LedgerlingActions;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordRecordsReceivedCandidateV1;
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
    @Autowired LedgerlingFilingQueries filings;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean java.time.Clock clock;
    @MockitoSpyBean(name = "recordRecordsReceivedHandler")
    TypedIntentHandler<FilingProjection, RecordRecordsReceivedCandidateV1,
            io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordsReceivedEventV1> handler;
    @MockitoSpyBean(name = "recordRecordsReceivedApplicability")
    TypedApplicabilityPolicy<FilingProjection> applicability;

    @Override
    protected Kernel kernel() {
        return kernel;
    }

    @Override
    protected Flow flow(String uniqueId) {
        String tenant = "ledger-contract-" + uniqueId;
        String id = "filing-" + uniqueId;
        Instant dueAt = Instant.parse("2041-08-15T09:00:00Z");
        seedFiling(tenant, id, dueAt, true, false);
        FilingProjection projection = filings.projection(tenant, id);
        var subject = new TypedSubject<>(FilingId.TYPE, projection.filingId());
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                tenant, subject, 1, FilingProjection.TYPE, projection), dueAt);
        var offer = kernel.authorise(
                tenant, snapshot.id(), new Principal("Staff", "contract"), dueAt.plusSeconds(1),
                FilingProjection.TYPE).actionOffers().stream()
                .filter(candidate -> candidate.actionType() == LedgerlingActions.RECORD_RECORDS_RECEIVED)
                .findFirst().orElseThrow();
        Instant processAt = dueAt.plusSeconds(2);
        return new Flow(
                tenant, processAt,
                intentId -> {
                    org.mockito.Mockito.doReturn(dueAt.plusSeconds(1)).when(clock).instant();
                    return kernel.accept(offer.id(), intentId,
                            LedgerlingActions.RECORD_RECORDS_RECEIVED.candidate(
                                    new RecordRecordsReceivedCandidateV1(dueAt.plusSeconds(1))));
                },
                at -> org.mockito.Mockito.doReturn(at).when(clock).instant(),
                () -> kernel.evaluate(new TypedProjectedState<>(
                        tenant, subject, 2, FilingProjection.TYPE, projection), processAt.minusSeconds(1)),
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
                                .getFirst().id(), LedgerlingActions.RECORD_RECORDS_RECEIVED).events().size(),
                () -> !filings.projection(tenant, id).recordsOutstanding(),
                () -> !meters.find("kernel.intent.outcomes").counters().isEmpty());
    }

    @Override
    protected boolean hasNoOffer(String uniqueId) {
        String tenant = "ledger-contract-" + uniqueId;
        String id = "filing-" + uniqueId;
        Instant dueAt = Instant.parse("2041-09-15T09:00:00Z");
        seedFiling(tenant, id, dueAt, false, true);
        FilingProjection projection = filings.projection(tenant, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), dueAt);
        return kernel.authorise(tenant, snapshot.id(), new Principal("Staff", "contract"),
                dueAt.plusSeconds(1), FilingProjection.TYPE).actionOffers().isEmpty();
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
        String tenant = "ledger-contract-" + uniqueId;
        String id = "filing-" + uniqueId;
        Instant dueAt = Instant.parse("2041-10-15T09:00:00Z");
        Instant startsAt = dueAt.minus(java.time.Duration.ofDays(7));
        seedFiling(tenant, id, dueAt, true, false);
        FilingProjection projection = filings.projection(tenant, id);
        var first = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), startsAt.minusSeconds(1));
        var next = kernel.processNextReevaluation(startsAt).orElseThrow();
        return first.reevaluateAt().equals(java.util.Optional.of(startsAt))
                && next.facts().find(FilingDueSoon.TYPE).isPresent();
    }

    @Override
    protected boolean filtersAuthority(String uniqueId) {
        String tenant = "ledger-contract-" + uniqueId;
        String id = "filing-" + uniqueId;
        Instant dueAt = Instant.parse("2041-11-15T09:00:00Z");
        seedFiling(tenant, id, dueAt, true, false);
        FilingProjection projection = filings.projection(tenant, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), dueAt);
        return kernel.authorise(tenant, snapshot.id(), new Principal("Client", "contract"),
                dueAt.plusSeconds(1), FilingProjection.TYPE).actionOffers().isEmpty();
    }

    @Test
    void evaluatesAuthorisesExecutesAndProjectsOneTypedFiling() {
        Instant dueAt = Instant.parse("2040-08-15T09:00:00Z");
        seedFiling("tenant-one", "filing-a", dueAt, true, false);
        var projection = filings.projection("tenant-one", "filing-a");
        var filingId = projection.filingId();
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(FilingId.TYPE, filingId), 1,
                FilingProjection.TYPE, projection), dueAt);
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Staff", "gareth"), dueAt.plusSeconds(1),
                        FilingProjection.TYPE)
                .actionOffers().getFirst();
        assertThat(offer.actionType()).isSameAs(LedgerlingActions.RECORD_RECORDS_RECEIVED);
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(1)).when(clock).instant();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(),
                LedgerlingActions.RECORD_RECORDS_RECEIVED.candidate(
                        new RecordRecordsReceivedCandidateV1(dueAt.plusSeconds(1))));

        org.mockito.Mockito.doReturn(dueAt.plusSeconds(2)).when(clock).instant();
        var completed = kernel.processNext(dueAt.plusSeconds(2)).orElseThrow();

        assertThat(completed.id()).isEqualTo(intent.id());
        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.readIntentEvidence(
                        "tenant-one", intent.id(), LedgerlingActions.RECORD_RECORDS_RECEIVED).events())
                .singleElement().extracting(event -> event.requestId().value()).isEqualTo("request-a");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', 'tenant-one', true)", String.class);
            assertThat(jdbc.queryForObject("""
                    SELECT records_outstanding FROM ledger_filing_projection
                    WHERE tenant_id = 'tenant-one' AND filing_id = 'filing-a'
                    """, Boolean.class)).isFalse();
        });
    }

    @Test
    void reevaluatesTypedProjectionWhenScheduledFactBecomesDue() {
        Instant dueAt = Instant.parse("2040-09-15T09:00:00Z");
        Instant startsAt = dueAt.minus(java.time.Duration.ofDays(7));
        seedFiling("tenant-one", "filing-b", dueAt, true, false);
        var projection = filings.projection("tenant-one", "filing-b");
        var filingId = projection.filingId();

        var first = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(FilingId.TYPE, filingId), 1,
                FilingProjection.TYPE, projection), startsAt.minusSeconds(1));
        var reevaluated = kernel.processNextReevaluation(startsAt).orElseThrow();

        assertThat(first.reevaluateAt()).contains(startsAt);
        assertThat(reevaluated.facts().find(FilingDueSoon.TYPE)).isPresent();
    }

    @Test
    void correctionEventSupersedesQueuedReevaluationOfOldState() {
        Instant dueAt = Instant.parse("2040-12-15T09:00:00Z");
        Instant startsAt = dueAt.minus(java.time.Duration.ofDays(7));
        seedFiling("tenant-correction", "filing-correction", dueAt, true, false);
        FilingProjection projection = filings.projection("tenant-correction", "filing-correction");
        var snapshot = kernel.evaluate(new TypedProjectedState<>("tenant-correction",
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), startsAt.minusSeconds(2));
        assertThat(snapshot.reevaluateAt()).contains(startsAt);
        var offer = kernel.authorise("tenant-correction", snapshot.id(),
                        new Principal("Staff", "gareth"), startsAt.minusSeconds(1), FilingProjection.TYPE)
                .actionOffers().stream()
                .filter(candidate -> candidate.actionType() == LedgerlingActions.RECORD_RECORDS_RECEIVED)
                .findFirst().orElseThrow();
        org.mockito.Mockito.doReturn(startsAt.minusSeconds(1)).when(clock).instant();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(),
                LedgerlingActions.RECORD_RECORDS_RECEIVED.candidate(
                        new RecordRecordsReceivedCandidateV1(startsAt.minusSeconds(1))));
        org.mockito.Mockito.doReturn(startsAt).when(clock).instant();

        assertThat(kernel.processNext(startsAt).orElseThrow().status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.readIntentEvidence(
                        "tenant-correction", intent.id(), LedgerlingActions.RECORD_RECORDS_RECEIVED).events())
                .singleElement().extracting(event -> event.requestId().value()).isEqualTo("request-correction");
        assertThat(filings.projection("tenant-correction", "filing-correction").recordsOutstanding()).isFalse();
        assertThat(kernel.processNextReevaluation(startsAt)).isEmpty();
    }

    private static void seedFiling(
            String tenant, String filingId, Instant dueAt, boolean outstanding, boolean preparationStarted) {
        var admin = admin();
        admin.update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, request_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES (?, ?, ?, 'ACME', ?, ?, ?)
                """, tenant, filingId, filingId.replace("filing", "request"),
                java.sql.Timestamp.from(dueAt), outstanding, preparationStarted);
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
