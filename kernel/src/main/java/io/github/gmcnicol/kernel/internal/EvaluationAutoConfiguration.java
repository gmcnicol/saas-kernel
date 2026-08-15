package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.authorisation.AuthorisationModel;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = ApplicationValidationAutoConfiguration.class)
public class EvaluationAutoConfiguration {

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    Kernel kernel(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AuthorisationService authorisation,
            IntentService intents,
            SemanticPackVersion semanticPackVersion,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies) {
        return new DefaultKernel(
                jdbc,
                new TransactionTemplate(transactionManager),
                authorisation,
                intents,
                new ApplicationVersion(applicationId, applicationVersion),
                kernelVersion(),
                semanticPackVersion,
                derivations,
                policies);
    }

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    AuthorisationService authorisationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CedarAuthoriser cedar,
            EvaluationStore evaluations) {
        return new AuthorisationService(jdbc, new TransactionTemplate(transactionManager), cedar, evaluations);
    }

    @Bean
    EvaluationStore evaluationStore(JdbcTemplate jdbc) {
        return new EvaluationStore(jdbc);
    }

    @Bean
    CedarAuthoriser cedarAuthoriser(
            List<AuthorisationBundle> bundles,
            List<AuthorisationModel> models,
            ResourceLoader resources) {
        Properties manifest = load(resources, bundles.getFirst().manifestResource());
        String schema = read(resources, manifest.getProperty("schema"));
        String policies = java.util.Arrays.stream(manifest.getProperty("policies").split(","))
                .map(String::trim)
                .map(path -> read(resources, path))
                .collect(java.util.stream.Collectors.joining("\n"));
        String checksum = read(resources, manifest.getProperty("checksum")).trim();
        try {
            return new CedarAuthoriser(
                    Schema.parse(Schema.JsonOrCedar.Cedar, schema),
                    PolicySet.parsePolicies(policies),
                    models.getFirst(),
                    manifest.getProperty("id"),
                    checksum);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialise Authorisation Bundle", exception);
        }
    }

    @Bean
    SemanticPackVersion semanticPackVersion(List<SemanticPack> semanticPacks, ResourceLoader resources) {
        Properties manifest = load(resources, semanticPacks.getFirst().manifestResource());
        return new SemanticPackVersion(
                manifest.getProperty("id"), read(resources, manifest.getProperty("checksum")).trim());
    }

    @Bean
    TaxiPayloadValidator taxiPayloadValidator(List<SemanticPack> semanticPacks, ResourceLoader resources) {
        Properties manifest = load(resources, semanticPacks.getFirst().manifestResource());
        List<String> sources = java.util.Arrays.stream(manifest.getProperty("taxi-sources").split(","))
                .map(String::trim)
                .toList();
        String source = sources.stream().map(path -> read(resources, path)).collect(Collectors.joining("\n"));
        return new TaxiPayloadValidator(
                new Compiler(source, sources.getFirst(), List.of(), new CompilerConfig()).compile());
    }

    @Bean
    IntentService intentService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            EvaluationStore evaluations,
            CedarAuthoriser cedar,
            TaxiPayloadValidator payloads,
            SemanticPackVersion semanticPackVersion,
            List<ApplicabilityPolicy> policies,
            List<FactDerivation> derivations,
            Clock clock) {
        return new IntentService(
                jdbc,
                new TransactionTemplate(transactionManager),
                evaluations,
                cedar,
                payloads,
                semanticPackVersion,
                policies,
                derivations,
                clock);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock kernelClock() {
        return Clock.systemUTC();
    }

    private static String kernelVersion() {
        String version = EvaluationAutoConfiguration.class.getPackage().getImplementationVersion();
        return version == null ? "0.1.0-SNAPSHOT" : version;
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

    private static String read(ResourceLoader resources, String path) {
        try {
            return resources.getResource("classpath:" + path)
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Application resource: " + path, exception);
        }
    }
}
