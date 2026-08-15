package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.flywaydb.core.Flyway;

@AutoConfiguration
@AutoConfigureAfter(KernelAutoConfiguration.class)
public class ApplicationValidationAutoConfiguration {

    @Bean(initMethod = "validate")
    ApplicationValidator applicationValidator(
            List<SemanticPack> semanticPacks,
            List<PresentationPack> presentationPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<AuthorisationModel> authorisationModels,
            List<SemanticImplementation> semanticImplementations,
            ResourceLoader resourceLoader,
            @Qualifier("applicationFlyway") ObjectProvider<Flyway> applicationFlyway) {
        applicationFlyway.getIfAvailable();
        return new ApplicationValidator(
                semanticPacks, presentationPacks, authorisationBundles, authorisationModels,
                semanticImplementations, resourceLoader);
    }
}
