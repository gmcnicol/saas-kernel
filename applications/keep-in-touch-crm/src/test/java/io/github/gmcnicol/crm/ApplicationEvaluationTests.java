package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import java.sql.DriverManager;
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
        var selfLinkedIntentId = UUID.randomUUID();
        assertThatThrownBy(() -> kernel.accept(offer.id(), selfLinkedIntentId, new CandidatePayload(
                payload.type(), payload.version(), payload.values(), Optional.empty(), Optional.of(selfLinkedIntentId))))
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
        assertThat(persisted).containsExactly(1, 1, 1);
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
                assertThatThrownBy(() -> kernel.processNext(processedAt))
                        .hasMessageContaining("injected completion failure");
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
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent WHERE id = ? AND status = 'CLAIMED'",
                            Integer.class, intent.id()),
                    jdbc.queryForObject("SELECT count(*) FROM kernel.intent_audit WHERE intent_id = ?",
                            Integer.class, intent.id()));
        });
        assertThat(persisted).containsExactly(0, 0, 0, 1, 2);
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
        var inapplicable = acceptedRecordInteraction(
                "policy-revoked", 80, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");

        var denied = acceptedRecordInteraction(
                "authorisation-revoked", 90, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var stalePackStatement = admin.prepareStatement("""
                        UPDATE kernel.intent SET semantic_pack_checksum = repeat('0', 64) WHERE id = ?
                        """);
                var policyStatement = admin.prepareStatement("""
                        UPDATE kernel.intent SET applicability_policy_id = 'removed.policy' WHERE id = ?
                        """);
                var deniedStatement = admin.prepareStatement("""
                        UPDATE kernel.intent SET principal_type = 'Viewer' WHERE id = ?
                        """)) {
            stalePackStatement.setObject(1, stalePack.id());
            stalePackStatement.executeUpdate();
            policyStatement.setObject(1, inapplicable.id());
            policyStatement.executeUpdate();
            deniedStatement.setObject(1, denied.id());
            deniedStatement.executeUpdate();
        }

        var stalePackResult = processUntil(stalePack.id(), Instant.parse("2026-08-15T23:02:00Z"));
        assertThat(stalePackResult.status()).isEqualTo(IntentStatus.STALE);
        assertThat(stalePackResult.failureReason()).contains(IntentFailureReason.STATE_OR_SEMANTIC_STALE);
        var inapplicableResult = processUntil(inapplicable.id(), Instant.parse("2026-08-15T23:03:00Z"));
        assertThat(inapplicableResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(inapplicableResult.failureReason()).contains(IntentFailureReason.NOT_APPLICABLE);
        var deniedResult = processUntil(denied.id(), Instant.parse("2026-08-15T23:02:00Z"));
        assertThat(deniedResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(deniedResult.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);

        var evidence = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
            return java.util.List.of(
                    jdbc.queryForObject("SELECT count(*) FROM kernel.event WHERE intent_id IN (?, ?, ?, ?)",
                            Integer.class, stale.id(), stalePack.id(), inapplicable.id(), denied.id()),
                    jdbc.queryForObject("""
                            SELECT count(*) FROM kernel.intent_audit
                            WHERE intent_id IN (?, ?, ?, ?) AND failure_reason IS NOT NULL
                              AND evidence_state_checksum IS NOT NULL AND semantic_pack_checksum IS NOT NULL
                              AND applicability_result IS NOT NULL AND authorisation_bundle_checksum IS NOT NULL
                              AND authorisation_allowed IS NOT NULL
                            """, Integer.class, stale.id(), stalePack.id(), inapplicable.id(), denied.id()));
        });
        assertThat(evidence).containsExactly(0, 4);
    }

    private io.github.gmcnicol.kernel.application.Intent acceptedRecordInteraction(
            String subjectId, long version, String dueAt, String evaluatedAt) {
        var snapshot = kernel.evaluate(new ProjectedState(
                "tenant-one", new Subject("crm.Contact", subjectId), version,
                Map.of("followUpDueAt", dueAt, "followUpCompleted", "false")), Instant.parse(evaluatedAt));
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T14:00:00Z"))
                .actionOffers().stream()
                .filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        return kernel.accept(offer.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.RecordInteractionInput", 1, Map.of("note", "Safety check")));
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
