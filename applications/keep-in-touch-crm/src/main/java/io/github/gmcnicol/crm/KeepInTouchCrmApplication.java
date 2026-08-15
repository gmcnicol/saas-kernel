package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
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
    SemanticImplementation followUpDueDerivation() {
        return new SemanticImplementation(SemanticImplementation.Kind.DERIVATION, "io.github.gmcnicol.crm.FollowUpDue");
    }

    @Bean
    SemanticImplementation recordInteractionApplicability() {
        return new SemanticImplementation(SemanticImplementation.Kind.APPLICABILITY, "io.github.gmcnicol.crm.CrmActions.recordInteraction");
    }

    @Bean
    SemanticImplementation recordInteractionHandler() {
        return new SemanticImplementation(SemanticImplementation.Kind.HANDLER, "io.github.gmcnicol.crm.CrmActions.recordInteraction");
    }

    @Bean
    PresentationPack crmPresentationPack() {
        return () -> "keep-in-touch-crm-default";
    }
}
