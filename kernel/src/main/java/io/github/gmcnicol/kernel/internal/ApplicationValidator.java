package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lang.taxi.Compiler;
import lang.taxi.TaxiDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

final class ApplicationValidator {

    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)+");
    private static final String SUPPORTED_KERNEL = "0.1";
    private static final String SUPPORTED_FORMAT = "1";

    private final List<SemanticPack> semanticPacks;
    private final List<AuthorisationBundle> authorisationBundles;
    private final List<SemanticBindings> semanticBindings;
    private final List<TypedFactDerivation<?, ?>> typedDerivations;
    private final List<TypedApplicabilityPolicy<?>> typedPolicies;
    private final List<TypedIntentHandler<?, ?, ?>> typedHandlers;
    private final List<TypedEventProjector<?, ?>> typedProjectors;
    private final ResourceLoader resourceLoader;
    private final String applicationVersion;
    private TaxiDocument packagedTaxi;

    ApplicationValidator(
            List<SemanticPack> semanticPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<SemanticBindings> semanticBindings,
            List<TypedFactDerivation<?, ?>> typedDerivations,
            List<TypedApplicabilityPolicy<?>> typedPolicies,
            List<TypedIntentHandler<?, ?, ?>> typedHandlers,
            List<TypedEventProjector<?, ?>> typedProjectors,
            ResourceLoader resourceLoader,
            String applicationVersion) {
        this.semanticPacks = semanticPacks;
        this.authorisationBundles = authorisationBundles;
        this.semanticBindings = semanticBindings;
        this.typedDerivations = typedDerivations;
        this.typedPolicies = typedPolicies;
        this.typedHandlers = typedHandlers;
        this.typedProjectors = typedProjectors;
        this.resourceLoader = resourceLoader;
        this.applicationVersion = applicationVersion;
    }

    void validate() {
        requireExactlyOne("Semantic Pack", semanticPacks.size());
        requireExactlyOne("Authorisation Bundle", authorisationBundles.size());

        String semanticManifestPath = semanticPacks.getFirst().manifestResource();
        Properties semantic = loadProperties(semanticManifestPath);
        String semanticId = qualified(semantic, "Semantic Pack", "id");
        requireValue(semantic, "Semantic Pack", "format-version", "2");
        requireValue(semantic, "Semantic Pack", "kernel-compatibility", SUPPORTED_KERNEL);
        coordinate(requireProperty(semantic, "Semantic Pack", "taxi-coordinate"));
        List<String> taxiSources = listProperty(semantic, "Semantic Pack", "taxi-sources");
        List<String> compiledContent = optionalListProperty(semantic, "compiled-content");
        String indexPath = requireProperty(semantic, "Semantic Pack", "semantic-index");
        SemanticIndex index = SemanticIndex.parse(readText(indexPath));
        requireValue(semantic, "Semantic Pack", "application-version", applicationVersion);
        List<String> packagedContent = concat(
                concat(concat(taxiSources, List.of(indexPath)), compiledContent), index.generatedContent());
        unique(packagedContent, "Semantic Pack packaged resource");
        verifyChecksum(semanticManifestPath, packagedContent,
                requireProperty(semantic, "Semantic Pack", "checksum"));
        validateIndexMetadata(index, taxiSources, compiledContent);
        TaxiDocument taxi = compileTaxi(taxiSources);
        packagedTaxi = taxi;
        validateIndex(index, taxi);

        String authorisationManifestPath = authorisationBundles.getFirst().manifestResource();
        Properties authorisation = loadProperties(authorisationManifestPath);
        qualified(authorisation, "Authorisation Bundle", "id");
        requireValue(authorisation, "Authorisation Bundle", "format-version", SUPPORTED_FORMAT);
        requireValue(authorisation, "Authorisation Bundle", "semantic-pack", semanticId);
        String schemaPath = requireProperty(authorisation, "Authorisation Bundle", "schema");
        List<String> policies = listProperty(authorisation, "Authorisation Bundle", "policies");
        verifyChecksum(
                authorisationManifestPath,
                concat(concat(List.of(schemaPath), policies), optionalListProperty(authorisation, "compiled-content")),
                requireProperty(authorisation, "Authorisation Bundle", "checksum"));
        validateCedar(schemaPath, policies);
    }

    TaxiDocument packagedTaxi() {
        if (packagedTaxi == null) throw new IllegalStateException("Application assembly is not validated");
        return packagedTaxi;
    }

    private TaxiDocument compileTaxi(List<String> sources) {
        try {
            return TaxiSchemas.compile(sources, this::readText);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Taxi compilation failed: " + exception.getMessage(), exception);
        }
    }

    private void validateIndexMetadata(
            SemanticIndex index, List<String> taxiSources, List<String> compiledContent) {
        String runtimeVersion = loadProperties("META-INF/saas-kernel/version.properties").getProperty("version");
        if (!runtimeVersion.equals(index.kernelVersion()) || !runtimeVersion.equals(index.generatorVersion())) {
            throw new IllegalStateException("Semantic Index Kernel version mismatch: runtime " + runtimeVersion
                    + ", generator " + index.generatorVersion() + ", index " + index.kernelVersion());
        }
        String compilerVersion = Compiler.class.getPackage().getImplementationVersion();
        if (!index.taxiCompilerVersion().equals(compilerVersion)) {
            throw new IllegalStateException("Semantic Index Taxi compiler version mismatch: runtime "
                    + compilerVersion + ", index " + index.taxiCompilerVersion());
        }
        byte[] standard = readBytes(index.standardSchema().path());
        if (!index.standardSchema().path().equals("META-INF/saas-kernel/standard.taxi")
                || !sha256(standard).equals(index.standardSchema().checksum())) {
            throw new IllegalStateException("Semantic Index Kernel standard schema mismatch");
        }
        String kernelDependency = "io.github.gmcnicol:saas-kernel:" + runtimeVersion + "|"
                + index.standardSchema().checksum();
        if (!index.dependencies().contains(kernelDependency)) {
            throw new IllegalStateException("Semantic Index package dependency mismatch: " + kernelDependency);
        }
        List<String> indexedSources = index.sources().stream().map(SemanticIndex.Source::path).toList();
        if (!indexedSources.equals(taxiSources)) {
            throw new IllegalStateException("Semantic Index Taxi source list mismatch");
        }
        for (SemanticIndex.Source source : index.sources()) {
            if (!sha256(readBytes(source.path())).equals(source.checksum())) {
                throw new IllegalStateException("Semantic Index Taxi source checksum mismatch: " + source.path());
            }
        }
        Set<String> expectedGenerated = expectedGeneratedContent(index);
        if (!Set.copyOf(index.generatedContent()).equals(expectedGenerated)) {
            throw new IllegalStateException("Semantic Index generated content does not match Java bindings");
        }
        Set<String> packagedGenerated = packagedGeneratedContent(generatedBasePackage(index));
        if (!packagedGenerated.equals(expectedGenerated)) {
            Set<String> missing = new HashSet<>(expectedGenerated);
            missing.removeAll(packagedGenerated);
            Set<String> extra = new HashSet<>(packagedGenerated);
            extra.removeAll(expectedGenerated);
            throw new IllegalStateException(
                    "Packaged generated content mismatch: missing " + missing + ", extra " + extra);
        }
        if (index.generatedContent().stream().anyMatch(compiledContent::contains)) {
            throw new IllegalStateException("Semantic Pack manifest repeats generated content");
        }
    }

    private static Set<String> expectedGeneratedContent(SemanticIndex index) {
        if (index.types().isEmpty()) return Set.of();
        String basePackage = generatedBasePackage(index);
        var expected = index.types().stream()
                .map(type -> javaClass(type.javaBinding()))
                .collect(Collectors.toCollection(HashSet::new));
        index.types().forEach(type -> {
            if (!type.javaBinding().equals(basePackage + "." + type.qualifiedName())) {
                throw new IllegalStateException("Semantic Index Java binding mismatch: " + type.qualifiedName());
            }
        });
        index.actions().stream().map(SemanticIndex.ActionEntry::qualifiedName).forEach(action -> {
            String service = action.substring(0, action.lastIndexOf('.'));
            expected.add(javaClass(basePackage + "." + service));
        });
        boolean bindings = index.types().stream().anyMatch(type -> Set.of(
                        "PROJECTION", "FACT", "CANDIDATE", "EVENT").contains(type.role()))
                || !index.actions().isEmpty();
        if (bindings) expected.add(javaClass(basePackage + ".GeneratedSemanticBindings"));
        if (!index.actions().isEmpty()) expected.add(javaClass(basePackage + ".GeneratedSemanticRegistry"));
        return Set.copyOf(expected);
    }

    private Set<String> packagedGeneratedContent(String basePackage) {
        String root = basePackage.replace('.', '/') + "/";
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(resourceLoader)
                    .getResources("classpath*:" + root + "**/*.class");
            var paths = new ArrayList<String>();
            for (Resource resource : resources) {
                String location = resource.getURL().toExternalForm();
                int start = location.lastIndexOf(root);
                if (start < 0) {
                    throw new IllegalStateException("Malformed packaged generated content: " + location);
                }
                paths.add(location.substring(start));
            }
            return unique(paths, "packaged generated content");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inventory packaged generated content", exception);
        }
    }

    private static String generatedBasePackage(SemanticIndex index) {
        if (index.types().isEmpty()) throw new IllegalStateException("Semantic Index has no generated types");
        SemanticIndex.TypeEntry first = index.types().getFirst();
        String suffix = "." + first.qualifiedName();
        if (!first.javaBinding().endsWith(suffix)) {
            throw new IllegalStateException("Semantic Index Java binding mismatch: " + first.qualifiedName());
        }
        return first.javaBinding().substring(0, first.javaBinding().length() - suffix.length());
    }

    private static String javaClass(String name) {
        return name.replace('.', '/') + ".class";
    }

    private void validateIndex(SemanticIndex index, TaxiDocument taxi) {
        requireExactlyOne("generated Semantic Bindings", semanticBindings.size());
        SemanticBindings bindings = semanticBindings.getFirst();
        Set<String> indexedTypes = unique(index.types().stream()
                .map(type -> type.role() + "|" + type.qualifiedName()).toList(), "Semantic Index type");
        index.types().forEach(type -> {
            if (!taxi.containsType(type.qualifiedName())) {
                throw new IllegalStateException("Semantic Index type is absent from packaged Taxi: "
                        + type.qualifiedName());
            }
        });
        var actualTypes = new HashSet<String>();
        bindings.projections().forEach(type -> {
            actualTypes.add(indexedType(
                    type.qualifiedName(), "PROJECTION", type.contractVersion(),
                    type.javaType().getName(), type.subjectType().qualifiedName(), type.fields(), index.types()));
            indexedJavaType(type.subjectType().qualifiedName(), "SUBJECT",
                    type.subjectType().javaType().getName(), index.types());
        });
        bindings.facts().forEach(type -> actualTypes.add(indexedType(
                type.qualifiedName(), "FACT", type.contractVersion(),
                type.javaType().getName(), type.projectionType().qualifiedName(), type.fields(), index.types())));
        bindings.candidates().forEach(type -> actualTypes.add(indexedType(
                type.qualifiedName(), "CANDIDATE", type.contractVersion(),
                type.javaType().getName(), "", type.fields(), index.types())));
        bindings.events().forEach(type -> actualTypes.add(indexedType(
                type.qualifiedName(), "EVENT", type.contractVersion(),
                type.javaType().getName(), "", type.fields(), index.types())));
        Set<String> expectedDurable = indexedTypes.stream()
                .filter(value -> value.startsWith("PROJECTION|") || value.startsWith("FACT|")
                        || value.startsWith("CANDIDATE|") || value.startsWith("EVENT|"))
                .collect(Collectors.toSet());
        if (!actualTypes.equals(expectedDurable)) {
            throw new IllegalStateException("Generated Semantic Bindings types do not match Semantic Index");
        }
        Set<String> expectedActions = unique(index.actions().stream()
                .map(ApplicationValidator::actionIdentity).toList(), "Semantic Index Action");
        Set<String> actualActions = bindings.actions().stream()
                .map(action -> action.qualifiedName() + "|" + action.projectionType().qualifiedName() + "|"
                        + action.candidateType().qualifiedName() + "|" + action.eventTypes().stream()
                                .map(type -> type.qualifiedName()).collect(Collectors.joining(",")))
                .collect(Collectors.toSet());
        if (!actualActions.equals(expectedActions)) {
            throw new IllegalStateException("Generated Semantic Bindings Actions do not match Semantic Index");
        }
        validateImplementationSlots(index.slots());
    }

    private static String indexedType(
            String name,
            String role,
            int version,
            String javaBinding,
            String relationship,
            List<? extends io.github.gmcnicol.kernel.application.FieldType<?, ?>> fields,
            List<SemanticIndex.TypeEntry> entries) {
        List<SemanticIndex.TypeEntry> matches = entries.stream()
                .filter(type -> type.qualifiedName().equals(name) && type.role().equals(role)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Generated Semantic Binding has no unique Semantic Index type: " + name);
        }
        SemanticIndex.TypeEntry index = matches.getFirst();
        if (index.version() != version || !index.javaBinding().equals(javaBinding)
                || !index.relationship().equals(relationship)) {
            throw new IllegalStateException("Generated Semantic Binding metadata mismatch: " + name);
        }
        List<String> indexedFields = index.shape().isEmpty() ? List.of()
                : Arrays.stream(index.shape().split(","))
                        .map(field -> field.substring(0, field.indexOf(':'))).toList();
        List<String> generatedFields = fields.stream()
                .map(io.github.gmcnicol.kernel.application.FieldType::name).toList();
        if (!indexedFields.equals(generatedFields)) {
            throw new IllegalStateException("Generated Semantic Binding fields mismatch: " + name);
        }
        return role + "|" + name;
    }

    private static void indexedJavaType(
            String name, String role, String javaBinding, List<SemanticIndex.TypeEntry> entries) {
        long matches = entries.stream().filter(type -> type.qualifiedName().equals(name)
                && type.role().equals(role) && type.javaBinding().equals(javaBinding)).count();
        if (matches != 1) {
            throw new IllegalStateException("Generated Semantic Binding Java type mismatch: " + name);
        }
    }

    private void validateImplementationSlots(List<SemanticIndex.Slot> slots) {
        Set<String> expected = unique(slots.stream()
                .map(slot -> slot.kind() + "|" + slot.target()).toList(), "Semantic Index implementation slot");
        var actual = new ArrayList<String>();
        typedDerivations.forEach(implementation -> actual.add(
                "DERIVATION|" + implementation.factType().qualifiedName()));
        typedPolicies.forEach(implementation -> actual.add(
                "APPLICABILITY|" + implementation.actionType().qualifiedName()));
        typedHandlers.forEach(implementation -> actual.add(
                "HANDLER|" + implementation.actionType().qualifiedName()));
        typedProjectors.forEach(implementation -> actual.add(
                "PROJECTOR|" + implementation.eventType().qualifiedName()));
        for (String implementation : actual) {
            if (!expected.contains(implementation)) {
                throw new IllegalStateException("Extra Semantic Implementation: " + implementation);
            }
        }
        for (String slot : expected) {
            long matches = actual.stream().filter(slot::equals).count();
            if (matches != 1) {
                throw new IllegalStateException("Semantic Index requires exactly one implementation for "
                        + slot + ", found " + matches);
            }
        }
    }

    private static Set<String> unique(List<String> values, String name) {
        Set<String> unique = new HashSet<>(values);
        if (unique.size() != values.size()) throw new IllegalStateException("Duplicate " + name);
        return Set.copyOf(unique);
    }

    private static String actionIdentity(SemanticIndex.ActionEntry action) {
        return action.qualifiedName() + "|" + action.projection() + "|" + action.candidate() + "|"
                + String.join(",", action.events());
    }

    private void validateCedar(String schemaPath, List<String> policyPaths) {
        try {
            Schema schema = Schema.parse(Schema.JsonOrCedar.Cedar, readText(schemaPath));
            PolicySet policies = PolicySet.parsePolicies(
                    policyPaths.stream().map(this::readText).collect(Collectors.joining("\n")));
            var response = new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policies));
            if (!response.validationPassed()) {
                String errors = response.errors.orElseGet(com.google.common.collect.ImmutableList::of).stream()
                        .map(error -> error.message).collect(Collectors.joining("; "));
                if (errors.isEmpty()) {
                    errors = response.success.stream().flatMap(success -> success.validationErrors.stream())
                            .map(error -> error.getError().message).collect(Collectors.joining("; "));
                }
                throw new IllegalStateException("Cedar validation failed: " + errors);
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cedar validation failed: " + exception.getMessage(), exception);
        }
    }

    private void verifyChecksum(String manifest, List<String> content, String checksumPath) {
        String expected = readText(checksumPath).trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonicalManifest(manifest));
            content.forEach(path -> digest.update(readBytes(path)));
            String actual = java.util.HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) {
                throw new IllegalStateException("Application checksum mismatch: " + manifest);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private byte[] canonicalManifest(String path) {
        String content = readText(path).lines()
                .filter(line -> !line.startsWith("checksum="))
                .collect(Collectors.joining("\n", "", "\n"));
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private Properties loadProperties(String path) {
        try (var input = resource(path).getInputStream()) {
            var properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Application resource: " + path, exception);
        }
    }

    private String readText(String path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(path))).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Malformed UTF-8 Application resource: " + path, exception);
        }
    }

    private byte[] readBytes(String path) {
        try {
            return resource(path).getContentAsByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Application resource: " + path, exception);
        }
    }

    private Resource resource(String path) {
        Resource resource = resourceLoader.getResource("classpath:" + path);
        if (!resource.exists()) {
            throw new IllegalStateException("Application resource not found: " + path);
        }
        return resource;
    }

    private static String qualified(Properties properties, String manifest, String name) {
        String value = requireProperty(properties, manifest, name);
        if (!QUALIFIED_NAME.matcher(value).matches()) {
            throw new IllegalStateException(manifest + " manifest has invalid qualified " + name + ": " + value);
        }
        return value;
    }

    private static void coordinate(String value) {
        if (!Pattern.matches("[^\\s:]+:[^\\s:]+:[^\\s:]+", value)) {
            throw new IllegalStateException("Semantic Pack manifest has invalid Taxi coordinate: " + value);
        }
    }

    private static void requireValue(Properties properties, String manifest, String name, String expected) {
        String value = requireProperty(properties, manifest, name);
        if (!value.equals(expected)) {
            throw new IllegalStateException(manifest + " manifest has unsupported " + name + ": " + value);
        }
    }

    private static List<String> listProperty(Properties properties, String manifest, String name) {
        return Arrays.stream(requireProperty(properties, manifest, name).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static List<String> optionalListProperty(Properties properties, String name) {
        String value = properties.getProperty(name);
        return value == null ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static String requireProperty(Properties properties, String manifest, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(manifest + " manifest missing: " + name);
        }
        return value;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    private static void requireExactlyOne(String name, int count) {
        if (count != 1) {
            throw new IllegalStateException("Application must register exactly one " + name + ", found " + count);
        }
    }

}
