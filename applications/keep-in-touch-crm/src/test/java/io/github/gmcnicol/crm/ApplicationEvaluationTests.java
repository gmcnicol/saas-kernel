package io.github.gmcnicol.crm;

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
    void derivesDueFollowUpAndThreeApplicableActionsReproducibly() {
        var state = new ProjectedState("tenant-one", "crm.Contact:alex", 7, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false"));
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");

        var first = application.evaluate(state, evaluatedAt);
        var repeated = application.evaluate(state, evaluatedAt);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.subject()).isEqualTo("crm.Contact:alex");
        assertThat(first.projectedStateVersion()).isEqualTo(7);
        assertThat(first.evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(first.applicationId()).isEqualTo("io.github.gmcnicol.crm");
        assertThat(first.semanticPack().id()).isEqualTo("io.github.gmcnicol.crm.semantic");
        assertThat(first.semanticPack().checksum()).hasSize(64);
        assertThat(first.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.type()).isEqualTo("io.github.gmcnicol.crm.FollowUpDue");
            assertThat(fact.derivationId()).isEqualTo("io.github.gmcnicol.crm.deriveFollowUpDue");
            assertThat(fact.values()).containsEntry("contactId", "alex");
        });
        assertThat(first.applicableActions())
                .extracting(action -> action.actionId())
                .containsExactlyInAnyOrder(
                        "io.github.gmcnicol.crm.CrmActions.recordInteraction",
                        "io.github.gmcnicol.crm.CrmActions.snoozeFollowUp",
                        "io.github.gmcnicol.crm.CrmActions.completeFollowUp");
        assertThat(first.applicableActions())
                .allSatisfy(action -> assertThat(action.policyId())
                        .isEqualTo("io.github.gmcnicol.crm.followUpActions"));
        assertThat(first.reevaluateAt()).isEmpty();
    }

    @Test
    void schedulesExplicitDueTimeWithoutReadingAmbientClock() {
        var dueAt = Instant.parse("2026-08-20T09:00:00Z");
        var snapshot = application.evaluate(
                new ProjectedState("tenant-one", "crm.Contact:alex", 8, Map.of(
                        "followUpDueAt", dueAt.toString(),
                        "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:00:00Z"));

        assertThat(snapshot.facts()).isEmpty();
        assertThat(snapshot.applicableActions()).isEmpty();
        assertThat(snapshot.reevaluateAt()).contains(dueAt);
    }
}
