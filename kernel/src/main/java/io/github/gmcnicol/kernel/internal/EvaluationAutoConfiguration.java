package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityAdapter;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityRequirement;
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
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
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
            TypedActionService typedActions,
            IntentQueryService intentQueries,
            IntentWorkerProperties worker,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticPackVersion semanticPackVersion,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<SemanticBindings> typedBindings,
            SemanticCodec semanticCodec,
            io.github.gmcnicol.kernel.application.ApplicationWorkflowData applicationData) {
        return new DefaultKernel(
                jdbc,
                new TransactionTemplate(transactionManager),
                typedActions,
                intentQueries,
                worker,
                clock,
                new ApplicationVersion(applicationId, applicationVersion),
                kernelVersion(),
                semanticPackVersion,
                typedDerivations,
                typedPolicies,
                typedBindings,
                semanticCodec,
                telemetry, applicationData);
    }

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    ScopedAuthorisedReads authorisedReads(
            DataSource dataSource, CedarAuthoriser cedar, List<SemanticBindings> bindings, MeterRegistry meters) {
        return new ScopedAuthorisedReads(dataSource, cedar, bindings, meters);
    }

    @Bean
    io.github.gmcnicol.kernel.application.AuthorisedDisclosures authorisedDisclosures(ScopedAuthorisedReads reads, java.time.Clock clock) {
        return new FencedDisclosures(reads, clock);
    }

    @Bean
    IntentQueryService intentQueryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new IntentQueryService(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    @DependsOn({"applicationValidator", "runtimeRoleValidator"})
    SemanticDeploymentGuard semanticDeploymentGuard(
            DataSource dataSource,
            @Qualifier("semanticDeploymentGuardDataSource") ObjectProvider<DataSource> dedicatedDataSource,
            SemanticPackVersion semanticPack,
            @Value("${spring.application.name}") String applicationId,
            ConfigurableApplicationContext context) {
        var guardDataSource = dedicatedDataSource.getIfAvailable(() -> deploymentGuardDataSource(dataSource));
        if (DataSourceUnwrapper.unwrapRoot(guardDataSource) == DataSourceUnwrapper.unwrapRoot(dataSource)) {
            throw unsupportedGuardDataSource();
        }
        return new SemanticDeploymentGuard(guardDataSource, applicationId, semanticPack.checksum(), () -> {
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
    IntentWorker intentWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        return new IntentWorker(kernel, policy, clock);
    }

    @Bean
    ReevaluationWorker reevaluationWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        return new ReevaluationWorker(kernel, policy, clock);
    }

    @Bean(destroyMethod = "close")
    CedarAuthoriser cedarAuthoriser(
            List<AuthorisationBundle> bundles,
            List<io.github.gmcnicol.kernel.application.TypedAuthorisationModel<?>> typedModels,
            List<SemanticBindings> bindings,
            List<io.github.gmcnicol.kernel.application.EffectiveCapabilityResolver> resolvers,
            Clock clock,
            ResourceLoader resources,
            org.springframework.core.env.Environment environment,
            MeterRegistry meters) {
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
                    typedModels,
                    manifest.getProperty("id"),
                    checksum, bindings, resolvers, clock,
                    environment.getProperty("kernel.authorisation.read-evaluator", "auto"), meters);
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
    TypedActionService typedActionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CedarAuthoriser cedar,
            IntentWorkerProperties worker,
            Clock clock,
            KernelTelemetry telemetry,
            SemanticPackVersion semanticPack,
            List<SemanticBindings> bindings,
            List<io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler<?, ?, ?>> handlers,
            List<io.github.gmcnicol.kernel.semanticpack.TypedEventProjector<?, ?>> projectors,
            List<TypedApplicabilityPolicy<?>> policies,
            List<TypedFactDerivation<?, ?>> derivations,
            SemanticCodec semanticCodec,
            io.github.gmcnicol.kernel.application.ApplicationWorkflowData applicationData) {
        return new TypedActionService(
                jdbc, new TransactionTemplate(transactionManager), cedar, worker, clock, telemetry, semanticPack, bindings,
                handlers, projectors, policies, derivations, semanticCodec, applicationData);
    }

    @Bean
    SemanticCodec semanticCodec(
            List<SemanticBindings> bindings,
            List<TypedCompatibilityAdapter<?, ?>> compatibilityAdapters,
            List<TypedCompatibilityRequirement<?, ?>> compatibilityRequirements,
            io.github.gmcnicol.kernel.application.CanonicalCodec.Limits limits) {
        return new SemanticCodec(bindings, compatibilityAdapters, compatibilityRequirements, limits);
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
            ResourceLoader resources,
            @Value("${spring.application.name}") String applicationId,
            @Value("${spring.application.version}") String applicationVersion) {
        return builder -> builder
                .withDetail("application", java.util.Map.of("id", applicationId, "version", applicationVersion))
                .withDetail("kernel", java.util.Map.of("version", kernelVersion()))
                .withDetail("semanticPack", java.util.Map.of(
                        "id", semanticPack.id(), "checksum", semanticPack.checksum()))
                .withDetail("authorisationBundle", java.util.Map.of(
                        "id", cedar.bundleId(), "checksum", cedar.bundleChecksum()));
    }

    @Bean
    @ConditionalOnMissingBean
    Clock kernelClock() {
        return Clock.systemUTC();
    }

    private static DataSource deploymentGuardDataSource(DataSource dataSource) {
        var source = DataSourceUnwrapper.unwrapRoot(dataSource);
        // One accounted, non-pooled session keeps the lifetime lock outside the request pool.
        if (source.getClass() == DriverManagerDataSource.class || source.getClass() == SimpleDriverDataSource.class) {
            var dedicated = (AbstractDriverBasedDataSource) DataSourceBuilder.derivedFrom(source)
                    .type(source.getClass()).build();
            dedicated.setConnectionProperties(copyConnectionProperties(
                    ((AbstractDriverBasedDataSource) source).getConnectionProperties()));
            if (source instanceof SimpleDriverDataSource simple) {
                ((SimpleDriverDataSource) dedicated).setDriver(simple.getDriver());
            }
            return dedicated;
        }
        if (source.getClass().getName().equals("com.zaxxer.hikari.HikariDataSource")) {
            var properties = new BeanWrapperImpl(source);
            // Datasource-backed pools, session setup and rotating credentials cannot be cloned safely.
            for (var property : List.of("dataSource", "dataSourceClassName", "dataSourceJNDI",
                    "connectionInitSql", "schema", "catalog", "transactionIsolation",
                    "credentialsProvider", "credentialsProviderClassName")) {
                if (properties.isReadableProperty(property) && properties.getPropertyValue(property) != null) {
                    throw unsupportedGuardDataSource();
                }
            }
            if (properties.getPropertyValue("jdbcUrl") == null
                    || Boolean.TRUE.equals(properties.getPropertyValue("readOnly"))) {
                throw unsupportedGuardDataSource();
            }
            var dedicated = DataSourceBuilder.derivedFrom(source).type(SimpleDriverDataSource.class).build();
            dedicated.setConnectionProperties(copyConnectionProperties(
                    (Properties) properties.getPropertyValue("dataSourceProperties")));
            return dedicated;
        }
        throw unsupportedGuardDataSource();
    }

    private static Properties copyConnectionProperties(Properties properties) {
        // Keep SSL and driver options without sharing the mutable property map.
        return properties == null ? null : (Properties) properties.clone();
    }

    private static IllegalStateException unsupportedGuardDataSource() {
        return new IllegalStateException("Cannot safely create an independent Semantic Pack deployment guard connection. "
                + "Define @Bean(defaultCandidate = false) named 'semanticDeploymentGuardDataSource': "
                + "a dedicated non-pooled DataSource using the same runtime login, URL, driver and SSL/options "
                + "as the request DataSource; account for one additional PostgreSQL connection.");
    }

    private static String kernelVersion() {
        String version = EvaluationAutoConfiguration.class.getPackage().getImplementationVersion();
        return version == null ? "0.2.0" : version;
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
