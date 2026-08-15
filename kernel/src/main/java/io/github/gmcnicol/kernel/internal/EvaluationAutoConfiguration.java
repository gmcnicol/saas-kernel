package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.Application;
import io.github.gmcnicol.kernel.application.SemanticPackIdentity;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = ApplicationValidationAutoConfiguration.class)
public class EvaluationAutoConfiguration {

    @Bean
    @DependsOn("applicationValidator")
    Application application(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${spring.application.name}") String applicationId,
            List<SemanticPack> semanticPacks,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies,
            ResourceLoader resources) {
        Properties manifest = load(resources, semanticPacks.getFirst().manifestResource());
        String checksumPath = manifest.getProperty("checksum");
        String checksum;
        try {
            checksum = resources.getResource("classpath:" + checksumPath)
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Semantic Pack checksum", exception);
        }
        return new KernelApplication(
                jdbc,
                new TransactionTemplate(transactionManager),
                applicationId,
                new SemanticPackIdentity(manifest.getProperty("id"), checksum),
                derivations,
                policies);
    }

    private static Properties load(ResourceLoader resources, String path) {
        try (var input = resources.getResource("classpath:" + path).getInputStream()) {
            var properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Semantic Pack manifest", exception);
        }
    }
}
