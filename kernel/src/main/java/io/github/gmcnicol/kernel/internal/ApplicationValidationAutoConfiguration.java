package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.flywaydb.core.Flyway;

@AutoConfiguration
@AutoConfigureAfter(KernelAutoConfiguration.class)
public class ApplicationValidationAutoConfiguration {

    @Bean(initMethod = "validate")
    ApplicationValidator applicationValidator(
            List<SemanticPack> semanticPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<SemanticBindings> semanticBindings,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<TypedIntentHandler<?, ?, ?>> typedHandlers,
            List<TypedEventProjector<?, ?>> typedProjectors,
            ResourceLoader resourceLoader,
            @Value("${spring.application.version:unknown}") String applicationVersion,
            @Qualifier("applicationFlyway") ObjectProvider<Flyway> applicationFlyway) {
        applicationFlyway.getIfAvailable();
        return new ApplicationValidator(
                semanticPacks, authorisationBundles, semanticBindings, typedDerivations, typedPolicies,
                typedHandlers, typedProjectors, resourceLoader, applicationVersion);
    }
}
