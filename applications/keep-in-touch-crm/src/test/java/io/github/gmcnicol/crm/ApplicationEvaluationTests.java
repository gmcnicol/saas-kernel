package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.IntentConflictException;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentAuditQuery;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentRejectedException;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import io.github.gmcnicol.kernel.application.TypedStateTransition;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.application.TypedTransitionProvenance;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpDue;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.InteractionRecordedEventV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.crm.bindings.GeneratedSemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(ApplicationEvaluationTests.TypedEvaluationConfiguration.class)
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
        properties.add("spring.security.user.password", () -> "test-password");
    }

    @Autowired Kernel kernel;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meters;
    @Autowired ObservationRegistry observations;
    @Autowired ApplicationAvailability availability;
    @Autowired CrmA2uiAdapter a2ui;
    @Autowired CrmContactQueries contacts;
    @Autowired List<SemanticVersionAdapter> semanticAdapters;
    @Autowired @Qualifier("typedProjectionSeen") AtomicReference<FollowUpProjection> typedProjectionSeen;
    @Autowired @Qualifier("typedPolicyProjectionSeen") AtomicReference<FollowUpProjection> typedPolicyProjectionSeen;
    @Autowired @Qualifier("crmDesktopPresentationPack") PresentationPack desktopPresentation;
    @Autowired @Qualifier("crmMobilePresentationPack") PresentationPack mobilePresentation;
    @Autowired @Qualifier("typedCrmDesktopPresentationPack")
    TypedPresentationPack<ContactId, FollowUpProjection> typedDesktopPresentation;
    @Autowired @Qualifier("typedCrmMobilePresentationPack")
    TypedPresentationPack<ContactId, FollowUpProjection> typedMobilePresentation;

    @MockitoSpyBean private AuthorisationModel authorisationModel;
    @MockitoSpyBean private SemanticPackVersion semanticPack;
    @MockitoSpyBean("recordInteractionHandler") private IntentHandler recordInteractionHandler;
    @MockitoSpyBean("followUpDueDerivation") private FactDerivation followUpDueDerivation;
    @MockitoSpyBean("typedRecordInteractionHandler")
    private TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, InteractionRecordedEventV1>
            typedHandler;
    @MockitoSpyBean("typedInteractionRecordedProjector")
    private TypedEventProjector<FollowUpProjection, InteractionRecordedEventV1> typedProjector;
    @MockitoSpyBean private Clock clock;

    private void changeCurrentSemanticPack() {
        org.mockito.Mockito.doReturn("0".repeat(64)).when(semanticPack).checksum();
    }

    private void restoreCurrentSemanticPack() {
        org.mockito.Mockito.reset(semanticPack);
    }

    private void revokeCurrentAuthorisation() {
        org.mockito.Mockito.doReturn("RevokedContact").when(authorisationModel).resourceType();
    }

    private void failRecordInteractionTransiently() {
        org.mockito.Mockito.doThrow(new RetryableIntentException("temporary outage"))
                .when(recordInteractionHandler).handle(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private void failRecordInteractionDeterministically() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid Intent transition"))
                .when(recordInteractionHandler).handle(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private void crashRecordInteractionHandler() {
        org.mockito.Mockito.doThrow(new AssertionError("simulated process crash"))
                .when(recordInteractionHandler).handle(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private void restoreRecordInteractionHandler() {
        org.mockito.Mockito.reset(recordInteractionHandler);
    }

    private void expireReevaluationLeaseDuringDerivation(Instant claimedAt) {
        org.mockito.Mockito.doReturn(claimedAt).when(clock).instant();
        org.mockito.Mockito.doAnswer(invocation -> {
            org.mockito.Mockito.doReturn(claimedAt.plusSeconds(31)).when(clock).instant();
            return invocation.callRealMethod();
        }).when(followUpDueDerivation).derive(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void restoreDerivationAt(Instant currentTime) {
        org.mockito.Mockito.reset(followUpDueDerivation, clock);
        org.mockito.Mockito.doReturn(currentTime).when(clock).instant();
    }

    @AfterEach
    void restoreCurrentExecutionBasis() {
        org.mockito.Mockito.reset(
                authorisationModel, semanticPack, recordInteractionHandler, followUpDueDerivation,
                typedHandler, typedProjector, clock);
    }

    @Test
    void evaluatesGeneratedProjectionIntoDurableTypedFact() {
        var dueAt = Instant.parse("2040-08-15T09:00:00Z");
        seedOpenContact("typed-alex", dueAt);
        FollowUpProjection projection = new TransactionTemplate(transactionManager).execute(status -> {
            useTenant();
            return jdbc.queryForObject("""
                    SELECT contact_id, next_contact_due_at, open_follow_up_id IS NULL AS completed
                    FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'typed-alex'
                    """, (result, row) -> new FollowUpProjection(
                            new ContactId(result.getString("contact_id")),
                            result.getTimestamp("next_contact_due_at").toInstant(),
                            result.getBoolean("completed")));
        });
        var state = new TypedProjectedState<>(
                "tenant-one",
                new TypedSubject<>(ContactId.TYPE, projection.contactId()),
                400,
                FollowUpProjection.TYPE,
                projection);

        var snapshot = kernel.evaluate(state, dueAt.plusSeconds(1));

        assertThat(typedProjectionSeen.get()).isSameAs(projection);
        assertThat(typedPolicyProjectionSeen.get()).isSameAs(projection);
        assertThat(snapshot.projectionType()).isSameAs(FollowUpProjection.TYPE);
        assertThat(snapshot.facts().find(FollowUpDue.TYPE))
                .contains(new FollowUpDue(new ContactId("typed-alex")));
        assertThat(snapshot.applicableActions())
                .extracting(action -> action.actionId())
                .containsExactly(TypedCrmActions.RECORD_INTERACTION.qualifiedName());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            useTenant();
            assertThat(jdbc.queryForMap("""
                    SELECT projection_type, contract_version, format_version, content, checksum
                    FROM kernel.typed_projected_state
                    WHERE tenant_id = 'tenant-one' AND subject_id = 'typed-alex'
                    """))
                    .containsEntry("projection_type", "io.github.gmcnicol.crm.FollowUpProjection")
                    .containsEntry("contract_version", 1)
                    .containsEntry("format_version", 1)
                    .containsEntry("content", "{\"contactId\":\"typed-alex\",\"followUpCompleted\":false,\"nextContactDueAt\":\"2040-08-15T09:00:00Z\"}");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM kernel.typed_evaluation_fact
                    WHERE snapshot_id = ? AND fact_type = 'io.github.gmcnicol.crm.FollowUpDue'
                      AND contract_version = 1 AND format_version = 1
                    """, Integer.class, snapshot.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT action_id FROM kernel.typed_evaluation_applicable_action
                    WHERE snapshot_id = ? AND position = 0
                    """, String.class, snapshot.id()))
                    .isEqualTo(TypedCrmActions.RECORD_INTERACTION.qualifiedName());
        });
    }

    @Test
    void acceptsAndExecutesGeneratedActionAtomicallyThroughPublicKernel(CapturedOutput output) {
        Instant dueAt = Instant.parse("2040-08-15T09:00:00Z");
        seedOpenContact("typed-action-alex", dueAt);
        var projection = new FollowUpProjection(new ContactId("typed-action-alex"), dueAt, false);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(ContactId.TYPE, projection.contactId()), 500,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(2)).when(clock).instant();
        var offer = kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"), dueAt.plusSeconds(2))
                .actionOffers().getFirst();

        var trace = new W3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");
        var payload = new TypedCandidatePayload<>(TypedCrmActions.RECORD_INTERACTION,
                new RecordInteractionCandidateV1("Spoke typed"), Optional.of(trace), Optional.empty());
        long attemptsBefore = meters.find("kernel.intent.attempt").timer() == null
                ? 0 : meters.find("kernel.intent.attempt").timer().count();
        double outcomesBefore = meters.find("kernel.intent.outcomes").tag("outcome", "succeeded").counter() == null
                ? 0 : meters.find("kernel.intent.outcomes").tag("outcome", "succeeded").counter().count();
        var intent = kernel.accept(offer.id(), UUID.randomUUID(), payload);
        assertThatThrownBy(() -> kernel.accept(offer.id(), intent.id(), new TypedCandidatePayload<>(
                        TypedCrmActions.RECORD_INTERACTION, new RecordInteractionCandidateV1("Spoke typed"),
                        Optional.of(new W3cTraceContext(
                                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null)), Optional.empty())))
                .isInstanceOf(IntentConflictException.class);
        var completed = processUntil(intent.id(), dueAt.plusSeconds(30));
        kernel.processNext(dueAt.plusSeconds(31)); // Caller lost the successful acknowledgement and polls again.

        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            useTenant();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM kernel.typed_event WHERE intent_id = ?", Integer.class, intent.id()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForMap("""
                    SELECT traceparent, tracestate FROM kernel.typed_intent WHERE id = ?
                    """, intent.id()))
                    .containsEntry("traceparent", trace.traceparent())
                    .containsEntry("tracestate", trace.tracestate());
            assertThat(jdbc.queryForObject("""
                    SELECT open_follow_up_id IS NULL FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'typed-action-alex'
                    """, Boolean.class)).isTrue();
        });
        assertThat(meters.find("kernel.intent.attempt").timer().count()).isEqualTo(attemptsBefore + 1);
        assertThat(meters.find("kernel.intent.outcomes").tag("outcome", "succeeded").counter().count())
                .isEqualTo(outcomesBefore + 1);
        assertThat(output).contains(
                "\"action_offer\":\"" + offer.id() + "\"",
                "\"intent\":\"" + intent.id() + "\"",
                "\"trace_correlation\":\"4bf92f3577b34da6a3ce929d0e0e4736\"",
                "\"event\":");
    }

    @Test
    void preservesAndValidatesGeneratedIntentLineage() {
        Instant dueAt = Instant.parse("2040-08-15T10:00:00Z");
        var firstOffer = typedOffer("typed-lineage-first", 505, dueAt);
        var first = kernel.accept(firstOffer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("first")));
        var secondOffer = typedOffer("typed-lineage-second", 506, dueAt.plusSeconds(120));
        assertThatThrownBy(() -> kernel.accept(secondOffer.id(), UUID.randomUUID(), new TypedCandidatePayload<>(
                        TypedCrmActions.RECORD_INTERACTION, new RecordInteractionCandidateV1("too early"),
                        Optional.empty(), Optional.of(first.id()))))
                .isInstanceOf(IntentRejectedException.class);

        assertThat(processUntil(first.id(), dueAt.plusSeconds(30)).status()).isEqualTo(IntentStatus.SUCCEEDED);
        var linkedId = UUID.randomUUID();
        var linked = kernel.accept(secondOffer.id(), linkedId, new TypedCandidatePayload<>(
                TypedCrmActions.RECORD_INTERACTION, new RecordInteractionCandidateV1("linked"),
                Optional.empty(), Optional.of(first.id())));

        inTenant(() -> assertThat(jdbc.queryForObject(
                "SELECT prior_intent_id FROM kernel.typed_intent WHERE id = ?", UUID.class, linked.id()))
                .isEqualTo(first.id()));
        assertThat(processUntil(linked.id(), dueAt.plusSeconds(150)).status()).isEqualTo(IntentStatus.SUCCEEDED);
    }

    @Test
    void rejectsCandidateBoundToAnythingExceptStoredGeneratedAction() {
        Instant dueAt = Instant.parse("2040-08-16T09:00:00Z");
        var offer = typedOffer("typed-invalid", 510, dueAt);
        var copiedDescriptor = new io.github.gmcnicol.kernel.application.ActionType<>(
                TypedCrmActions.RECORD_INTERACTION.qualifiedName(),
                TypedCrmActions.RECORD_INTERACTION.projectionType(),
                TypedCrmActions.RECORD_INTERACTION.candidateType(),
                TypedCrmActions.RECORD_INTERACTION.eventTypes());

        assertThatThrownBy(() -> kernel.accept(
                        offer.id(), UUID.randomUUID(), copiedDescriptor.candidate(
                                new RecordInteractionCandidateV1("invalid"))))
                .isInstanceOf(IntentRejectedException.class);
    }

    @Test
    void marksGeneratedActionStaleWithoutRunningHandler() {
        Instant dueAt = Instant.parse("2040-08-17T09:00:00Z");
        long version = 520;
        var offer = typedOffer("typed-stale", version, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("stale")));
        var projection = new FollowUpProjection(new ContactId("typed-stale"), dueAt, false);
        kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(ContactId.TYPE, projection.contactId()), version + 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(3));

        var processed = processUntil(intent.id(), dueAt.plusSeconds(30));

        assertThat(processed.status()).isEqualTo(IntentStatus.STALE);
        org.mockito.Mockito.verify(typedHandler, org.mockito.Mockito.never()).handle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void checksSemanticPackStalenessBeforeDecodingHistoricalPayload() {
        Instant dueAt = Instant.parse("2040-08-17T10:00:00Z");
        var offer = typedOffer("typed-pack-stale", 525, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("stale")));
        var admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("UPDATE kernel.typed_intent SET payload_content = '{corrupt' WHERE id = ?", intent.id());
        changeCurrentSemanticPack();

        assertThat(processUntil(intent.id(), dueAt.plusSeconds(30)).status()).isEqualTo(IntentStatus.STALE);
        org.mockito.Mockito.verify(typedHandler, org.mockito.Mockito.never()).handle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retriesGeneratedActionWithoutDuplicatingEvents() {
        Instant dueAt = Instant.parse("2040-08-18T09:00:00Z");
        var offer = typedOffer("typed-retry", 530, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("retry")));
        org.mockito.Mockito.doThrow(new RetryableIntentException("temporary"))
                .doCallRealMethod().when(typedHandler).handle(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertThat(processUntil(intent.id(), dueAt.plusSeconds(30)).status()).isEqualTo(IntentStatus.RETRY_WAIT);
        assertThat(processUntil(intent.id(), dueAt.plusSeconds(90)).status()).isEqualTo(IntentStatus.SUCCEEDED);
        inTenant(() -> {
            assertThat(jdbc.queryForObject(
                    "SELECT attempt_count FROM kernel.typed_intent WHERE id = ?", Integer.class, intent.id()))
                    .isEqualTo(2);
            assertThat(jdbc.queryForList(
                    "SELECT sequence FROM kernel.typed_event WHERE intent_id = ? ORDER BY sequence",
                    Integer.class, intent.id())).containsExactly(1);
        });
    }

    @Test
    void rollsBackProjectorEvidenceStateAndCompletionTogether() {
        Instant dueAt = Instant.parse("2040-08-19T09:00:00Z");
        long version = 540;
        var offer = typedOffer("typed-rollback", version, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("rollback")));
        org.mockito.Mockito.doThrow(new IllegalStateException("projector failed"))
                .when(typedProjector).project(org.mockito.ArgumentMatchers.any());

        var processed = processUntil(intent.id(), dueAt.plusSeconds(30));

        assertThat(processed.status()).isEqualTo(IntentStatus.FAILED);
        inTenant(() -> {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM kernel.typed_event WHERE intent_id = ?", Integer.class, intent.id()))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT max(state_version) FROM kernel.typed_projected_state
                    WHERE subject_id = 'typed-rollback'
                    """, Long.class)).isEqualTo(version);
            assertThat(jdbc.queryForObject("""
                    SELECT open_follow_up_id IS NOT NULL FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'typed-rollback'
                    """, Boolean.class)).isTrue();
        });
    }

    @Test
    void commitsOrderedGeneratedMultiEventTransitionsWithFullProvenance() {
        Instant dueAt = Instant.parse("2040-08-20T09:00:00Z");
        long version = 550;
        var offer = typedOffer("typed-multi", version, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("multi")));
        var id = new ContactId("typed-multi");
        org.mockito.Mockito.doReturn(List.of(
                new TypedStateTransition<>(
                        new InteractionRecordedEventV1(id), new FollowUpProjection(id, dueAt.plusSeconds(1), false)),
                new TypedStateTransition<>(
                        new InteractionRecordedEventV1(id), new FollowUpProjection(id, dueAt.plusSeconds(2), true))))
                .when(typedHandler).handle(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertThat(processUntil(intent.id(), dueAt.plusSeconds(30)).status()).isEqualTo(IntentStatus.SUCCEEDED);

        @SuppressWarnings("unchecked")
        var provenance = org.mockito.ArgumentCaptor.forClass(
                (Class<TypedTransitionProvenance<FollowUpProjection, InteractionRecordedEventV1>>) (Class<?>)
                        TypedTransitionProvenance.class);
        org.mockito.Mockito.verify(typedProjector, org.mockito.Mockito.times(2)).project(provenance.capture());
        assertThat(provenance.getAllValues()).extracting(TypedTransitionProvenance::sequence)
                .containsExactly(1, 2);
        assertThat(provenance.getAllValues().getFirst().subject().type()).isSameAs(ContactId.TYPE);
        assertThat(provenance.getAllValues().getFirst().subject().id()).isEqualTo(id);
        assertThat(provenance.getAllValues().get(1).previousProjection())
                .isEqualTo(provenance.getAllValues().get(0).resultingProjection());
        inTenant(() -> {
            assertThat(jdbc.queryForList("""
                    SELECT resulting_state_version FROM kernel.typed_event
                    WHERE intent_id = ? ORDER BY sequence
                    """, Long.class, intent.id())).containsExactly(version + 1, version + 2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM kernel.typed_projected_state
                    WHERE subject_id = 'typed-multi' AND state_version IN (?, ?)
                    """, Integer.class, version + 1, version + 2)).isEqualTo(2);
        });
    }

    @Test
    void rollsBackExpiredLeaseThenReclaimsWithoutDuplicateEvents() {
        Instant dueAt = Instant.parse("2040-08-21T09:00:00Z");
        long version = 560;
        var offer = typedOffer("typed-expired-lease", version, dueAt);
        var intent = kernel.accept(
                offer.id(), UUID.randomUUID(),
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("lease")));
        org.mockito.Mockito.doAnswer(invocation -> {
            org.mockito.Mockito.doReturn(dueAt.plusSeconds(63)).when(clock).instant();
            return invocation.callRealMethod();
        }).when(typedHandler).handle(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> kernel.processNext(dueAt.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Typed Intent lease is no longer owned");
        inTenant(() -> {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM kernel.typed_event WHERE intent_id = ?", Integer.class, intent.id()))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT max(state_version) FROM kernel.typed_projected_state
                    WHERE subject_id = 'typed-expired-lease'
                    """, Long.class)).isEqualTo(version);
            assertThat(jdbc.queryForObject("""
                    SELECT open_follow_up_id IS NOT NULL FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'typed-expired-lease'
                    """, Boolean.class)).isTrue();
        });

        org.mockito.Mockito.reset(typedHandler, clock);
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(64)).when(clock).instant();
        assertThat(processUntil(intent.id(), dueAt.plusSeconds(64)).status()).isEqualTo(IntentStatus.SUCCEEDED);
        inTenant(() -> {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM kernel.typed_event WHERE intent_id = ?", Integer.class, intent.id()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT attempt_count FROM kernel.typed_intent WHERE id = ?", Integer.class, intent.id()))
                    .isEqualTo(2);
        });
    }

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
            useTenant();
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
        var correctedSnapshot = java.util.stream.IntStream.range(0, 100)
                .mapToObj(ignored -> kernel.processNextReevaluation(firstDue.plusSeconds(1)))
                .flatMap(Optional::stream)
                .filter(snapshot -> snapshot.subject().equals(subject))
                .findFirst();
        assertThat(correctedSnapshot)
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
            useTenant();
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
            useTenant();
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
            useTenant();
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
    void rendersDistinctAuthorisedCrmExperiencesAndInvokesOpaqueOffer(CapturedOutput output) throws Exception {
        Instant dueAt = Instant.parse("2040-08-15T09:00:00Z");
        String subjectId = "presented-alex";
        seedOpenContact(subjectId, dueAt);
        var id = new ContactId(subjectId);
        var subject = new TypedSubject<>(ContactId.TYPE, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", subject, 20, FollowUpProjection.TYPE,
                new FollowUpProjection(id, dueAt, false)), dueAt.plusSeconds(1));
        var presentedAt = dueAt.plusSeconds(2);
        var envelope = kernel.present(
                "tenant-one", snapshot.id(), new Principal("Owner", "gareth"), presentedAt,
                FollowUpProjection.TYPE);

        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.subject()).isEqualTo(subject);
        assertThat(envelope.evaluationId()).isEqualTo(snapshot.id());
        assertThat(envelope.evaluatedAt()).isEqualTo(dueAt.plusSeconds(1));
        assertThat(envelope.semanticPackId()).isEqualTo("io.github.gmcnicol.crm.semantic");
        assertThat(envelope.fields()).singleElement().satisfies(field -> {
            assertThat(field.type()).isSameAs(FollowUpProjection.CONTACT_ID);
            assertThat(field.value()).isEqualTo(id);
        });
        assertThat(envelope.facts()).singleElement().satisfies(fact ->
                assertThat(fact.type()).isSameAs(FollowUpDue.TYPE));
        assertThat(envelope.actionOffers()).singleElement().satisfies(offer ->
                assertThat(offer.actionType()).isSameAs(TypedCrmActions.RECORD_INTERACTION));

        var desktop = typedDesktopPresentation.render(envelope);
        var mobile = typedMobilePresentation.render(envelope);
        assertThat(desktop.html()).contains("Relationship workspace", subjectId, "data-on:submit", "@post(")
                .doesNotContain(dueAt.toString(), "followUpCompleted");
        assertThat(mobile.html()).contains("Next relationship").doesNotContain("Relationship workspace");
        assertThat(desktop.eventStream()).startsWith("event: datastar-patch-elements\n");
        assertThat(desktop.renderedActionOffers()).containsExactlyInAnyOrderElementsOf(
                envelope.actionOffers().stream().map(offer -> offer.id()).toList());

        var viewerEnvelope = kernel.present(
                "tenant-one", snapshot.id(), new Principal("Viewer", "guest"), presentedAt,
                FollowUpProjection.TYPE);
        assertThat(viewerEnvelope.actionOffers()).isEmpty();
        assertThat(typedMobilePresentation.render(viewerEnvelope).html()).doesNotContain("<form");

        var offer = envelope.actionOffers().getFirst();
        var intentId = UUID.randomUUID();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        org.mockito.Mockito.doReturn(presentedAt.plusSeconds(1)).when(clock).instant();
        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                        .header("tracestate", "vendor=value")
                        .param("intentId", intentId.toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("note", "Spoke through rendered control"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("datastar-patch-elements")));
        inTenant(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kernel.typed_intent WHERE id = ?", Integer.class, intentId)).isEqualTo(1));
        assertThat(processUntil(intentId, presentedAt.plusSeconds(30)).status())
                .isEqualTo(IntentStatus.SUCCEEDED);
        assertThat(meters.find("kernel.evaluation").timer()).isNotNull();
        assertThat(meters.find("kernel.authorisation").timer()).isNotNull();
        assertThat(meters.find("kernel.intent.acceptance").timer()).isNotNull();
        assertThat(meters.find("kernel.intent.attempt").timer()).isNotNull();
        assertThat(meters.find("kernel.intent.handler").timer()).isNotNull();
        assertThat(meters.find("kernel.event.projection.commit").timer()).isNotNull();
        assertThat(meters.find("kernel.presentation.rendering").timer()).isNotNull();
        assertThat(meters.find("kernel.intent.outcomes").tag("outcome", "succeeded").counter().count()).isPositive();
        assertThat(meters.getMeters().stream()
                        .filter(meter -> meter.getId().getName().startsWith("kernel."))
                        .flatMap(meter -> meter.getId().getTags().stream()))
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("error", "outcome", "worker"));
        assertThat(output).contains(
                        "\"tenant\":\"tenant-one\"",
                        "\"evaluation_snapshot\":\"" + snapshot.id() + "\"",
                        "\"action_offer\":\"" + offer.id() + "\"",
                        "\"intent\":\"" + intentId + "\"",
                        "\"trace_correlation\":\"" + traceId + "\"",
                        "\"event\":")
                .doesNotContain(subjectId, "Spoke through rendered control", "never render");
        int acceptedIntentCount = new TransactionTemplate(transactionManager).execute(status -> {
            useTenant();
            return jdbc.queryForObject("SELECT count(*) FROM kernel.typed_intent", Integer.class);
        });

        mvc.perform(post("/presentation/intents/{offerId}", UUID.randomUUID())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", UUID.randomUUID().toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("note", "forged offer"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", UUID.randomUUID().toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", "unknown.Candidate")
                        .param("payloadVersion", "1")
                        .param("note", "unknown type"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", UUID.randomUUID().toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("note", "one", "two"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", UUID.randomUUID().toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("unknown", "field"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", UUID.randomUUID().toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("note", "x".repeat(65_537)))
                .andExpect(status().isBadRequest());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            useTenant();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kernel.typed_intent", Integer.class))
                    .isEqualTo(acceptedIntentCount);
        });

        mvc.perform(get("/presentation/crm/desktop/events")
                        .header("X-Tenant-Id", "tenant-one")
                        .header("X-Principal-Type", "Owner")
                        .header("X-Principal-Id", "gareth")
                        .param("snapshotId", snapshot.id().toString()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/presentation/crm/desktop/events")
                        .with(httpBasic("gareth", "test-password"))
                        .param("snapshotId", snapshot.id().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Relationship workspace")));

        mvc.perform(get("/presentation/crm/desktop")
                        .with(httpBasic("gareth", "test-password"))
                        .param("snapshotId", snapshot.id().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("integrity=\"sha384-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-on:submit")));

        mvc.perform(get("/presentation/crm/mobile")
                        .with(user("guest").roles("TENANT_tenant-one", "PRINCIPAL_Viewer"))
                        .param("snapshotId", snapshot.id().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<form"))));
    }

    @Test
    void rendersBoundedA2uiAndAcceptsOnlyCurrentOffer() throws Exception {
        Instant dueAt = Instant.parse("2040-08-16T09:00:00Z");
        seedOpenContact("a2ui-contact", dueAt);
        var id = new ContactId("a2ui-contact");
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(ContactId.TYPE, id), 300, FollowUpProjection.TYPE,
                new FollowUpProjection(id, dueAt, false)), dueAt.plusSeconds(1));
        var envelope = kernel.present(
                "tenant-one", snapshot.id(), new Principal("Owner", "gareth"),
                dueAt.plusSeconds(2), FollowUpProjection.TYPE);
        var offer = envelope.actionOffers().getFirst();
        String source = a2uiMessages(offer.id());
        var rendered = a2ui.render(envelope, source);
        var nativeHtml = typedDesktopPresentation.render(envelope).html();

        assertThat(rendered.html()).contains(
                "Ada &lt;Lovelace&gt;", "/presentation/intents/" + offer.id(),
                "name=\"payloadType\" value=\"" + RecordInteractionCandidateV1.TYPE.qualifiedName() + "\"",
                "name=\"payloadVersion\" value=\"1\"", "name=\"note\" value=\"A2UI contact note\"");
        assertThat(nativeHtml).contains(
                "/presentation/intents/" + offer.id(),
                "name=\"payloadType\" value=\"" + RecordInteractionCandidateV1.TYPE.qualifiedName() + "\"",
                "name=\"payloadVersion\" value=\"1\"");

        UUID intentId = UUID.randomUUID();
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(3)).when(clock).instant();
        mvc.perform(post("/presentation/intents/{offerId}", offer.id())
                        .with(httpBasic("gareth", "test-password"))
                        .contentType("application/x-www-form-urlencoded")
                        .param("intentId", intentId.toString())
                        .param("actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName())
                        .param("payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName())
                        .param("payloadVersion", "1")
                        .param("note", "A2UI contact note"))
                .andExpect(status().isOk());
        inTenant(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kernel.typed_intent WHERE id = ?", Integer.class, intentId)).isEqualTo(1));
        assertThat(processUntil(intentId, dueAt.plusSeconds(30)).status())
                .isEqualTo(IntentStatus.SUCCEEDED);

        assertThatThrownBy(() -> a2ui.render(envelope, a2uiMessages(UUID.randomUUID())))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);

        var staleSubject = new Subject("crm.Contact", "a2ui-stale");
        var staleSnapshot = kernel.evaluate(new ProjectedState("tenant-one", staleSubject, 310, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:00:00Z"));
        var staleOffer = kernel.present(
                        "tenant-one", staleSnapshot.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:01:00Z"))
                .actionOffers().stream().filter(candidate -> candidate.actionId().endsWith("recordInteraction"))
                .findFirst().orElseThrow();
        kernel.evaluate(new ProjectedState("tenant-one", staleSubject, 311, Map.of(
                "followUpDueAt", "2026-08-16T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:02:00Z"));
        UUID staleIntent = UUID.randomUUID();
        assertThatThrownBy(() -> kernel.accept(staleOffer.id(), staleIntent, new CandidatePayload(
                staleOffer.inputType(), 1, Map.of("note", "must not persist"))))
                .isInstanceOf(IntentRejectedException.class);
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.of(staleSubject),
                Optional.of(staleIntent), Optional.empty()))).isEmpty();
    }

    @Test
    void telemetryExporterFailureDoesNotBlockWorkOrReadiness() {
        var failing = new AtomicBoolean(true);
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStart(Observation.Context context) {
                if (failing.get() && context.getName().startsWith("kernel.")) {
                    throw new IllegalStateException("exporter offline");
                }
            }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        try {
            assertThat(kernel.evaluate(new ProjectedState(
                    "tenant-one", new Subject("crm.Contact", "telemetry-failure"), 1, Map.of(
                            "followUpDueAt", "2026-08-15T09:00:00Z")),
                    Instant.parse("2026-08-15T10:00:00Z"))).isNotNull();
        } finally {
            failing.set(false);
        }
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
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
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
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
                payload.type(), 3, payload.values())))
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
            useTenant();
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
        Instant processedAt = Instant.now().plusSeconds(30);
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
        seedOpenContact(subject.id(), Instant.parse("2026-08-15T09:00:00Z"));
        assertThat(contacts.dueBy(
                "tenant-one", Instant.parse("2026-08-15T23:00:00Z"), Optional.empty(), 10))
                .extracting(CrmContactQueries.ContactDue::contactId).contains(subject.id());

        var completed = processUntil(intent.id(), processedAt);

        assertThat(completed.status()).isEqualTo(IntentStatus.SUCCEEDED);
        var resultingState = new ProjectedState("tenant-one", subject, 41, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "true",
                "lastInteractionNote", "Spoke to Alex",
                "lastInteractionAt", intent.acceptedAt().toString()));
        var reevaluated = kernel.evaluate(resultingState, Instant.parse("2026-08-15T10:03:00Z"));
        assertThat(reevaluated.applicableActions()).isEmpty();
        assertThat(contacts.dueBy(
                "tenant-one", Instant.parse("2026-08-15T23:00:00Z"), Optional.empty(), 10))
                .extracting(CrmContactQueries.ContactDue::contactId).doesNotContain(subject.id());

        var persisted = new TransactionTemplate(transactionManager).execute(status -> {
            useTenant();
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

        var reopened = kernel.evaluate(new ProjectedState("tenant-one", subject, 42, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z",
                "followUpCompleted", "false",
                "lastInteractionAt", intent.acceptedAt().toString())),
                Instant.parse("2026-08-15T10:04:00Z"));
        var snooze = kernel.authorise(
                        "tenant-one", reopened.id(), new Principal("Owner", "gareth"),
                        Instant.parse("2026-08-15T10:05:00Z"))
                .actionOffers().stream().filter(candidate -> candidate.actionId().endsWith("snoozeFollowUp"))
                .findFirst().orElseThrow();
        var snoozeIntent = kernel.accept(snooze.id(), UUID.randomUUID(), new CandidatePayload(
                "io.github.gmcnicol.crm.SnoozeFollowUpInput", 1,
                Map.of("until", "2026-08-20T09:00:00Z")));
        assertThat(processUntil(snoozeIntent.id(), processedAt).status())
                .isEqualTo(IntentStatus.SUCCEEDED);
        Instant projectedInteraction = new TransactionTemplate(transactionManager).execute(status -> {
            useTenant();
            return jdbc.queryForObject("""
                    SELECT last_interaction_at FROM crm_contact_engagement_projection
                    WHERE tenant_id = 'tenant-one' AND contact_id = 'processed-alex'
                    """, (result, row) -> result.getTimestamp(1).toInstant());
        });
        assertThat(projectedInteraction).isEqualTo(intent.acceptedAt());
    }

    @Test
    void rollsBackEveryCompletionEffectWhenAuditInsertionFails(CapturedOutput output) throws Exception {
        assertThat(kernel.processNext(Instant.EPOCH)).isEmpty();
        var processedAt = Instant.now().plusSeconds(30);
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
        seedOpenContact(subject.id(), Instant.parse("2026-08-15T09:00:00Z"));

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
            useTenant();
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
        assertThat(contacts.dueBy(
                "tenant-one", Instant.parse("2026-08-15T23:00:00Z"), Optional.empty(), 10))
                .extracting(CrmContactQueries.ContactDue::contactId).contains(subject.id());
        assertThat(output.getOut().lines().filter(line -> line.contains("\"message\":\"event_committed\"")
                        && line.contains("\"intent\":\"" + intent.id() + "\"")))
                .isEmpty();
    }

    @Test
    void rejectsStaleInapplicableAndReauthorisationDeniedIntentWithoutEvents() throws Exception {
        Instant processedAt = Instant.now().plusSeconds(30);
        while (kernel.processNext(processedAt).isPresent()) {
            // Drain Intent left by other independent acceptance tests.
        }

        var stale = acceptedRecordInteraction(
                "stale-execution", 60, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        kernel.evaluate(new ProjectedState("tenant-one", new Subject("crm.Contact", "stale-execution"), 61, Map.of(
                "followUpDueAt", "2026-08-15T09:00:00Z", "followUpCompleted", "false")),
                Instant.parse("2026-08-15T10:01:00Z"));
        var staleResult = processUntil(stale.id(), processedAt);
        assertThat(staleResult.status()).isEqualTo(IntentStatus.STALE);
        assertThat(staleResult.failureReason()).contains(IntentFailureReason.STATE_OR_SEMANTIC_STALE);

        var stalePack = acceptedRecordInteraction(
                "stale-pack", 70, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        changeCurrentSemanticPack();
        var stalePackResult = processUntil(stalePack.id(), processedAt);
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
        var deniedResult = processUntil(denied.id(), processedAt);
        assertThat(deniedResult.status()).isEqualTo(IntentStatus.FAILED);
        assertThat(deniedResult.failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);
        assertThat(kernel.accept(denied.actionOfferId(), requestKey("authorisation-revoked"), interactionPayload())
                .failureReason()).contains(IntentFailureReason.AUTHORISATION_DENIED);

        var evidence = new TransactionTemplate(transactionManager).execute(status -> {
            useTenant();
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
        assertThat(kernel.findIntentAudit(IntentAuditQuery.firstPage("tenant-one", exhausted.id())))
                .extracting(entry -> entry.toStatus())
                .containsExactly(
                        IntentStatus.PENDING, IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT,
                        IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT, IntentStatus.CLAIMED, IntentStatus.FAILED);
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.of(new Subject("crm.Contact", "human-recovery")),
                Optional.of(retryId), Optional.empty())))
                .singleElement().satisfies(view -> assertThat(view.priorIntentId()).contains(deterministic.id()));

        var firstPage = kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 1));
        assertThat(firstPage).hasSize(1);
        var first = firstPage.getFirst();
        assertThat(kernel.findIntents(new IntentQuery(
                "tenant-one", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new IntentQuery.Cursor(first.acceptedAt(), first.id())), 1)))
                .singleElement().satisfies(next -> assertThat(next.id()).isNotEqualTo(first.id()));
        var firstAudit = kernel.findIntentAudit(new IntentAuditQuery(
                "tenant-one", exhausted.id(), java.util.OptionalInt.empty(), 1));
        assertThat(firstAudit).hasSize(1);
        assertThat(kernel.findIntentAudit(new IntentAuditQuery(
                "tenant-one", exhausted.id(), java.util.OptionalInt.of(firstAudit.getFirst().sequence()), 1)))
                .singleElement().satisfies(next -> assertThat(next.sequence())
                        .isGreaterThan(firstAudit.getFirst().sequence()));
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
        assertThat(kernel.findIntentAudit(IntentAuditQuery.firstPage("tenant-one", intent.id())))
                .extracting(entry -> entry.toStatus())
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
            useTenant();
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

    @Test
    void readsPersistedHistoricalEventAndPayloadVersionsThroughRegisteredAdapters() throws Exception {
        var intent = acceptedRecordInteraction(
                "historical-contract", 160, "2026-08-15T09:00:00Z", "2026-08-15T10:00:00Z");
        assertThat(processUntil(intent.id(), Instant.parse("2031-01-01T00:00:00Z")).status())
                .isEqualTo(IntentStatus.SUCCEEDED);

        String eventType;
        int storedVersion;
        Map<String, String> storedPayload;
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var update = admin.prepareStatement(
                    "UPDATE kernel.event SET payload_version = 1 WHERE intent_id = ?")) {
                update.setObject(1, intent.id());
                update.executeUpdate();
            }
            try (var query = admin.prepareStatement("""
                    SELECT event_record.event_type, event_record.payload_version,
                           payload.name, payload.value
                    FROM kernel.event event_record
                    JOIN kernel.event_payload_value payload ON payload.event_id = event_record.id
                    WHERE event_record.intent_id = ?
                    """)) {
                query.setObject(1, intent.id());
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    eventType = result.getString("event_type");
                    storedVersion = result.getInt("payload_version");
                    storedPayload = Map.of(result.getString("name"), result.getString("value"));
                }
            }
        }

        var adapter = semanticAdapters.stream()
                .filter(candidate -> candidate.contract() == SemanticVersionAdapter.Contract.EVENT)
                .filter(candidate -> candidate.type().equals(eventType))
                .filter(candidate -> candidate.fromVersion() == storedVersion)
                .findFirst().orElseThrow();
        assertThat(adapter.toVersion()).isEqualTo(2);
        assertThat(adapter.adapt(storedPayload)).containsEntry("contactId", "historical-contract");
        try (var admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var query = admin.prepareStatement(
                     "SELECT payload_version FROM kernel.event WHERE intent_id = ?")) {
            query.setObject(1, intent.id());
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static String a2uiMessages(UUID offerId) {
        return """
                [
                  {"version":"v0.9.1","createSurface":{"surfaceId":"contact","catalogId":"%s"}},
                  {"version":"v0.9.1","updateComponents":{"surfaceId":"contact","components":[
                    {"id":"root","component":"Column","children":["title","submit"]},
                    {"id":"title","component":"Text","text":{"path":"/title"}},
                    {"id":"label","component":"Text","text":"Record contact"},
                    {"id":"submit","component":"Button","child":"label","action":{"event":{
                      "name":"invokeActionOffer","context":{"actionOfferId":"%s",
                        "note":{"path":"/note"}
                      }
                    }}}
                  ]}},
                  {"version":"v0.9.1","updateDataModel":{"surfaceId":"contact","path":"/","value":{
                    "title":"Ada <Lovelace>","note":"A2UI contact note"
                  }}}
                ]
                """.formatted(CrmA2uiAdapter.CATALOGUE, offerId);
    }

    private io.github.gmcnicol.kernel.application.Intent acceptedRecordInteraction(
            String subjectId, long version, String dueAt, String evaluatedAt) {
        return acceptedRecordInteraction(subjectId, version, dueAt, evaluatedAt, Map.of());
    }

    private io.github.gmcnicol.kernel.application.ActionOffer typedOffer(
            String subjectId, long version, Instant dueAt) {
        seedOpenContact(subjectId, dueAt);
        var id = new ContactId(subjectId);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(
                "tenant-one", new TypedSubject<>(ContactId.TYPE, id), version,
                FollowUpProjection.TYPE, new FollowUpProjection(id, dueAt, false)), dueAt.plusSeconds(1));
        org.mockito.Mockito.doReturn(dueAt.plusSeconds(2)).when(clock).instant();
        return kernel.authorise(
                        "tenant-one", snapshot.id(), new Principal("Owner", "gareth"), dueAt.plusSeconds(2))
                .actionOffers().stream()
                .filter(offer -> offer.actionId().equals(TypedCrmActions.RECORD_INTERACTION.qualifiedName()))
                .findFirst().orElseThrow();
    }

    private void inTenant(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            useTenant();
            work.run();
        });
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

    private void seedOpenContact(String contactId, Instant dueAt) {
        var admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES ('tenant-one', ?, ?, ?, ?)
                ON CONFLICT (tenant_id, contact_id) DO UPDATE SET
                    next_contact_due_at = EXCLUDED.next_contact_due_at,
                    open_follow_up_id = EXCLUDED.open_follow_up_id
                """, contactId, contactId, java.sql.Timestamp.from(dueAt), UUID.randomUUID());
    }

    private void useTenant() {
        jdbc.execute("SET LOCAL ROLE kernel_runtime");
        jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-one");
    }

    @TestConfiguration
    static class TypedEvaluationConfiguration {
        @Bean
        AtomicReference<FollowUpProjection> typedProjectionSeen() {
            return new AtomicReference<>();
        }

        @Bean
        AtomicReference<FollowUpProjection> typedPolicyProjectionSeen() {
            return new AtomicReference<>();
        }

        @Bean
        SemanticBindings typedBindings() {
            return GeneratedSemanticBindings.INSTANCE;
        }

        @Bean
        TypedFactDerivation<FollowUpProjection, FollowUpDue> typedFollowUpDueDerivation(
                @Qualifier("typedProjectionSeen") AtomicReference<FollowUpProjection> seen) {
            return FollowUpDue.DERIVATION.bind((projection, evaluatedAt) -> {
                seen.set(projection);
                return projection.followUpCompleted() || evaluatedAt.isBefore(projection.nextContactDueAt())
                        ? TypedFactDerivation.Result.none()
                        : TypedFactDerivation.Result.fact(new FollowUpDue(projection.contactId()));
            });
        }

        @Bean
        TypedApplicabilityPolicy<FollowUpProjection> typedRecordInteractionApplicability(
                @Qualifier("typedPolicyProjectionSeen")
                AtomicReference<FollowUpProjection> typedPolicyProjectionSeen) {
            return TypedCrmActions.RECORD_INTERACTION.bindApplicability((projection, facts) -> {
                    typedPolicyProjectionSeen.set(projection);
                    return facts.find(FollowUpDue.TYPE).isPresent();
                });
        }

        @Bean
        TypedIntentHandler<FollowUpProjection, RecordInteractionCandidateV1, InteractionRecordedEventV1>
                typedRecordInteractionHandler() {
            return TypedCrmActions.RECORD_INTERACTION.bindHandler((intent, payload, projection) -> List.of(
                    new io.github.gmcnicol.kernel.application.TypedStateTransition<>(
                            new InteractionRecordedEventV1(projection.contactId()),
                            new FollowUpProjection(
                                    projection.contactId(), projection.nextContactDueAt(), true))));
        }

        @Bean
        TypedEventProjector<FollowUpProjection, InteractionRecordedEventV1> typedInteractionRecordedProjector(
                JdbcTemplate jdbc) {
            return TypedCrmActions.RECORD_INTERACTION.bindProjector(
                    InteractionRecordedEventV1.TYPE, transition -> jdbc.update("""
                    UPDATE crm_contact_engagement_projection SET open_follow_up_id = NULL
                    WHERE tenant_id = ? AND contact_id = ?
                    """, transition.tenantId(), transition.event().contactId().value()));
        }
    }
}
