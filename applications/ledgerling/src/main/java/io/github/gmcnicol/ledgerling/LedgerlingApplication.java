package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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
    SemanticImplementation recordRecordsReceivedHandler() {
        return SemanticImplementation.binding(
                SemanticImplementation.Kind.HANDLER,
                "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
    }

    @Bean
    SemanticImplementation startPreparationHandler() {
        return SemanticImplementation.binding(
                SemanticImplementation.Kind.HANDLER,
                "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation");
    }

    @Bean
    PresentationPack ledgerlingPresentationPack() {
        return () -> "ledgerling-default";
    }
}
