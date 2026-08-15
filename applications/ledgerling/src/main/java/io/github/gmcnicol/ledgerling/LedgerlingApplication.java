package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
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
    SemanticImplementation recordsOutstandingDerivation() {
        return new SemanticImplementation(SemanticImplementation.Kind.DERIVATION, "io.github.gmcnicol.ledgerling.RecordsOutstanding");
    }

    @Bean
    SemanticImplementation recordRecordsReceivedApplicability() {
        return new SemanticImplementation(SemanticImplementation.Kind.APPLICABILITY, "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
    }

    @Bean
    SemanticImplementation recordRecordsReceivedHandler() {
        return new SemanticImplementation(SemanticImplementation.Kind.HANDLER, "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived");
    }

    @Bean
    PresentationPack ledgerlingPresentationPack() {
        return () -> "ledgerling-default";
    }
}
