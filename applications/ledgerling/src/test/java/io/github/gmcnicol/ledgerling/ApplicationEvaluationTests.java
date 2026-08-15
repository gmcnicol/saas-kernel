package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import java.time.Instant;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class ApplicationEvaluationTests {

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

    @MockitoSpyBean("recordRecordsReceivedHandler")
    IntentHandler recordsHandler;

    @MockitoSpyBean
    private SemanticPackVersion semanticPack;

    @Test
    void derivesFilingAndRecordsFactsFromContrastingState() {
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");
        var outstanding = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("ledgerling.Filing", "acme-2026"), 3, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "true",
                        "documentRequestId", "request-42")), evaluatedAt);

        assertThat(outstanding.tenantId()).isEqualTo("tenant-one");
        assertThat(outstanding.applicationVersion().id()).isEqualTo("io.github.gmcnicol.ledgerling");
        assertThat(outstanding.applicationVersion().version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(outstanding.kernelVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(outstanding.semanticPackVersion().id()).isEqualTo("io.github.gmcnicol.ledgerling.semantic");
        assertThat(outstanding.facts()).extracting(fact -> fact.type()).containsExactly(
                "io.github.gmcnicol.ledgerling.FilingDueSoon",
                "io.github.gmcnicol.ledgerling.RecordsOutstanding");
        assertThat(outstanding.applicableActions()).singleElement().satisfies(action -> {
            assertThat(action.actionId())
                    .isEqualTo("io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
            assertThat(action.policyId()).isEqualTo("io.github.gmcnicol.ledgerling.recordsOutstanding");
        });

        var ready = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("ledgerling.Filing", "acme-2026"), 4, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "false",
                        "preparationStarted", "false")), evaluatedAt);

        assertThat(ready.facts()).extracting(fact -> fact.type())
                .containsExactly("io.github.gmcnicol.ledgerling.FilingDueSoon");
        assertThat(ready.applicableActions()).singleElement().satisfies(action -> {
            assertThat(action.actionId())
                    .isEqualTo("io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation");
            assertThat(action.policyId()).isEqualTo("io.github.gmcnicol.ledgerling.preparationReady");
        });
    }

    @Test
    void catchesUpDeadlineAndSupersedesEventCorrectionThroughApplicationSeam() {
        var subject = new Subject("ledgerling.Filing", "temporal-acme");
        var firstDue = Instant.parse("2036-09-01T09:00:00Z");
        var firstChange = firstDue.minusSeconds(7 * 24 * 60 * 60);
        for (int request = 0; request < 100; request++) {
            kernel.processNextReevaluation(firstChange.minusSeconds(86_400));
        }
        var initial = kernel.evaluate(new ProjectedState("tenant-one", subject, 200, Map.of(
                "filingDueAt", firstDue.toString(), "recordsOutstanding", "true",
                "documentRequestId", "request-temporal-acme",
                "preparationStarted", "false")), Instant.parse("2036-08-01T09:00:00Z"));
        assertThat(initial.reevaluateAt()).contains(firstChange);
        assertThat(kernel.processNextReevaluation(firstChange.minusSeconds(1))).isEmpty();

        var offer = kernel.authorise(
                        "tenant-one", initial.id(), new Principal("Staff", "accountant"),
                        Instant.parse("2036-08-02T08:59:00Z"))
                .actionOffers().getFirst();
        var correction = kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput", 1,
                Map.of("receivedAt", "2036-08-02T09:00:00Z")));
        assertThat(processUntil(correction.id(), Instant.parse("2036-08-02T09:00:00Z")).status())
                .isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.processNextReevaluation(Instant.parse("2036-08-02T09:00:01Z")))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.projectedStateVersion()).isEqualTo(201);
                    assertThat(snapshot.facts()).isEmpty();
                    assertThat(snapshot.applicableActions()).extracting(action -> action.actionId())
                            .containsExactly("io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation");
                    assertThat(snapshot.reevaluateAt()).contains(firstChange);
                });
        assertThat(kernel.processNextReevaluation(firstChange))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.facts())
                        .extracting(fact -> fact.type())
                        .containsExactly("io.github.gmcnicol.ledgerling.FilingDueSoon"));
        assertThat(kernel.processNextReevaluation(firstChange)).isEmpty();
    }

    @Test
    void discardsLedgerlingReevaluationWhenSemanticPackChanges() {
        var dueAt = Instant.parse("2037-09-01T09:00:00Z");
        for (int request = 0; request < 100; request++) {
            kernel.processNextReevaluation(dueAt.minusSeconds(1));
        }
        var subject = new Subject("ledgerling.Filing", "stale-temporal-acme");
        kernel.evaluate(new ProjectedState("tenant-one", subject, 202, Map.of(
                "filingDueAt", dueAt.plusSeconds(7 * 24 * 60 * 60).toString(),
                "recordsOutstanding", "false", "preparationStarted", "false")),
                dueAt.minusSeconds(86_400));

        org.mockito.Mockito.doReturn("0".repeat(64)).when(semanticPack).checksum();
        assertThat(kernel.processNextReevaluation(dueAt)).isEmpty();
        org.mockito.Mockito.reset(semanticPack);
        assertThat(kernel.processNextReevaluation(dueAt)).isEmpty();
    }

    @Test
    void authorisesStaffButGivesClientNoActionOffers() {
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("ledgerling.Filing", "acme-2026"), 5, Map.of(
                        "status", "Waiting for records",
                        "staffNote", "Call on Monday",
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "true",
                        "documentRequestId", "request-42")), Instant.parse("2026-08-15T10:00:00Z"));
        var authorisedAt = Instant.parse("2026-08-15T10:01:00Z");

        var staff = kernel.authorise(
                "tenant-one", snapshot.id(), new Principal("Staff", "accountant"), authorisedAt);
        var client = kernel.authorise(
                "tenant-one", snapshot.id(), new Principal("Client", "acme"), authorisedAt);

        assertThat(staff.fields()).containsEntry("io.github.gmcnicol.ledgerling.Filing.status", "Waiting for records")
                .containsEntry("io.github.gmcnicol.ledgerling.Filing.staffNote", "Call on Monday");
        assertThat(staff.facts()).hasSize(2);
        assertThat(staff.actionOffers()).extracting(offer -> offer.actionId())
                .containsExactly("io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
        assertThat(client.fields()).containsOnlyKeys("io.github.gmcnicol.ledgerling.Filing.status");
        assertThat(client.facts()).extracting(fact -> fact.type())
                .containsExactly("io.github.gmcnicol.ledgerling.FilingDueSoon");
        assertThat(client.actionOffers()).isEmpty();
    }

    @Test
    void acceptsRecordsReceivedIntentThroughTheApplicationInterface() {
        var subject = new Subject("ledgerling.Filing", "intent-acme");
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", subject, 30, Map.of(
                        "filingDueAt", "2026-08-30T09:00:00Z",
                        "recordsOutstanding", "true",
                        "documentRequestId", "request-84")), Instant.parse("2026-08-15T10:00:00Z"));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Staff", "accountant"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().getFirst();

        var intent = kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput",
                1,
                Map.of("receivedAt", "2026-08-15T10:02:00Z")));

        assertThat(intent.actionOfferId()).isEqualTo(offer.id());
        assertThat(intent.status()).isEqualTo(IntentStatus.PENDING);

        var completed = processUntil(intent.id(), Instant.parse("2026-08-15T23:03:00Z"));
        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        var reevaluated = kernel.processNextReevaluation(Instant.parse("2026-08-15T23:04:00Z")).orElseThrow();
        assertThat(reevaluated.applicableActions()).singleElement().satisfies(action -> assertThat(action.actionId())
                .isEqualTo("io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation"));
        assertThat(reevaluated.reevaluateAt()).contains(Instant.parse("2026-08-23T09:00:00Z"));
    }

    @Test
    void rejectsIntentWhenStateChangesAfterAcceptance() {
        var subject = new Subject("ledgerling.Filing", "stale-intent-acme");
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", subject, 40, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "true",
                        "documentRequestId", "request-85")), Instant.parse("2026-08-15T10:00:00Z"));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Staff", "accountant"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().getFirst();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput", 1,
                Map.of("receivedAt", "2026-08-15T10:02:00Z")));

        kernel.evaluate(new ProjectedState("tenant-one", subject, 41, Map.of(
                "filingDueAt", "2026-08-20T09:00:00Z",
                "recordsOutstanding", "false",
                "documentRequestId", "request-85")), Instant.parse("2026-08-15T10:03:00Z"));

        var rejected = processUntil(intent.id(), Instant.parse("2026-08-15T23:04:00Z"));
        assertThat(rejected.status()).isEqualTo(IntentStatus.STALE);
        assertThat(rejected.failureReason()).contains(IntentFailureReason.STATE_OR_SEMANTIC_STALE);
    }

    @Test
    void retriesIsolatesInspectsAndLinksRecoveryThroughLedgerling() {
        Instant base = Instant.now().plusSeconds(10);
        while (kernel.processNext(base).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }

        var transientIntent = acceptRecords("ledger-transient", 50);
        org.mockito.Mockito.doThrow(new RetryableIntentException("temporary outage"))
                .when(recordsHandler).handle(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(processUntil(transientIntent.id(), base).status()).isEqualTo(IntentStatus.RETRY_WAIT);
        org.mockito.Mockito.reset(recordsHandler);
        assertThat(processUntil(transientIntent.id(), base.plusSeconds(70)).status())
                .isEqualTo(IntentStatus.SUCCEEDED);

        var failedIntent = acceptRecords("ledger-failed", 60);
        var successfulSubject = new Subject("ledgerling.Filing", "ledger-isolated-success");
        var successfulSnapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", successfulSubject, 70, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "false", "preparationStarted", "false",
                        "documentRequestId", "request-ledger-isolated")),
                Instant.parse("2026-08-15T10:00:00Z"));
        var successfulOffer = kernel.authorise(
                        "tenant-one", successfulSnapshot.id(), new Principal("Staff", "accountant"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().getFirst();
        var successfulIntent = kernel.accept(successfulOffer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.StartPreparationInput", 1, Map.of("confirmed", "true")));

        org.mockito.Mockito.doThrow(new IllegalArgumentException("deterministic failure"))
                .when(recordsHandler).handle(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(kernel.processDue(base.plusSeconds(80))).extracting(intent -> intent.status())
                .containsExactlyInAnyOrder(IntentStatus.FAILED, IntentStatus.SUCCEEDED);
        org.mockito.Mockito.reset(recordsHandler);

        var failedQuery = new IntentQuery("tenant-one", Optional.of(IntentStatus.FAILED),
                Optional.of(new Subject("ledgerling.Filing", "ledger-failed")),
                Optional.of(failedIntent.id()), Optional.empty());
        assertThat(kernel.findIntents(failedQuery)).singleElement();
        assertThat(kernel.findIntentAudit(failedQuery)).extracting(entry -> entry.toStatus())
                .containsExactly(IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.FAILED);

        var recoveryOffer = currentRecordsOffer("ledger-failed", 60);
        var pendingPrior = acceptRecords("ledger-pending-prior", 75);
        assertThatThrownBy(() -> kernel.accept(recoveryOffer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput", 1,
                Map.of("receivedAt", "2026-08-15T10:02:00Z"), Optional.empty(),
                Optional.of(pendingPrior.id())))).isInstanceOf(IntentRejectedException.class);
        var recovery = kernel.accept(recoveryOffer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput", 1,
                Map.of("receivedAt", "2026-08-15T10:02:00Z"), Optional.empty(),
                Optional.of(failedIntent.id())));
        assertThat(processUntil(recovery.id(), base.plusSeconds(90)).status()).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    void reclaimsExpiredLedgerlingLeaseWithoutDuplicateEvents() throws Exception {
        Instant dueAt = Instant.now().plusSeconds(10);
        while (kernel.processNext(dueAt).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }
        var intent = acceptRecords("ledger-crashed", 80);
        Instant claimedAt = Instant.now();
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var claim = admin.prepareStatement("SELECT intent_id FROM kernel.claim_due_intent(?, ?, ?, ?, ?, ?)");
                var expire = admin.prepareStatement("UPDATE kernel.intent SET lease_until = ? WHERE id = ?")) {
            claim.setObject(1, UUID.randomUUID());
            claim.setTimestamp(2, java.sql.Timestamp.from(dueAt));
            claim.setTimestamp(3, java.sql.Timestamp.from(claimedAt));
            claim.setTimestamp(4, java.sql.Timestamp.from(claimedAt.plusSeconds(30)));
            claim.setObject(5, UUID.randomUUID());
            claim.setObject(6, UUID.randomUUID());
            assertThat(claim.executeQuery()).satisfies(result -> assertThat(result.next()).isTrue());
            assertThat(kernel.processNext(dueAt)).isEmpty();
            expire.setTimestamp(1, java.sql.Timestamp.from(Instant.EPOCH));
            expire.setObject(2, intent.id());
            assertThat(expire.executeUpdate()).isEqualTo(1);
        }
        assertThat(processUntil(intent.id(), dueAt).status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.processNext(dueAt)).isEmpty();
        assertThat(kernel.findIntentAudit(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.empty(), Optional.of(intent.id()), Optional.empty())))
                .extracting(entry -> entry.toStatus())
                .containsExactly(IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.CLAIMED,
                        IntentStatus.SUCCEEDED);
    }

    @AfterEach
    void restoreHandler() {
        org.mockito.Mockito.reset(recordsHandler, semanticPack);
    }

    private io.github.gmcnicol.kernel.application.Intent acceptRecords(String subjectId, long version) {
        var offer = currentRecordsOffer(subjectId, version);
        return kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput", 1,
                Map.of("receivedAt", "2026-08-15T10:02:00Z")));
    }

    private io.github.gmcnicol.kernel.application.ActionOffer currentRecordsOffer(String subjectId, long version) {
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("ledgerling.Filing", subjectId), version, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z", "recordsOutstanding", "true",
                        "documentRequestId", "request-" + subjectId)), Instant.parse("2026-08-15T10:00:00Z"));
        return kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Staff", "accountant"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().getFirst();
    }

    private io.github.gmcnicol.kernel.application.Intent processUntil(UUID intentId, Instant processedAt) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var processed = kernel.processNext(processedAt).orElseThrow();
            if (processed.id().equals(intentId)) {
                return processed;
            }
        }
        throw new AssertionError("Intent was not processed");
    }
}
