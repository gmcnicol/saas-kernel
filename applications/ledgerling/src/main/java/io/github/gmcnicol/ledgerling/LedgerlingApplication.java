package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.authorisation.AuthorisationModel;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import io.micrometer.observation.ObservationRegistry;

@SpringBootApplication
public class LedgerlingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlingApplication.class, args);
    }

    @Bean
    SemanticPack ledgerlingSemanticPack() {
        return () -> "semantic-pack/manifest.properties";
    }

    @Bean
    AuthorisationBundle ledgerlingAuthorisationBundle() {
        return () -> "authorisation/manifest.properties";
    }

    @Bean
    AuthorisationModel ledgerlingAuthorisationModel() {
        return new AuthorisationModel() {
            @Override public String subjectType() { return "ledgerling.Filing"; }
            @Override public String resourceType() { return "Filing"; }
            @Override public Map<String, String> fields() {
                return Map.of(
                        "io.github.gmcnicol.ledgerling.Filing.status", "status",
                        "io.github.gmcnicol.ledgerling.Filing.staffNote", "staffNote");
            }
        };
    }

    @Bean
    FactDerivation filingDueSoonDerivation() {
        return FactDerivation.of(
                "io.github.gmcnicol.ledgerling.FilingDueSoon",
                "io.github.gmcnicol.ledgerling.deriveFilingDueSoon",
                (state, evaluatedAt) -> {
                    Instant dueAt = Instant.parse(state.values().get("filingDueAt"));
                    Instant startsAt = dueAt.minus(Duration.ofDays(7));
                    return evaluatedAt.isBefore(startsAt)
                            ? FactDerivation.Derivation.later(startsAt)
                            : FactDerivation.Derivation.fact(Map.of("filingDueAt", dueAt.toString()));
                });
    }

    @Bean
    FactDerivation recordsOutstandingDerivation() {
        return FactDerivation.of(
                "io.github.gmcnicol.ledgerling.RecordsOutstanding",
                "io.github.gmcnicol.ledgerling.deriveRecordsOutstanding",
                (state, evaluatedAt) -> Boolean.parseBoolean(state.values().getOrDefault("recordsOutstanding", "false"))
                        ? FactDerivation.Derivation.fact(Map.of(
                                "requestId", state.values().get("documentRequestId")))
                        : FactDerivation.Derivation.none());
    }

    @Bean
    ApplicabilityPolicy recordRecordsReceivedApplicability() {
        return ApplicabilityPolicy.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived",
                "io.github.gmcnicol.ledgerling.recordsOutstanding",
                (state, facts) -> facts.stream().anyMatch(fact ->
                        fact.type().equals("io.github.gmcnicol.ledgerling.RecordsOutstanding")));
    }

    @Bean
    ApplicabilityPolicy startPreparationApplicability() {
        return ApplicabilityPolicy.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation",
                "io.github.gmcnicol.ledgerling.preparationReady",
                (state, facts) -> facts.stream().noneMatch(fact ->
                                fact.type().equals("io.github.gmcnicol.ledgerling.RecordsOutstanding"))
                        && !Boolean.parseBoolean(state.values().getOrDefault("preparationStarted", "false")));
    }

    @Bean
    IntentHandler recordRecordsReceivedHandler() {
        return IntentHandler.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived", (intent, payload, state) -> {
                    var resultingState = new HashMap<>(state.values());
                    resultingState.put("recordsOutstanding", "false");
                    resultingState.put("recordsReceivedAt", payload.values().get("receivedAt"));
                    return List.of(new Event("io.github.gmcnicol.ledgerling.RecordsReceived", 1,
                            Map.of("requestId", state.values().get("documentRequestId")), resultingState));
                });
    }

    @Bean
    IntentHandler startPreparationHandler() {
        return IntentHandler.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation", (intent, payload, state) -> {
                    var resultingState = new HashMap<>(state.values());
                    resultingState.put("preparationStarted", "true");
                    return List.of(new Event("io.github.gmcnicol.ledgerling.PreparationStarted", 1,
                            Map.of("requestId", state.values().get("documentRequestId")), resultingState));
                });
    }

    @Bean
    PresentationPack ledgerlingPresentationPack(ObservationRegistry observations) {
        return LedgerlingPresentation.defaultPack().observed(observations);
    }
}
