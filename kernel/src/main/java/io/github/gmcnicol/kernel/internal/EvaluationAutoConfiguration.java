package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(
        after = ApplicationValidationAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
            "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"
        })
@EnableConfigurationProperties(IntentWorkerProperties.class)
public class EvaluationAutoConfiguration {

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    DefaultKernel kernel(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AuthorisationService authorisation,
            IntentService intents,
            IntentExecutionService execution,
            IntentQueryService intentQueries,
            IntentWorkerProperties worker,
            Clock clock,
            SemanticPackVersion semanticPackVersion,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion,
            List<FactDerivation> derivations,
            List<ApplicabilityPolicy> policies,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<SemanticBindings> typedBindings,
            io.github.gmcnicol.kernel.application.CanonicalCodec.Limits canonicalLimits,
            KernelTelemetry telemetry) {
        return new DefaultKernel(
                jdbc,
                new TransactionTemplate(transactionManager),
                authorisation,
                intents,
                execution,
                intentQueries,
                worker,
                clock,
                new ApplicationVersion(applicationId, applicationVersion),
                kernelVersion(),
                semanticPackVersion,
                derivations,
                policies,
                typedDerivations,
                typedPolicies,
                typedBindings,
                canonicalLimits,
                telemetry);
    }

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    AuthorisationService authorisationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CedarAuthoriser cedar,
            EvaluationStore evaluations,
            TaxiPayloadValidator payloads,
            KernelTelemetry telemetry) {
        return new AuthorisationService(
                jdbc, new TransactionTemplate(transactionManager), cedar, evaluations, payloads, telemetry);
    }

    @Bean
    EvaluationStore evaluationStore(JdbcTemplate jdbc) {
        return new EvaluationStore(jdbc);
    }

    @Bean
    IntentQueryService intentQueryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new IntentQueryService(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    IntentInvariantValidator intentInvariantValidator() {
        return new IntentInvariantValidator();
    }

    @Bean
    FatalInvariantHandler fatalInvariantHandler(
            ConfigurableApplicationContext context, KernelTelemetry telemetry, KernelRuntimeHealth health) {
        return new FatalInvariantHandler(context, telemetry, health);
    }

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    SemanticDeploymentGuard semanticDeploymentGuard(
            DataSource dataSource,
            SemanticPackVersion semanticPack,
            @Value("${spring.application.name}") String applicationId,
            ConfigurableApplicationContext context) {
        return new SemanticDeploymentGuard(dataSource, applicationId, semanticPack.checksum(), () -> {
            org.springframework.boot.availability.AvailabilityChangeEvent.publish(
                    context, org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
            Thread.ofPlatform().name("kernel-deployment-guard-shutdown").start(context::close);
        });
    }

    @Bean
    @DependsOn({"applicationFlyway", "applicationValidator", "runtimeRoleValidator"})
    KernelRuntimeHealth kernelRuntimeHealthIndicator(
            ObjectProvider<FixedDelayWorker> workers,
            ObjectProvider<SemanticDeploymentGuard> deploymentGuard) {
        return new KernelRuntimeHealth(workers, deploymentGuard);
    }

    @Bean
    IntentWorker intentWorker(IntentExecutionService execution, IntentWorkerProperties policy, Clock clock) {
        return new IntentWorker(execution, policy, clock);
    }

    @Bean
    ReevaluationWorker reevaluationWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        return new ReevaluationWorker(kernel, policy, clock);
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
        Set<String> actions = java.util.Arrays.stream(manifest.getProperty("bindings").split(","))
                .map(String::trim)
                .filter(binding -> binding.startsWith("ACTION="))
                .map(binding -> binding.substring("ACTION=".length()))
                .collect(Collectors.toSet());
        Set<String> events = java.util.Arrays.stream(manifest.getProperty("bindings").split(","))
                .map(String::trim)
                .filter(binding -> binding.startsWith("EVENT="))
                .map(binding -> binding.substring("EVENT=".length()))
                .collect(Collectors.toSet());
        return new TaxiPayloadValidator(
                TaxiSchemas.compile(sources, path -> read(resources, path)), actions, events);
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
            Clock clock,
            KernelTelemetry telemetry,
            SemanticCompatibility compatibility) {
        return new IntentService(
                jdbc,
                new TransactionTemplate(transactionManager),
                evaluations,
                cedar,
                payloads,
                semanticPackVersion,
                policies,
                derivations,
                clock,
                telemetry,
                compatibility);
    }

    @Bean
    IntentExecutionService intentExecutionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            List<io.github.gmcnicol.kernel.semanticpack.IntentHandler> handlers,
            List<io.github.gmcnicol.kernel.semanticpack.EventProjector> projectors,
            TaxiPayloadValidator payloads,
            SemanticPackVersion semanticPackVersion,
            List<ApplicabilityPolicy> policies,
            List<FactDerivation> derivations,
            CedarAuthoriser cedar,
            IntentWorkerProperties worker,
            IntentInvariantValidator invariants,
            FatalInvariantHandler fatalInvariants,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticCompatibility compatibility) {
        return new IntentExecutionService(
                jdbc, new TransactionTemplate(transactionManager), handlers, projectors, payloads,
                semanticPackVersion, policies, derivations, cedar, worker, invariants, fatalInvariants, clock,
                telemetry, compatibility);
    }

    @Bean
    SemanticCompatibility semanticCompatibility(List<SemanticVersionAdapter> adapters) {
        return new SemanticCompatibility(adapters);
    }

    @Bean
    KernelTelemetry kernelTelemetry(
            ObservationRegistry observations,
            MeterRegistry meters,
            ObjectProvider<Tracer> tracer,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion,
            @Value("${kernel.telemetry.subject-key}") String subjectKey,
            @Value("${kernel.telemetry.subject-key-id}") String subjectKeyId) {
        return new KernelTelemetry(
                observations, meters, tracer.getIfAvailable(() -> Tracer.NOOP),
                new ApplicationVersion(applicationId, applicationVersion), kernelVersion(), subjectKey, subjectKeyId);
    }

    @Bean
    @ConditionalOnMissingBean
    io.github.gmcnicol.kernel.application.CanonicalCodec.Limits canonicalCodecLimits() {
        return io.github.gmcnicol.kernel.application.CanonicalCodec.Limits.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry kernelObservationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    @ConditionalOnMissingBean
    MeterRegistry kernelMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnClass(InfoContributor.class)
    InfoContributor kernelInfo(
            SemanticPackVersion semanticPack,
            CedarAuthoriser cedar,
            List<PresentationPack> presentations,
            ResourceLoader resources,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion) {
        return builder -> builder
                .withDetail("application", java.util.Map.of("id", applicationId, "version", applicationVersion))
                .withDetail("kernel", java.util.Map.of("version", kernelVersion()))
                .withDetail("semanticPack", java.util.Map.of(
                        "id", semanticPack.id(), "checksum", semanticPack.checksum()))
                .withDetail("authorisationBundle", java.util.Map.of(
                        "id", cedar.bundleId(), "checksum", cedar.bundleChecksum()))
                .withDetail("presentationPacks", presentations.stream().map(pack -> {
                    Properties manifest = load(resources, pack.manifestResource());
                    return java.util.Map.of(
                            "id", manifest.getProperty("id"),
                            "checksum", read(resources, manifest.getProperty("checksum")).trim());
                }).toList());
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
