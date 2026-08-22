package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpDue;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ApplicationAssemblyIT {

    private static final String INDEX = "META-INF/saas-kernel/semantic-index.properties";
    private static final String MANIFEST = "semantic-pack/manifest.properties";
    private static final String CHECKSUM = "semantic-pack/checksum.sha256";
    private static final ClassLoader PACKAGED_APPLICATION = packagedApplication();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withInitScript("postgres-init.sql");

    @Test
    void acceptsPackagedAssemblyBeforeReadiness() {
        try (var context = application(Map.of(), List.of()).run()) {
            assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void acceptedTypedIntentSurvivesPackagedApplicationRestart() {
        UUID intentId = UUID.randomUUID();
        try (var context = application(Map.of(), List.of()).run()) {
            var admin = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            Instant dueAt = Instant.parse("2020-08-15T09:00:00Z");
            admin.update("""
                    INSERT INTO crm_contact_engagement_projection
                        (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                    VALUES ('tenant-restart', 'restart-contact', 'Restart Contact', ?, ?)
                    """, java.sql.Timestamp.from(dueAt), UUID.randomUUID());
            Kernel kernel = context.getBean(Kernel.class);
            FollowUpProjection projection = context.getBean(CrmContactQueries.class)
                    .projection("tenant-restart", "restart-contact");
            var snapshot = kernel.evaluate(new TypedProjectedState<>("tenant-restart",
                    new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                    FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
            var offer = kernel.authorise("tenant-restart", snapshot.id(),
                    new Principal("Owner", "restart"), dueAt.plusSeconds(2), FollowUpProjection.TYPE)
                    .actionOffers().getFirst();
            kernel.accept(offer.id(), intentId, TypedCrmActions.RECORD_INTERACTION.candidate(
                    new RecordInteractionCandidateV1("restart")));
        }

        try (var restarted = application(Map.of(), List.of()).run()) {
            assertThat(restarted.getBean(Kernel.class).findIntents(IntentQuery.tenant("tenant-restart")))
                    .singleElement().extracting(view -> view.id()).isEqualTo(intentId);
        }
    }

    @Test
    void rejectsStaleSemanticIndexBeforeReadiness() {
        Map<String, byte[]> resources = indexedOverrides(index -> index.replaceFirst(
                "source\\.0=[^|]+\\|[0-9a-f]{64}",
                "source.0=semantic-pack/schema.taxi|" + "0".repeat(64)));

        assertStartupFails(resources, List.of(),
                "Semantic Index Taxi source checksum mismatch: semantic-pack/schema.taxi");
    }

    @Test
    void rejectsMissingSemanticIndexBeforeReadiness() {
        assertStartupFails(Map.of(), List.of(INDEX), "Application resource not found: " + INDEX);
    }

    @Test
    void rejectsRemovedSemanticIndexDeclarationBeforeReadiness() {
        assertStartupFails(Map.of(MANIFEST, text(MANIFEST)
                        .replace("semantic-index=" + INDEX + "\n", "").getBytes(StandardCharsets.UTF_8)),
                List.of(), "Semantic Pack manifest missing: semantic-index");
    }

    @Test
    void rejectsDuplicateGeneratedImplementationBeforeReadiness() {
        assertStartupFails(Map.of(), List.of(),
                "Semantic Index requires exactly one implementation for DERIVATION|"
                        + FollowUpDue.TYPE.qualifiedName() + ", found 2",
                DuplicateImplementation.class);
    }

    @Test
    void rejectsExtraGeneratedImplementationBeforeReadiness() {
        assertStartupFails(Map.of(), List.of(),
                "Extra Semantic Implementation: DERIVATION|test.ExtraFact",
                ExtraImplementation.class);
    }

    @Test
    void rejectsExtraPackagedGeneratedClassBeforeReadiness() {
        assertThatThrownBy(() -> application(
                        Map.of(), List.of(), packagedApplicationWithExtraClass()).run())
                .hasRootCauseMessage("Packaged generated content mismatch: missing [], extra "
                        + "[io/github/gmcnicol/crm/bindings/StaleGeneratedBinding.class]");
    }

    @Test
    void rejectsMalformedSemanticIndexBeforeReadiness() {
        Map<String, byte[]> resources = indexedOverrides(index ->
                index.replace("format-version=1\n", "format-version=1\nformat-version=1\n"));

        assertStartupFails(resources, List.of(), "Malformed Semantic Index: duplicate entry: format-version");
    }

    @Test
    void rejectsIncompatibleTaxiCompilerBeforeReadiness() {
        Map<String, byte[]> resources = indexedOverrides(index ->
                index.replace("taxi-compiler-version=1.70.0", "taxi-compiler-version=0.0.0"));
        String runtime = lang.taxi.Compiler.class.getPackage().getImplementationVersion();

        assertStartupFails(resources, List.of(),
                "Semantic Index Taxi compiler version mismatch: runtime " + runtime + ", index 0.0.0");
    }

    @Test
    void rejectsTamperedTaxiBeforeReadiness() {
        assertStartupFails(
                Map.of("semantic-pack/schema.taxi", (text("semantic-pack/schema.taxi") + "\n// tampered\n")
                        .getBytes(StandardCharsets.UTF_8)),
                List.of(), "Application checksum mismatch: " + MANIFEST);
    }

    private static void assertStartupFails(
            Map<String, byte[]> overrides, List<String> missing, String message, Class<?>... extraSources) {
        assertThatThrownBy(() -> application(overrides, missing, extraSources).run())
                .hasRootCauseMessage(message);
    }

    private static SpringApplicationBuilder application(
            Map<String, byte[]> overrides, List<String> missing, Class<?>... extraSources) {
        return application(overrides, missing, PACKAGED_APPLICATION, extraSources);
    }

    private static SpringApplicationBuilder application(
            Map<String, byte[]> overrides,
            List<String> missing,
            ClassLoader packagedApplication,
            Class<?>... extraSources) {
        var sources = new ArrayList<Class<?>>();
        sources.add(KeepInTouchCrmApplication.class);
        sources.addAll(List.of(extraSources));
        var loader = new DefaultResourceLoader(packagedApplication) {
            @Override
            public Resource getResource(String location) {
                String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
                if (missing.contains(path)) return new ClassPathResource("missing-application-resource", getClassLoader());
                byte[] content = overrides.get(path);
                return content == null ? super.getResource(location) : new ByteArrayResource(content, path);
            }
        };
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .resourceLoader(loader)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=kernel_test_login",
                        "spring.datasource.password=kernel-test",
                        "spring.flyway.url=" + postgres.getJdbcUrl(),
                        "spring.flyway.user=" + postgres.getUsername(),
                        "spring.flyway.password=" + postgres.getPassword(),
                        "server.port=0",
                        "management.server.port=0",
                        "kernel.intent-worker.enabled=false",
                        "spring.main.banner-mode=off",
                        "logging.level.root=OFF");
    }

    private static byte[] withIndexChecksum(String index) {
        String canonical = index.lines()
                .filter(line -> !line.startsWith("index-checksum="))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        return (canonical + "index-checksum=" + sha256(canonical.getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> indexedOverrides(java.util.function.UnaryOperator<String> change) {
        var resources = new HashMap<String, byte[]>();
        resources.put(INDEX, withIndexChecksum(change.apply(text(INDEX))));
        resources.put(CHECKSUM, packageChecksum(resources).getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(resources);
    }

    private static String packageChecksum(Map<String, byte[]> overrides) {
        Properties manifest = new Properties();
        try {
            manifest.load(new StringReader(text(MANIFEST)));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        var paths = new ArrayList<String>();
        paths.addAll(csv(manifest.getProperty("taxi-sources")));
        paths.add(manifest.getProperty("semantic-index"));
        paths.addAll(csv(manifest.getProperty("compiled-content")));
        new String(overrides.getOrDefault(INDEX, bytes(INDEX)), StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("generated-content."))
                .map(line -> line.substring(line.indexOf('=') + 1))
                .forEach(paths::add);
        var digest = digest();
        String canonicalManifest = text(MANIFEST).lines()
                .filter(line -> !line.startsWith("checksum="))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        digest.update(canonicalManifest.getBytes(StandardCharsets.UTF_8));
        paths.forEach(path -> digest.update(overrides.getOrDefault(path, bytes(path))));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> csv(String value) {
        return List.of(value.split(","));
    }

    private static String text(String path) {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String path) {
        try {
            return new ClassPathResource(path, PACKAGED_APPLICATION).getContentAsByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] content) {
        return java.util.HexFormat.of().formatHex(digest().digest(content));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ClassLoader packagedApplication() {
        try (var files = Files.list(Path.of("target"))) {
            URL jar = files.filter(path -> path.getFileName().toString().endsWith(".jar.original"))
                    .findFirst().orElseThrow().toUri().toURL();
            return new URLClassLoader(new URL[] {jar}, ApplicationAssemblyIT.class.getClassLoader()) {
                @Override
                public URL getResource(String name) {
                    URL packaged = findResource(name);
                    return packaged == null ? super.getResource(name) : packaged;
                }

                @Override
                public java.util.Enumeration<URL> getResources(String name) throws IOException {
                    List<URL> packaged = java.util.Collections.list(findResources(name));
                    return packaged.isEmpty() ? super.getResources(name)
                            : java.util.Collections.enumeration(packaged);
                }
            };
        } catch (IOException exception) {
            throw new IllegalStateException("Packaged Application JAR is missing", exception);
        }
    }

    private static ClassLoader packagedApplicationWithExtraClass() {
        try {
            Path directory = Files.createTempDirectory("stale-generated-binding-");
            Path stale = directory.resolve("io/github/gmcnicol/crm/bindings/StaleGeneratedBinding.class");
            Files.createDirectories(stale.getParent());
            Files.write(stale, bytes("io/github/gmcnicol/crm/bindings/io/github/gmcnicol/crm/FollowUpDue.class"));
            return new URLClassLoader(new URL[] {directory.toUri().toURL()}, PACKAGED_APPLICATION);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DuplicateImplementation {
        @Bean
        TypedFactDerivation<FollowUpProjection, FollowUpDue> duplicateFollowUpDueDerivation() {
            return FollowUpDue.DERIVATION.bind((projection, at) -> TypedFactDerivation.Result.none());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraImplementation {
        private static final io.github.gmcnicol.kernel.application.FactType<FollowUpDue> EXTRA =
                new io.github.gmcnicol.kernel.application.FactType<>(
                        "test.ExtraFact", 1, FollowUpProjection.TYPE, FollowUpDue.class);

        @Bean
        TypedFactDerivation<FollowUpProjection, FollowUpDue> extraDerivation() {
            return new TypedFactDerivation<>() {
                @Override public io.github.gmcnicol.kernel.application.FactType<FollowUpDue> factType() {
                    return EXTRA;
                }
                @Override public String id() { return "test.ExtraFact"; }
                @Override public Result<FollowUpDue> derive(FollowUpProjection projection, java.time.Instant at) {
                    return Result.none();
                }
            };
        }
    }
}
