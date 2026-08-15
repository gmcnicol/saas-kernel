package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class ApplicationEvaluationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired Kernel kernel;

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
}
