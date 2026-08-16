package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.SemanticRegistry;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.crm.bindings.GeneratedSemanticRegistry;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpDue;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.micrometer.observation.ObservationRegistry;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
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
    TypedAuthorisationModel<FollowUpProjection> typedCrmAuthorisationModel() {
        return new TypedAuthorisationModel<>(
                FollowUpProjection.TYPE, Set.of(FollowUpProjection.CONTACT_ID), Set.of(FollowUpDue.TYPE));
    }

    @Bean
    SemanticRegistry crmSemanticRegistry() {
        return GeneratedSemanticRegistry.INSTANCE;
    }

    @Bean
    TypedPresentationPack<ContactId, FollowUpProjection> typedCrmDesktopPresentationPack() {
        return CrmPresentation.typedDesktop();
    }

    @Bean
    TypedPresentationPack<ContactId, FollowUpProjection> typedCrmMobilePresentationPack() {
        return CrmPresentation.typedMobile();
    }

    @Bean
    CrmA2uiAdapter crmA2uiAdapter(ObjectMapper json, ObservationRegistry observations) {
        return new CrmA2uiAdapter(json, observations);
    }

}
