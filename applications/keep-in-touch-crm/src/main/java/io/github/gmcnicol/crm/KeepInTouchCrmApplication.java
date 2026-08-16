package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.EventProjector;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication
public class KeepInTouchCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeepInTouchCrmApplication.class, args);
    }

    @Bean
    SemanticPack crmSemanticPack() {
        return () -> "semantic-pack/manifest.properties";
    }

    @Bean
    AuthorisationBundle crmAuthorisationBundle() {
        return () -> "authorisation/manifest.properties";
    }

    @Bean
    AuthorisationModel crmAuthorisationModel() {
        return new AuthorisationModel() {
            @Override public String subjectType() { return "crm.Contact"; }
            @Override public Set<String> subjectTypes() {
                return Set.of(subjectType(), "io.github.gmcnicol.crm.ContactId");
            }
            @Override public String resourceType() { return "Contact"; }
            @Override public Map<String, String> fields() {
                return Map.of("io.github.gmcnicol.crm.Contact.displayName", "displayName");
            }
        };
    }

    @Bean
    FactDerivation followUpDueDerivation() {
        return FactDerivation.of(
                "io.github.gmcnicol.crm.FollowUpDue",
                "io.github.gmcnicol.crm.deriveFollowUpDue",
                (state, evaluatedAt) -> {
                    if (Boolean.parseBoolean(state.values().getOrDefault("followUpCompleted", "false"))) {
                        return FactDerivation.Derivation.none();
                    }
                    String expiresAt = state.values().get("followUpExpiresAt");
                    if (expiresAt != null && !evaluatedAt.isBefore(Instant.parse(expiresAt))) {
                        return FactDerivation.Derivation.none();
                    }
                    Instant dueAt = Instant.parse(state.values().get("followUpDueAt"));
                    return evaluatedAt.isBefore(dueAt)
                            ? FactDerivation.Derivation.later(dueAt)
                            : FactDerivation.Derivation.fact(Map.of(
                                    "contactId", state.subject().id()));
                });
    }

    @Bean
    ApplicabilityPolicy recordInteractionApplicability() {
        return followUpPolicy("io.github.gmcnicol.crm.CrmActions.recordInteraction");
    }

    @Bean
    ApplicabilityPolicy snoozeFollowUpApplicability() {
        return followUpPolicy("io.github.gmcnicol.crm.CrmActions.snoozeFollowUp");
    }

    @Bean
    ApplicabilityPolicy completeFollowUpApplicability() {
        return followUpPolicy("io.github.gmcnicol.crm.CrmActions.completeFollowUp");
    }

    @Bean
    IntentHandler recordInteractionHandler() {
        return IntentHandler.of("io.github.gmcnicol.crm.CrmActions.recordInteraction", (intent, payload, state) -> {
            var resultingState = new HashMap<>(state.values());
            resultingState.put("followUpCompleted", "true");
            resultingState.put("lastInteractionNote", payload.values().get("note"));
            resultingState.put("lastInteractionAt", intent.acceptedAt().toString());
            return List.of(new Event("io.github.gmcnicol.crm.InteractionRecorded", 1,
                    Map.of("contactId", state.subject().id()), resultingState));
        });
    }

    @Bean
    IntentHandler snoozeFollowUpHandler() {
        return IntentHandler.of("io.github.gmcnicol.crm.CrmActions.snoozeFollowUp", (intent, payload, state) -> {
            var resultingState = new HashMap<>(state.values());
            resultingState.put("followUpDueAt", payload.values().get("until"));
            return List.of(new Event("io.github.gmcnicol.crm.FollowUpSnoozed", 1,
                    Map.of("contactId", state.subject().id()), resultingState));
        });
    }

    @Bean
    IntentHandler completeFollowUpHandler() {
        return IntentHandler.of("io.github.gmcnicol.crm.CrmActions.completeFollowUp", (intent, payload, state) -> {
            var resultingState = new HashMap<>(state.values());
            resultingState.put("followUpCompleted", "true");
            return List.of(new Event("io.github.gmcnicol.crm.FollowUpCompleted", 1,
                    Map.of("contactId", state.subject().id()), resultingState));
        });
    }

    @Bean
    SemanticVersionAdapter recordInteractionPayloadV1() {
        return adapter(SemanticVersionAdapter.Contract.PAYLOAD,
                "io.github.gmcnicol.crm.RecordInteractionInput");
    }

    @Bean
    SemanticVersionAdapter snoozePayloadV1() {
        return adapter(SemanticVersionAdapter.Contract.PAYLOAD,
                "io.github.gmcnicol.crm.SnoozeFollowUpInput");
    }

    @Bean
    SemanticVersionAdapter completePayloadV1() {
        return adapter(SemanticVersionAdapter.Contract.PAYLOAD,
                "io.github.gmcnicol.crm.CompleteFollowUpInput");
    }

    @Bean
    SemanticVersionAdapter interactionRecordedV1() {
        return adapter(SemanticVersionAdapter.Contract.EVENT,
                "io.github.gmcnicol.crm.InteractionRecorded");
    }

    @Bean
    SemanticVersionAdapter followUpSnoozedV1() {
        return adapter(SemanticVersionAdapter.Contract.EVENT,
                "io.github.gmcnicol.crm.FollowUpSnoozed");
    }

    @Bean
    SemanticVersionAdapter followUpCompletedV1() {
        return adapter(SemanticVersionAdapter.Contract.EVENT,
                "io.github.gmcnicol.crm.FollowUpCompleted");
    }

    @Bean
    EventProjector interactionRecordedProjector(JdbcTemplate jdbc) {
        return EventProjector.of("io.github.gmcnicol.crm.InteractionRecorded",
                (state, event) -> projectContact(jdbc, state, event.resultingState(), false));
    }

    @Bean
    EventProjector followUpSnoozedProjector(JdbcTemplate jdbc) {
        return EventProjector.of("io.github.gmcnicol.crm.FollowUpSnoozed",
                (state, event) -> projectContact(jdbc, state, event.resultingState(), true));
    }

    @Bean
    EventProjector followUpCompletedProjector(JdbcTemplate jdbc) {
        return EventProjector.of("io.github.gmcnicol.crm.FollowUpCompleted",
                (state, event) -> projectContact(jdbc, state, event.resultingState(), false));
    }

    @Bean
    PresentationPack crmDesktopPresentationPack(ObservationRegistry observations) {
        return CrmPresentation.desktop().observed(observations);
    }

    @Bean
    PresentationPack crmMobilePresentationPack(ObservationRegistry observations) {
        return CrmPresentation.mobile().observed(observations);
    }

    @Bean
    CrmA2uiAdapter crmA2uiAdapter(ObjectMapper json, ObservationRegistry observations) {
        return new CrmA2uiAdapter(json, observations);
    }

    private static ApplicabilityPolicy followUpPolicy(String action) {
        return ApplicabilityPolicy.of(
                action,
                "io.github.gmcnicol.crm.followUpActions",
                (state, facts) -> facts.stream()
                        .anyMatch(fact -> fact.type().equals("io.github.gmcnicol.crm.FollowUpDue")),
                (state, facts, evaluatedAt) -> java.util.Optional.ofNullable(state.values().get("followUpExpiresAt"))
                        .map(Instant::parse)
                        .filter(evaluatedAt::isBefore));
    }

    private static SemanticVersionAdapter adapter(SemanticVersionAdapter.Contract contract, String type) {
        return SemanticVersionAdapter.identity(contract, type, 1, 2);
    }

    private static void projectContact(
            JdbcTemplate jdbc, ProjectedState state, Map<String, String> values, boolean followUpOpen) {
        Instant lastInteractionAt = java.util.Optional.ofNullable(values.get("lastInteractionAt"))
                .map(Instant::parse).orElse(null);
        jdbc.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, last_interaction_at,
                     next_contact_due_at, open_follow_up_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, contact_id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    last_interaction_at = EXCLUDED.last_interaction_at,
                    next_contact_due_at = EXCLUDED.next_contact_due_at,
                    open_follow_up_id = EXCLUDED.open_follow_up_id
                """, state.tenantId(), state.subject().id(),
                values.getOrDefault("displayName", state.subject().id()),
                lastInteractionAt == null ? null : java.sql.Timestamp.from(lastInteractionAt),
                java.sql.Timestamp.from(Instant.parse(values.get("followUpDueAt"))),
                followUpOpen ? java.util.UUID.nameUUIDFromBytes(
                        (state.tenantId() + ":" + state.subject().id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null);
    }

}
