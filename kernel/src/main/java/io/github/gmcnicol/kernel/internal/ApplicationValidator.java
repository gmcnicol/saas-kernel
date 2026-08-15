package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.authorisation.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import lang.taxi.TaxiDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

final class ApplicationValidator {

    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)+");
    private static final String SUPPORTED_KERNEL = "0.1";
    private static final String SUPPORTED_FORMAT = "1";

    private final List<SemanticPack> semanticPacks;
    private final List<AuthorisationBundle> authorisationBundles;
    private final List<SemanticImplementation> semanticImplementations;
    private final ResourceLoader resourceLoader;

    ApplicationValidator(
            List<SemanticPack> semanticPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<SemanticImplementation> semanticImplementations,
            ResourceLoader resourceLoader) {
        this.semanticPacks = semanticPacks;
        this.authorisationBundles = authorisationBundles;
        this.semanticImplementations = semanticImplementations;
        this.resourceLoader = resourceLoader;
    }

    void validate() {
        requireExactlyOne("Semantic Pack", semanticPacks.size());
        requireExactlyOne("Authorisation Bundle", authorisationBundles.size());

        String semanticManifestPath = semanticPacks.getFirst().manifestResource();
        Properties semantic = loadProperties(semanticManifestPath);
        String semanticId = qualified(semantic, "Semantic Pack", "id");
        requireValue(semantic, "Semantic Pack", "format-version", SUPPORTED_FORMAT);
        requireValue(semantic, "Semantic Pack", "kernel-compatibility", SUPPORTED_KERNEL);
        coordinate(requireProperty(semantic, "Semantic Pack", "taxi-coordinate"));
        List<String> taxiSources = listProperty(semantic, "Semantic Pack", "taxi-sources");
        verifyChecksum(
                semanticManifestPath,
                concat(taxiSources, optionalListProperty(semantic, "compiled-content")),
                requireProperty(semantic, "Semantic Pack", "checksum"));
        TaxiDocument taxi = compileTaxi(taxiSources);
        validateBindings(taxi, listProperty(semantic, "Semantic Pack", "bindings"));

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

    private TaxiDocument compileTaxi(List<String> sources) {
        String source = sources.stream().map(this::readText).collect(Collectors.joining("\n"));
        try {
            return new Compiler(source, sources.getFirst(), List.of(), new CompilerConfig()).compile();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Taxi compilation failed: " + exception.getMessage(), exception);
        }
    }

    private void validateBindings(TaxiDocument taxi, List<String> declarations) {
        Map<BindingKind, Set<String>> bindings = new EnumMap<>(BindingKind.class);
        Arrays.stream(BindingKind.values()).forEach(kind -> bindings.put(kind, new HashSet<>()));
        for (String declaration : declarations) {
            String[] parts = declaration.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Malformed Semantic Pack binding: " + declaration);
            }
            BindingKind kind;
            try {
                kind = BindingKind.valueOf(parts[0]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Unknown Semantic Pack binding kind: " + parts[0], exception);
            }
            if (!bindings.get(kind).add(parts[1])) {
                throw new IllegalStateException("Duplicate Semantic Pack binding: " + declaration);
            }
        }
        for (BindingKind kind : List.of(BindingKind.FACT, BindingKind.PAYLOAD, BindingKind.EVENT, BindingKind.DERIVATION)) {
            bindings.get(kind).forEach(name -> requireTaxiType(taxi, kind, name));
        }
        for (BindingKind kind : List.of(BindingKind.ACTION, BindingKind.APPLICABILITY, BindingKind.HANDLER)) {
            bindings.get(kind).forEach(name -> requireTaxiOperation(taxi, kind, name));
        }
        Map<SemanticImplementation.Kind, List<String>> implementations = semanticImplementations.stream()
                .collect(Collectors.groupingBy(
                        SemanticImplementation::kind,
                        () -> new EnumMap<>(SemanticImplementation.Kind.class),
                        Collectors.mapping(SemanticImplementation::target, Collectors.toList())));
        for (SemanticImplementation.Kind kind : SemanticImplementation.Kind.values()) {
            List<String> targets = implementations.getOrDefault(kind, List.of());
            if (targets.size() != new HashSet<>(targets).size()) {
                throw new IllegalStateException("Duplicate Semantic Pack implementation: " + kind);
            }
            if (!bindings.get(BindingKind.valueOf(kind.name())).equals(new HashSet<>(targets))) {
                throw new IllegalStateException("Semantic Pack implementation does not match manifest: " + kind);
            }
        }
    }

    private static void requireTaxiType(TaxiDocument taxi, BindingKind kind, String name) {
        if (!taxi.containsType(name)) {
            throw new IllegalStateException(kind + " binding does not resolve to one Taxi type: " + name);
        }
    }

    private static void requireTaxiOperation(TaxiDocument taxi, BindingKind kind, String name) {
        long matches = taxi.getServices().stream()
                .flatMap(service -> service.getOperations().stream()
                        .map(operation -> service.getQualifiedName() + "." + operation.getName()))
                .filter(name::equals)
                .count();
        if (matches != 1) {
            throw new IllegalStateException(kind + " binding does not resolve to one Taxi operation: " + name);
        }
    }

    private void validateCedar(String schemaPath, List<String> policyPaths) {
        try {
            Schema schema = Schema.parse(Schema.JsonOrCedar.Cedar, readText(schemaPath));
            PolicySet policies = PolicySet.parsePolicies(
                    policyPaths.stream().map(this::readText).collect(Collectors.joining("\n")));
            var response = new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policies));
            if (!response.validationPassed()) {
                throw new IllegalStateException("Cedar validation failed: " + response);
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
        return new String(readBytes(path), StandardCharsets.UTF_8);
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

    private enum BindingKind {
        FACT,
        ACTION,
        PAYLOAD,
        EVENT,
        DERIVATION,
        APPLICABILITY,
        HANDLER
    }
}
