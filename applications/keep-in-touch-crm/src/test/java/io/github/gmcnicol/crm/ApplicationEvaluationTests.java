package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.internal.CurrentExecutionBasisTest;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
class ApplicationEvaluationTests extends CurrentExecutionBasisTest {

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
        Integer scheduled = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return jdbc.queryForObject("""
                    SELECT count(*) FROM kernel.reevaluation_request
                    WHERE subject_type = 'crm.Contact' AND subject_id = 'alex'
                      AND expected_state_version = 8 AND semantic_pack_id = ?
                      AND semantic_pack_checksum = ? AND due_at = ?
                    """, Integer.class, snapshot.semanticPackVersion().id(),
                    snapshot.semanticPackVersion().checksum(), java.sql.Timestamp.from(dueAt));
        });
        assertThat(scheduled).isEqualTo(1);
    }

    @Test
    void reevaluatesTimeOnlyChangeAndSupersedesCorrectedWork() {
        var subject = new Subject("crm.Contact", "temporal-alex");
        var firstDue = Instant.parse("2035-08-15T11:00:00Z");
        for (int request = 0; request < 100; request++) {
            kernel.processNextReevaluation(firstDue.minusSeconds(3_600));
        }
        var initial = kernel.evaluate(new ProjectedState("tenant-one", subject, 200, Map.of(
                "followUpDueAt", firstDue.toString(), "followUpCompleted", "false")),
                Instant.parse("2035-08-15T10:00:00Z"));
        assertThat(initial.facts()).isEmpty();
        assertThat(kernel.processNextReevaluation(firstDue.minusSeconds(1))).isEmpty();

        var dueSnapshot = kernel.processNextReevaluation(firstDue).orElseThrow();
        var correctedDue = Instant.parse("2035-08-15T12:00:00Z");
        restoreDerivationAt(firstDue);
        var snoozeOffer = kernel.authorise(
                        "tenant-one", dueSnapshot.id(), new Principal("Owner", "gareth"), firstDue)
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("snoozeFollowUp"))
                .findFirst().orElseThrow();
        var correction = kernel.accept(snoozeOffer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.SnoozeFollowUpInput", 1, Map.of("until", correctedDue.toString())));
        assertThat(processUntil(correction.id(), firstDue.plusSeconds(1)).status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.processNextReevaluation(firstDue.plusSeconds(1)))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.projectedStateVersion()).isEqualTo(201);
                    assertThat(snapshot.facts()).isEmpty();
                    assertThat(snapshot.reevaluateAt()).contains(correctedDue);
                });
        assertThat(kernel.processNextReevaluation(correctedDue.plusSeconds(3_600)))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.facts())
                        .extracting(fact -> fact.type())
                        .containsExactly("io.github.gmcnicol.crm.FollowUpDue"));
        assertThat(kernel.processNextReevaluation(correctedDue.plusSeconds(3_600))).isEmpty();

        var expiresAt = correctedDue.plusSeconds(7_200);
        var expiring = kernel.evaluate(new ProjectedState("tenant-one", new Subject(
                "crm.Contact", "expiring-alex"), 202, Map.of(
                "followUpDueAt", correctedDue.toString(), "followUpExpiresAt", expiresAt.toString(),
                "followUpCompleted", "false")), correctedDue.plusSeconds(1));
        assertThat(expiring.reevaluateAt()).contains(expiresAt);

        var concurrentDue = Instant.parse("2035-08-16T09:00:00Z");
        kernel.evaluate(new ProjectedState("tenant-one", new Subject(
                "crm.Contact", "concurrent-temporal-alex"), 203, Map.of(
                "followUpDueAt", concurrentDue.toString(), "followUpCompleted", "false")),
                concurrentDue.minusSeconds(3_600));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> kernel.processNextReevaluation(concurrentDue));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> kernel.processNextReevaluation(concurrentDue));
        assertThat(java.util.stream.Stream.of(first.join(), second.join())
                .flatMap(Optional::stream)
                .filter(snapshot -> snapshot.subject().id().equals("concurrent-temporal-alex"))
                .count())
                .isEqualTo(1);

        var staleDue = Instant.parse("2035-08-17T09:00:00Z");
        kernel.evaluate(new ProjectedState("tenant-one", new Subject(
                "crm.Contact", "stale-temporal-alex"), 204, Map.of(
                "followUpDueAt", staleDue.toString(), "followUpCompleted", "false")),
                staleDue.minusSeconds(3_600));
        changeCurrentSemanticPack();
        assertThat(kernel.processNextReevaluation(staleDue)).isEmpty();
        restoreCurrentSemanticPack();
        assertThat(kernel.processNextReevaluation(staleDue)).isEmpty();
    }

    @Test
    void reclaimsExpiredReevaluationLease() throws Exception {
        var dueAt = Instant.parse("2037-08-15T09:00:00Z");
        for (int request = 0; request < 100; request++) {
            kernel.processNextReevaluation(dueAt.minusSeconds(1));
        }
        var subject = new Subject("crm.Contact", "leased-temporal-alex");
        kernel.evaluate(new ProjectedState("tenant-one", subject, 205, Map.of(
                "followUpDueAt", dueAt.toString(), "followUpCompleted", "false")),
                dueAt.minusSeconds(3_600));
        Instant claimedAt = Instant.now();
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var claim = admin.prepareStatement(
                        "SELECT subject_id FROM kernel.claim_due_reevaluation(?, ?, ?, ?)");
                var expire = admin.prepareStatement("""
                        UPDATE kernel.reevaluation_request SET lease_until = ?
                        WHERE subject_type = 'crm.Contact' AND subject_id = 'leased-temporal-alex'
                        """)) {
            claim.setObject(1, UUID.randomUUID());
            claim.setTimestamp(2, java.sql.Timestamp.from(dueAt));
            claim.setTimestamp(3, java.sql.Timestamp.from(claimedAt));
            claim.setTimestamp(4, java.sql.Timestamp.from(claimedAt.plusSeconds(30)));
            assertThat(claim.executeQuery()).satisfies(result -> {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("subject_id")).isEqualTo(subject.id());
            });
            assertThat(kernel.processNextReevaluation(dueAt)).isEmpty();
            expire.setTimestamp(1, java.sql.Timestamp.from(Instant.EPOCH));
            assertThat(expire.executeUpdate()).isEqualTo(1);
        }
        assertThat(kernel.processNextReevaluation(dueAt))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.subject()).isEqualTo(subject));
        assertThat(kernel.processNextReevaluation(dueAt)).isEmpty();
    }

    @Test
    void rollsBackReevaluationWhenLeaseExpiresDuringDerivation() {
        var dueAt = Instant.parse("2038-08-15T09:00:00Z");
        for (int request = 0; request < 100; request++) {
            kernel.processNextReevaluation(dueAt.minusSeconds(1));
        }
        var subject = new Subject("crm.Contact", "expired-during-derivation");
        kernel.evaluate(new ProjectedState("tenant-one", subject, 206, Map.of(
                "followUpDueAt", dueAt.toString(), "followUpCompleted", "false")),
                dueAt.minusSeconds(3_600));

        var claimedAt = Instant.parse("2038-08-15T08:00:00Z");
        expireReevaluationLeaseDuringDerivation(claimedAt);
        assertThat(kernel.processNextReevaluation(dueAt)).isEmpty();
        Integer committed = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return jdbc.queryForObject("""
                    SELECT count(*) FROM kernel.evaluation_snapshot
                    WHERE subject_type = ? AND subject_id = ? AND evaluated_at = ?
                    """, Integer.class, subject.type(), subject.id(), java.sql.Timestamp.from(dueAt));
        });
        assertThat(committed).isZero();

        restoreDerivationAt(claimedAt.plusSeconds(31));
        assertThat(kernel.processNextReevaluation(dueAt))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.subject()).isEqualTo(subject));
    }

    @Test
    void catchesUpOverdueReevaluationAfterApplicationRestart() {
        var subject = new Subject("crm.Contact", "restart-temporal-alex");
        var dueAt = Instant.parse("2039-08-15T09:00:00Z");
        var properties = Map.<String, Object>of(
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", "kernel_test_login",
                "spring.datasource.password", "kernel-test",
                "spring.flyway.url", postgres.getJdbcUrl(),
                "spring.flyway.user", postgres.getUsername(),
                "spring.flyway.password", postgres.getPassword(),
                "kernel.intent-worker.enabled", "false");

        try (var beforeRestart = new SpringApplicationBuilder(KeepInTouchCrmApplication.class)
                .properties(properties).run()) {
            beforeRestart.getBean(Kernel.class).evaluate(new ProjectedState(
                    "tenant-one", subject, 207, Map.of(
                            "followUpDueAt", dueAt.toString(), "followUpCompleted", "false")),
                    dueAt.minusSeconds(3_600));
        }
        try (var afterRestart = new SpringApplicationBuilder(KeepInTouchCrmApplication.class)
                .properties(properties).run()) {
            var restartedKernel = afterRestart.getBean(Kernel.class);
            var caughtUp = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> restartedKernel.processNextReevaluation(dueAt.plusSeconds(3_600)))
                    .flatMap(Optional::stream)
                    .filter(snapshot -> snapshot.subject().equals(subject))
                    .findFirst();
            assertThat(caughtUp).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.facts()).hasSize(1);
                assertThat(snapshot.applicableActions()).hasSize(3);
            });
        }

        var persistedOutputCounts = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.evaluation_fact fact
                            JOIN kernel.evaluation_snapshot snapshot ON snapshot.id = fact.snapshot_id
                            WHERE snapshot.subject_type = ? AND snapshot.subject_id = ?
                            """, Integer.class, subject.type(), subject.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.evaluation_applicable_action action
                            JOIN kernel.evaluation_snapshot snapshot ON snapshot.id = action.snapshot_id
                            WHERE snapshot.subject_type = ? AND snapshot.subject_id = ?
                            """, Integer.class, subject.type(), subject.id()));
        });
        assertThat(persistedOutputCounts).containsExactly(1, 3);
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
        var selfLinkedIntentId = UUID.randomUUID();
        assertThatThrownBy(() -> kernel.accept(offer.id(), selfLinkedIntentId, new CandidatePayload(
                payload.type(), payload.version(), payload.values(), Optional.empty(), Optional.of(selfLinkedIntentId))))
                .isInstanceOf(IntentRejectedException.class);

        failRecordInteractionDeterministically();
        assertThat(processUntil(intentId, Instant.now().plusSeconds(10)).status()).isEqualTo(IntentStatus.FAILED);
        restoreRecordInteractionHandler();
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
        assertThat(persisted).containsExactly(2, 2, 4, 1);
    }

    @Test
    void processesRecordInteractionAtomicallyAndRemovesFollowUpActions() {
        var subject = new Subject("crm.Contact", "processed-alex");
        var initial = new ProjectedState("tenant-one", subject, 40, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false"));
        var snapshot = kernel.evaluate(initial, Instant.parse("2026-08-15T10:00:00Z"));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Spoke to Alex")));

        var completed = processUntil(intent.id(), Instant.parse("2026-08-15T23:02:00Z"));

        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        var resultingState = new ProjectedState("tenant-one", subject, 41, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "true",
                "lastInteractionNote", "Spoke to Alex"));
        var reevaluated = kernel.evaluate(resultingState, Instant.parse("2026-08-15T10:03:00Z"));
        assertThat(reevaluated.applicableActions()).isEmpty();

        var persisted = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id = ?", Integer.class,
                            intent.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.event event_record
                            JOIN kernel.event_payload_value payload ON payload.event_id = event_record.id
                            WHERE event_record.intent_id = ? AND event_record.sequence = 1
                              AND event_record.event_type = 'io.github.gmcnicol.crm.InteractionRecorded'
                              AND event_record.resulting_state_version = 41
                              AND payload.name = 'contactId' AND payload.value = 'processed-alex'
                            """, Integer.class, intent.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.reevaluation_request
                            WHERE subject_type = 'crm.Contact' AND subject_id = 'processed-alex'
                              AND expected_state_version = 41
                            """, Integer.class));
        });
        assertThat(persisted).containsExactly(1, 1, 0);
    }

    @Test
    void rollsBackEveryCompletionEffectWhenAuditInsertionFails() throws Exception {
        assertThat(kernel.processNext(Instant.EPOCH)).isEmpty();
        var processedAt = Instant.parse("2026-08-15T23:00:00Z");
        while (kernel.processNext(processedAt).isPresent()) {
            // Drain Intent left by other independent acceptance tests.
        }
        var subject = new Subject("crm.Contact", "rollback-alex");
        var snapshot = kernel.evaluate(new ProjectedState("tenant-one", subject, 50, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false")), Instant.parse("2026-08-15T10:00:00Z"));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Must roll back")));

        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = admin.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION public.reject_success_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.to_status = 'SUCCEEDED' THEN
                            RAISE EXCEPTION 'injected completion failure';
                        END IF;
                        RETURN NEW;
                    END $$;
                    CREATE TRIGGER reject_success_audit BEFORE INSERT ON kernel.intent_audit
                    FOR EACH ROW EXECUTE FUNCTION public.reject_success_audit();
                    """);
            try {
                assertThat(kernel.processNext(processedAt)).hasValueSatisfying(failed ->
                        assertThat(failed.status()).isEqualTo(IntentStatus.FAILED));
            } finally {
                statement.execute("""
                        DROP TRIGGER reject_success_audit ON kernel.intent_audit;
                        DROP FUNCTION public.reject_success_audit();
                        """);
            }
        }

        var persisted = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id = ?", Integer.class,
                            intent.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.projected_state_version
                            WHERE subject_type = 'crm.Contact' AND subject_id = 'rollback-alex' AND version = 51
                            """, Integer.class),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.reevaluation_request
                            WHERE subject_type = 'crm.Contact' AND subject_id = 'rollback-alex'
                            """, Integer.class),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent WHERE id = ? AND status = 'FAILED'",
                            Integer.class, intent.id()),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent_audit WHERE intent_id = ?",
                            Integer.class, intent.id()));
        });
        assertThat(persisted).containsExactly(0, 0, 0, 1, 3);
    }

    @Test
    void rejectsStaleInapplicableAndReauthorisationDeniedIntentWithoutEvents() throws Exception {
        while (kernel.processNext(Instant.parse("2026-08-15T23:00:00Z")).isPresent()) {
            // Drain Intent left by other independent acceptance tests.
        }

        var stale = acceptedRecordInteraction(
                "stale-execution", 60, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        kernel.evaluate(new ProjectedState("tenant-one", new Subject("crm.Contact", "stale-execution"), 61, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:01:00Z"));
        var staleResult = processUntil(stale.id(), Instant.parse("2026-08-15T23:01:00Z"));
        assertThat(staleResult.status()).isEqualTo(IntentStatus.STALE);
        assertThat(staleResult.failureReason()).contains(IntentFailureReason.STATE_OR_SEMANTIC_STALE);

        var stalePack = acceptedRecordInteraction(
                "stale-pack", 70, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        changeCurrentSemanticPack();
        var stalePackResult = processUntil(stalePack.id(), Instant.parse("2026-08-15T23:02:00Z"));
        assertThat(stalePackResult.status()).isEqualTo(IntentStatus.STALE);
        assertThat(stalePackResult.failureReason()).contains(IntentFailureReason.STATE_OR_SEMANTIC_STALE);
        restoreCurrentSemanticPack();

        var inapplicable = acceptedRecordInteraction(
                "time-revoked", 80, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z",
                Map.of("followUpExpiresAt", "2027-01-01T00:00:00Z"));
        var inapplicableResult = processUntil(inapplicable.id(), Instant.parse("2028-01-01T00:00:00Z"));
        assertThat(inapplicableResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(inapplicableResult.failureReason()).contains(IntentFailureReason.NOT_APPLICABLE);

        var denied = acceptedRecordInteraction(
                "authorisation-revoked", 90, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        revokeCurrentAuthorisation();
        var deniedResult = processUntil(denied.id(), Instant.parse("2026-08-15T23:02:00Z"));
        assertThat(deniedResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(deniedResult.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);
        assertThat(kernel.accept(denied.actionOfferId(), requestKey("authorisation-revoked"), interactionPayload())
                .failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);

        var evidence = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id IN (?, ?, ?, ?)",
                            Integer.class, stale.id(), stalePack.id(), inapplicable.id(), denied.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.intent_audit
                            WHERE intent_id IN (?, ?, ?, ?) AND failure_reason IS NOT NULL
                              AND evidence_state_checksum IS NOT NULL AND semantic_pack_checksum IS NOT NULL
                              AND authorisation_bundle_checksum IS NOT NULL
                              AND authorisation_correlation IS NOT NULL
                            """, Integer.class, stale.id(), stalePack.id(), inapplicable.id(), denied.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.intent_audit
                            WHERE intent_id IN (?, ?) AND applicability_result IS NOT NULL
                              AND authorisation_allowed IS NOT NULL
                            """, Integer.class, inapplicable.id(), denied.id()));
        });
        assertThat(evidence).containsExactly(0, 4, 2);
    }

    @Test
    void retriesTransientWorkFailsDeterministicWorkAndLinksHumanRecovery() {
        Instant base = Instant.now().plusSeconds(10);
        while (kernel.processNext(base).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }

        var transientIntent = acceptedRecordInteraction(
                "transient-recovery", 100, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        failRecordInteractionTransiently();
        var waiting = processUntil(transientIntent.id(), base);
        assertThat(waiting.status()).isEqualTo(IntentStatus.RETRY_WAIT);
        assertThatThrownBy(() -> kernel.accept(
                transientIntent.actionOfferId(), UUID.randomUUID(), new CandidatePayload(
                        "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Invalid retry"),
                        Optional.empty(), Optional.of(transientIntent.id()))))
                .isInstanceOf(IntentRejectedException.class);
        assertThat(kernel.processNext(base.plusSeconds(49))).isEmpty();
        restoreRecordInteractionHandler();
        assertThat(processUntil(transientIntent.id(), base.plusSeconds(60)).status())
                .isEqualTo(IntentStatus.SUCCEEDED);

        var deterministic = acceptedRecordInteraction(
                "human-recovery", 110, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        failRecordInteractionDeterministically();
        var failed = processUntil(deterministic.id(), base.plusSeconds(120));
        assertThat(failed.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(failed.failureReason()).contains(IntentFailureReason.DETERMINISTIC_FAILURE);
        restoreRecordInteractionHandler();

        var exhausted = acceptedRecordInteraction(
                "exhausted-recovery", 120, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        failRecordInteractionTransiently();
        assertThat(processUntil(exhausted.id(), base.plusSeconds(180)).status())
                .isEqualTo(IntentStatus.RETRY_WAIT);
        assertThat(processUntil(exhausted.id(), base.plusSeconds(240)).status())
                .isEqualTo(IntentStatus.RETRY_WAIT);
        var exhaustedResult = processUntil(exhausted.id(), base.plusSeconds(300));
        assertThat(exhaustedResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(exhaustedResult.failureReason()).contains(IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED);
        restoreRecordInteractionHandler();

        var retrySnapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", "human-recovery"), 110,
                Map.of("followUpDueAt", "2026-08-15T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:05:00Z"));
        var retryOffer = kernel.authorise(
                        "tenant-one", retrySnapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:06:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        var retryId = UUID.randomUUID();
        var linkedPayload = new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Try again"),
                Optional.empty(), Optional.of(deterministic.id()));
        var retry = kernel.accept(retryOffer.id(), retryId, linkedPayload);
        assertThat(processUntil(retry.id(), base.plusSeconds(360)).status()).isEqualTo(IntentStatus.SUCCEEDED);

        var failedQuery = new IntentQuery(
                "tenant-one", Optional.of(IntentStatus.FAILED),
                Optional.of(new Subject("crm.Contact", "exhausted-recovery")),
                Optional.of(exhausted.id()), Optional.of(base.plusSeconds(1)));
        assertThat(kernel.findIntents(failedQuery)).singleElement().satisfies(view -> {
            assertThat(view.attemptCount()).isEqualTo(3);
            assertThat(view.failureReason()).contains(IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED);
        });
        assertThat(kernel.findIntentAudit(failedQuery)).extracting(entry -> entry.toStatus())
                .containsExactly(
                        IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT,
                        IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT, IntentStatus.CLAIMED, IntentStatus.FAILED);
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.of(new Subject("crm.Contact", "human-recovery")),
                Optional.of(retryId), Optional.empty())))
                .singleElement().satisfies(view -> assertThat(view.priorIntentId()).contains(deterministic.id()));
    }

    @Test
    void keepsLiveLeaseExclusiveAndReclaimsItAfterAClaimedWorkerCrashes() throws Exception {
        Instant processedAt = Instant.parse("2030-01-01T00:00:00Z");
        while (kernel.processNext(processedAt).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }
        var intent = acceptedRecordInteraction(
                "crashed-worker", 130, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        UUID abandonedToken = UUID.randomUUID();
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var claim = admin.prepareStatement("SELECT intent_id FROM kernel.claim_due_intent(?, ?, ?, ?, ?, ?)");
                var expire = admin.prepareStatement("UPDATE kernel.intent SET lease_until = ? WHERE id = ?")) {
            Instant claimedAt = Instant.now();
            claim.setObject(1, abandonedToken);
            claim.setTimestamp(2, java.sql.Timestamp.from(processedAt));
            claim.setTimestamp(3, java.sql.Timestamp.from(claimedAt));
            claim.setTimestamp(4, java.sql.Timestamp.from(claimedAt.plusSeconds(60)));
            claim.setObject(5, UUID.randomUUID());
            claim.setObject(6, UUID.randomUUID());
            assertThat(claim.executeQuery()).satisfies(result -> assertThat(result.next()).isTrue());

            assertThat(kernel.processNext(processedAt)).isEmpty();
            expire.setTimestamp(1, java.sql.Timestamp.from(claimedAt.minusSeconds(1)));
            expire.setObject(2, intent.id());
            assertThat(expire.executeUpdate()).isEqualTo(1);
        }

        assertThat(processUntil(intent.id(), processedAt).status()).isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(kernel.processNext(processedAt)).isEmpty();
        var query = new IntentQuery("tenant-one", Optional.empty(), Optional.empty(),
                Optional.of(intent.id()), Optional.empty());
        assertThat(kernel.findIntentAudit(query)).extracting(entry -> entry.toStatus())
                .containsExactly(IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.CLAIMED,
                        IntentStatus.SUCCEEDED);
    }

    @Test
    void recoversHandlerCrashAndDoesNotRepeatCommittedWorkWhenAcknowledgementIsLost() throws Exception {
        Instant processedAt = Instant.parse("2030-02-01T00:00:00Z");
        while (kernel.processNext(processedAt).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }
        var duringHandling = acceptedRecordInteraction(
                "handler-crash", 135, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        crashRecordInteractionHandler();
        assertThatThrownBy(() -> kernel.processNext(processedAt)).isInstanceOf(AssertionError.class);
        restoreRecordInteractionHandler();
        expireLease(duringHandling.id());
        assertThat(processUntil(duringHandling.id(), processedAt).status()).isEqualTo(IntentStatus.SUCCEEDED);

        var repeatedlyCrashed = acceptedRecordInteraction(
                "repeated-handler-crash", 137, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        crashRecordInteractionHandler();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> kernel.processNext(processedAt)).isInstanceOf(AssertionError.class);
            expireLease(repeatedlyCrashed.id());
        }
        restoreRecordInteractionHandler();
        assertThat(processUntil(repeatedlyCrashed.id(), processedAt)).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(IntentStatus.FAILED);
            assertThat(failed.failureReason()).contains(IntentFailureReason.TRANSIENT_ATTEMPTS_EXHAUSTED);
        });

        var afterCommit = acceptedRecordInteraction(
                "lost-acknowledgement", 136, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        processUntil(afterCommit.id(), processedAt); // Simulate the caller losing the successful acknowledgement.
        assertThat(kernel.processNext(processedAt)).isEmpty();
        var committed = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id = ?", Integer.class,
                            duringHandling.id()),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id = ?", Integer.class,
                            afterCommit.id()));
        });
        assertThat(committed).containsExactly(1, 1);
    }

    @Test
    void isolatesOneFailedIntentFromUnrelatedDueWorkInTheSameBatch() {
        Instant processedAt = Instant.parse("2031-01-01T00:00:00Z");
        while (kernel.processNext(processedAt).isPresent()) {
            // Drain Intent left by independent acceptance tests.
        }
        var failedIntent = acceptedRecordInteraction(
                "isolated-failure", 140, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        var successfulSnapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", "isolated-success"), 150,
                Map.of("followUpDueAt", "2026-08-15T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:00:00Z"));
        var snoozeOffer = kernel.authorise(
                        "tenant-one", successfulSnapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("snoozeFollowUp"))
                .findFirst().orElseThrow();
        var successfulIntent = kernel.accept(snoozeOffer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.SnoozeFollowUpInput", 1,
                Map.of("until", "2032-01-01T00:00:00Z")));

        failRecordInteractionDeterministically();
        assertThat(kernel.processDue(processedAt)).extracting(result -> result.status())
                .containsExactlyInAnyOrder(IntentStatus.FAILED, IntentStatus.SUCCEEDED);
        restoreRecordInteractionHandler();
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.empty(), Optional.of(failedIntent.id()), Optional.empty())))
                .singleElement().satisfies(view -> assertThat(view.status()).isEqualTo(IntentStatus.FAILED));
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.empty(), Optional.of(successfulIntent.id()), Optional.empty())))
                .singleElement().satisfies(view -> assertThat(view.status()).isEqualTo(IntentStatus.SUCCEEDED));
    }

    private io.github.gmcnicol.kernel.application.Intent acceptedRecordInteraction(
            String subjectId, long version, String dueAt, String evaluatedAt) {
        return acceptedRecordInteraction(subjectId, version, dueAt, evaluatedAt, Map.of());
    }

    private io.github.gmcnicol.kernel.application.Intent acceptedRecordInteraction(
            String subjectId, long version, String dueAt, String evaluatedAt, Map<String, String> extraState) {
        var state = new java.util.HashMap<>(extraState);
        state.put("followUpDueAt", dueAt);
        state.put("followUpCompleted", "false");
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", subjectId), version,
                state), Instant.parse(evaluatedAt));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T14:00:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        return kernel.accept(offer.id(), requestKey(subjectId), interactionPayload());
    }

    private static UUID requestKey(String subjectId) {
        return UUID.nameUUIDFromBytes(subjectId.getBytes(StandardCharsets.UTF_8));
    }

    private static CandidatePayload interactionPayload() {
        return new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Safety check"));
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

    private void expireLease(UUID intentId) throws Exception {
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = admin.prepareStatement("UPDATE kernel.intent SET lease_until = ? WHERE id = ?")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.EPOCH));
            statement.setObject(2, intentId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
