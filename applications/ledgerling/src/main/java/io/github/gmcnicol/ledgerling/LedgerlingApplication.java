package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.ledgerling.bindings.GeneratedSemanticRegistry;
import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.SemanticRegistry;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
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
    SemanticRegistry ledgerlingSemanticRegistry() {
        return GeneratedSemanticRegistry.INSTANCE;
    }

    @Bean
    TypedPresentationPack<FilingId, FilingProjection> ledgerlingPresentation() {
        return LedgerlingPresentation.typed();
    }
}
