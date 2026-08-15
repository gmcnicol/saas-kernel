package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

@AutoConfiguration
public class ApplicationValidationAutoConfiguration {

    @Bean(initMethod = "validate")
    ApplicationValidator applicationValidator(
            List<SemanticPack> semanticPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<SemanticImplementation> semanticImplementations,
            ResourceLoader resourceLoader) {
        return new ApplicationValidator(
                semanticPacks, authorisationBundles, semanticImplementations, resourceLoader);
    }
}
