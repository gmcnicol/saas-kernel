package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    }

    @Autowired Kernel kernel;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void derivesDueFollowUpAndThreeApplicableActionsReproducibly() {
        var state = new ProjectedState("tenant-one", new Subject("crm.Contact", "alex"), 7, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false"));
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");

        var first = kernel.evaluate(state, evaluatedAt);
        var repeated = kernel.evaluate(state, evaluatedAt);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.tenantId()).isEqualTo("tenant-one");
        assertThat(first.subject()).isEqualTo(new Subject("crm.Contact", "alex"));
        assertThat(first.projectedStateVersion()).isEqualTo(7);
        assertThat(first.evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(first.applicationVersion().id()).isEqualTo("io.github.gmcnicol.crm");
        assertThat(first.applicationVersion().version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(first.kernelVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(first.semanticPackVersion().id()).isEqualTo("io.github.gmcnicol.crm.semantic");
        assertThat(first.semanticPackVersion().checksum()).hasSize(64);
        assertThat(first.projectedStateChecksum()).hasSize(64);
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
        var snapshot = kernel.evaluate(
                new ProjectedState("tenant-one", new Subject("crm.Contact", "alex"), 8, Map.of(
                        "followUpDueAt", dueAt.toString(),
                        "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:00:00Z"));

        assertThat(snapshot.facts()).isEmpty();
        assertThat(snapshot.applicableActions()).isEmpty();
        assertThat(snapshot.reevaluateAt()).contains(dueAt);
    }

    @Test
    void rejectsDifferentContentForOneProjectedStateVersion() {
        var subject = new Subject("crm.Contact", "alex");
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");
        kernel.evaluate(new ProjectedState("tenant-one", subject, 9, Map.of(
                "followUpDueAt", "2026-08-20T09:00:00Z")), evaluatedAt);

        assertThatThrownBy(() -> kernel.evaluate(new ProjectedState("tenant-one", subject, 9, Map.of(
                "followUpDueAt", "2026-08-21T09:00:00Z")), evaluatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Projected State version already exists with different content");
    }

    @Test
    void authorisesOwnerFieldsFactsAndActionsButHidesThemFromViewer() {
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", "alex"), 10, Map.of(
                        "displayName", "Alex Morgan",
                        "privateNote", "Do not disclose",
                        "followUpDueAt", "2026-08-15T09:00:00Z",
                        "followUpCompleted", "false")), Instant.parse("2026-08-15T10:00:00Z"));
        var authorisedAt = Instant.parse("2026-08-15T10:01:00Z");

        var owner = kernel.authorise(
                "tenant-one", snapshot.id(), new Principal("Owner", "gareth"), authorisedAt);
        var viewer = kernel.authorise(
                "tenant-one", snapshot.id(), new Principal("Viewer", "guest"), authorisedAt);

        assertThat(owner.fields()).containsEntry("io.github.gmcnicol.crm.Contact.displayName", "Alex Morgan")
                .doesNotContainValue("Do not disclose");
        assertThat(owner.facts()).extracting(fact -> fact.type())
                .containsExactly("io.github.gmcnicol.crm.FollowUpDue");
        assertThat(owner.actionOffers()).extracting(offer -> offer.actionId()).containsExactlyInAnyOrder(
                "io.github.gmcnicol.crm.CrmActions.recordInteraction",
                "io.github.gmcnicol.crm.CrmActions.snoozeFollowUp",
                "io.github.gmcnicol.crm.CrmActions.completeFollowUp");
        Integer evidence = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return jdbc.queryForObject("""
                    SELECT count(*) FROM kernel.action_offer
                    WHERE tenant_id = ? AND evaluation_snapshot_id = ? AND principal_type = ? AND principal_id = ?
                      AND subject_type = ? AND subject_id = ? AND state_version = ?
                      AND semantic_pack_id = ? AND semantic_pack_checksum = ?
                      AND authorisation_bundle_id = ? AND length(authorisation_bundle_checksum) = 64
                      AND authorised_at = ? AND decision_correlation IS NOT NULL
                    """, Integer.class, "tenant-one", snapshot.id(), "Owner", "gareth", "crm.Contact", "alex", 10,
                    snapshot.semanticPackVersion().id(), snapshot.semanticPackVersion().checksum(),
                    "io.github.gmcnicol.crm.authorisation", java.sql.Timestamp.from(authorisedAt));
        });
        assertThat(evidence).isEqualTo(3);
        assertThat(viewer.fields()).containsOnlyKeys("io.github.gmcnicol.crm.Contact.displayName");
        assertThat(viewer.facts()).extracting(fact -> fact.type())
                .containsExactly("io.github.gmcnicol.crm.FollowUpDue");
        assertThat(viewer.actionOffers()).isEmpty();
    }

    @Test
    void deniesCrossTenantSnapshotAccess() {
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", "alex"), 11, Map.of(
                        "followUpDueAt", "2026-08-15T09:00:00Z")), Instant.parse("2026-08-15T10:00:00Z"));

        assertThatThrownBy(() -> kernel.authorise(
                "tenant-two", snapshot.id(), new Principal("Owner", "gareth"),
                Instant.parse("2026-08-15T10:01:00Z")))
                .isInstanceOf(io.github.gmcnicol.kernel.application.AuthorisationDeniedException.class);
    }

    @Test
    void deniesMalformedAndMissingTenantContext() {
        assertThatThrownBy(() -> kernel.evaluate(new ProjectedState(
                "bad tenant", new Subject("crm.Contact", "alex"), 12, Map.of()),
                Instant.parse("2026-08-15T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);

        Integer visible = new TransactionTemplate(transactionManager).execute(status -> {
            return jdbc.queryForObject("SELECT count(*) FROM kernel.evaluation_snapshot", Integer.class);
        });
        assertThat(visible).isZero();
    }

    @Test
    void acceptsIntentIdempotentlyAndRejectsConflictForgeryInvalidPayloadAndStaleness() {
        var state = new ProjectedState("tenant-one", new Subject("crm.Contact", "intent-alex"), 20, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false"));
        var evaluatedAt = Instant.parse("2026-08-15T10:00:00Z");
        var snapshot = kernel.evaluate(state, evaluatedAt);
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        var intentId = UUID.randomUUID();
        var payload = new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Spoke to Alex"));

        var accepted = kernel.accept(offer.id(), intentId, payload);
        var repeated = kernel.accept(offer.id(), intentId, payload);

        assertThat(repeated).isEqualTo(accepted);
        assertThat(accepted.id()).isEqualTo(intentId);
        assertThat(accepted.actionOfferId()).isEqualTo(offer.id());
        assertThat(accepted.status()).isEqualTo(IntentStatus.PENDING);
        assertThat(kernel.evaluate(state, evaluatedAt)).isEqualTo(snapshot);
        assertThatThrownBy(() -> kernel.accept(offer.id(), intentId, new CandidatePayload(
                payload.type(), 1, Map.of("note", "Different"))))
                .isInstanceOf(IntentConflictException.class);
        assertThatThrownBy(() -> kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                payload.type(), 2, payload.values())))
                .isInstanceOf(IntentRejectedException.class);
        assertThatThrownBy(() -> kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                payload.type(), 1, Map.of("unexpected", "value"))))
                .isInstanceOf(IntentRejectedException.class);
        assertThatThrownBy(() -> kernel.accept(UUID.randomUUID(), UUID.randomUUID(), payload))
                .isInstanceOf(IntentRejectedException.class);

        var trace = new W3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "kernel=test");
        var linkedIntentId = UUID.randomUUID();
        var linked = kernel.accept(offer.id(), linkedIntentId, new CandidatePayload(
                payload.type(), payload.version(), payload.values(), Optional.of(trace), Optional.of(intentId)));
        assertThat(linked.id()).isEqualTo(linkedIntentId);

        kernel.evaluate(new ProjectedState("tenant-one", state.subject(), 21, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "true")), Instant.parse("2026-08-15T10:02:00Z"));
        assertThatThrownBy(() -> kernel.accept(offer.id(), UUID.randomUUID(), payload))
                .isInstanceOf(IntentRejectedException.class);

        var persisted = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent", Integer.class),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent_payload_value", Integer.class),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent_audit", Integer.class),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.intent
                            WHERE id = ? AND prior_intent_id = ? AND traceparent = ? AND tracestate = ?
                            """, Integer.class, linkedIntentId, intentId, trace.traceparent(), trace.tracestate()));
        });
        assertThat(persisted).containsExactly(2, 2, 2, 1);
    }
}
