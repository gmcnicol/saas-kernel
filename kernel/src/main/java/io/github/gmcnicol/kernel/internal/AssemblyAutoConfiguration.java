package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.semanticpack.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

@AutoConfiguration
public class AssemblyAutoConfiguration {

    @Bean(initMethod = "validate")
    ApplicationAssemblyValidator applicationAssemblyValidator(
            List<SemanticPack> semanticPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<SemanticImplementation> semanticImplementations,
            ResourceLoader resourceLoader) {
        return new ApplicationAssemblyValidator(
                semanticPacks, authorisationBundles, semanticImplementations, resourceLoader);
    }
}
