package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.authorisation.AuthorisationModel;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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
    PresentationPack crmPresentationPack() {
        return () -> "keep-in-touch-crm-default";
    }

    private static ApplicabilityPolicy followUpPolicy(String action) {
        return ApplicabilityPolicy.of(
                action,
                "io.github.gmcnicol.crm.followUpActions",
                (state, facts) -> facts.stream()
                        .anyMatch(fact -> fact.type().equals("io.github.gmcnicol.crm.FollowUpDue")));
    }
}
