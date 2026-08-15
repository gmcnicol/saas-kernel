package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.Application;
import io.github.gmcnicol.kernel.application.ProjectedState;
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

    @Autowired Application application;

    @Test
    void derivesFilingAndRecordsFactsFromContrastingState() {
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");
        var outstanding = application.evaluate(new ProjectedState(
                "tenant-one", "ledgerling.Filing:acme-2026", 3, Map.of(
                        "filingDueAt", "2026-08-20T09:00:00Z",
                        "recordsOutstanding", "true",
                        "documentRequestId", "request-42")), evaluatedAt);

        assertThat(outstanding.applicationId()).isEqualTo("io.github.gmcnicol.ledgerling");
        assertThat(outstanding.semanticPack().id()).isEqualTo("io.github.gmcnicol.ledgerling.semantic");
        assertThat(outstanding.facts()).extracting(fact -> fact.type()).containsExactly(
                "io.github.gmcnicol.ledgerling.FilingDueSoon",
                "io.github.gmcnicol.ledgerling.RecordsOutstanding");
        assertThat(outstanding.applicableActions()).singleElement().satisfies(action -> {
            assertThat(action.actionId())
                    .isEqualTo("io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
            assertThat(action.policyId()).isEqualTo("io.github.gmcnicol.ledgerling.recordsOutstanding");
        });

        var ready = application.evaluate(new ProjectedState(
                "tenant-one", "ledgerling.Filing:acme-2026", 4, Map.of(
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
}
