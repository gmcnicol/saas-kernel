package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.application.PresentationActionOffer;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.application.PresentationFact;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
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
    private final List<PresentationPack> presentationPacks;
    private final List<AuthorisationBundle> authorisationBundles;
    private final List<AuthorisationModel> authorisationModels;
    private final List<SemanticImplementation> semanticImplementations;
    private final ResourceLoader resourceLoader;

    ApplicationValidator(
            List<SemanticPack> semanticPacks,
            List<PresentationPack> presentationPacks,
            List<AuthorisationBundle> authorisationBundles,
            List<AuthorisationModel> authorisationModels,
            List<SemanticImplementation> semanticImplementations,
            ResourceLoader resourceLoader) {
        this.semanticPacks = semanticPacks;
        this.presentationPacks = presentationPacks;
        this.authorisationBundles = authorisationBundles;
        this.authorisationModels = authorisationModels;
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
        validatePresentationPacks(semantic, semanticId, taxi);
    }

    private void validatePresentationPacks(Properties semantic, String semanticId, TaxiDocument taxi) {
        Set<String> ids = new HashSet<>();
        Set<String> authorisedFields = authorisationModels.stream()
                .flatMap(model -> model.fields().keySet().stream())
                .collect(Collectors.toSet());
        Set<String> facts = bindingNames(semantic, BindingKind.FACT);
        Set<String> actions = bindingNames(semantic, BindingKind.ACTION);
        Set<String> payloads = bindingNames(semantic, BindingKind.PAYLOAD);
        String semanticCompatibility = requireProperty(semantic, "Semantic Pack", "taxi-coordinate").split(":")[2];
        for (PresentationPack pack : presentationPacks) {
            String path = pack.manifestResource();
            Properties manifest = loadProperties(path);
            String id = qualified(manifest, "Presentation Pack", "id");
            if (!ids.add(id)) {
                throw new IllegalStateException("Duplicate Presentation Pack identity: " + id);
            }
            requireValue(manifest, "Presentation Pack", "format-version", SUPPORTED_FORMAT);
            requireValue(manifest, "Presentation Pack", "envelope-version", SUPPORTED_FORMAT);
            requireValue(manifest, "Presentation Pack", "semantic-pack", semanticId);
            requireValue(manifest, "Presentation Pack", "semantic-pack-compatibility", semanticCompatibility);
            verifyChecksum(path, optionalListProperty(manifest, "content"),
                    requireProperty(manifest, "Presentation Pack", "checksum"));

            Set<String> referencedActions = new HashSet<>();
            Set<String> referencedInputs = new HashSet<>();
            Set<String> referencedFields = new HashSet<>();
            Set<String> referencedFacts = new HashSet<>();
            Set<String> references = new HashSet<>();
            for (String declaration : listProperty(manifest, "Presentation Pack", "references")) {
                if (!references.add(declaration)) {
                    throw new IllegalStateException("Duplicate Presentation Pack reference: " + declaration);
                }
                String[] parts = declaration.split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("Malformed Presentation Pack reference: " + declaration);
                }
                switch (parts[0]) {
                    case "FIELD" -> {
                        requireReference(authorisedFields.contains(parts[1]),
                                "Presentation Pack references unauthorised field", parts[1]);
                        referencedFields.add(parts[1]);
                    }
                    case "FACT" -> {
                        requireReference(facts.contains(parts[1]),
                                "Presentation Pack references unknown Fact", parts[1]);
                        referencedFacts.add(parts[1]);
                    }
                    case "ACTION" -> {
                        requireReference(actions.contains(parts[1]), "Presentation Pack references unknown Action", parts[1]);
                        referencedActions.add(parts[1]);
                    }
                    case "INPUT" -> {
                        requireReference(payloads.contains(parts[1]), "Presentation Pack references unknown input", parts[1]);
                        referencedInputs.add(parts[1]);
                    }
                    default -> throw new IllegalStateException(
                            "Unknown Presentation Pack reference kind: " + parts[0]);
                }
            }
            Set<String> coverage = new HashSet<>();
            for (String action : listProperty(manifest, "Presentation Pack", "offer-coverage")) {
                if (!coverage.add(action)) {
                    throw new IllegalStateException("Duplicate Presentation Pack Action Offer: " + action);
                }
                requireReference(actions.contains(action), "Presentation Pack promises unknown Action Offer", action);
                requireReference(referencedActions.contains(action), "Presentation Pack omits promised Action Offer", action);
                String input = taxiInput(taxi, action);
                requireReference(referencedInputs.contains(input),
                        "Presentation Pack omits promised Action Offer input", input);
            }
            validateRenderedCoverage(pack, semanticId, coverage, referencedFields, referencedFacts, taxi);
        }
    }

    private void validateRenderedCoverage(
            PresentationPack pack,
            String semanticId,
            Set<String> coverage,
            Set<String> fields,
            Set<String> facts,
            TaxiDocument taxi) {
        List<PresentationActionOffer> offers = coverage.stream().sorted()
                .map(action -> new PresentationActionOffer(
                        UUID.nameUUIDFromBytes(action.getBytes(StandardCharsets.UTF_8)), action, taxiInput(taxi, action)))
                .toList();
        PresentationEnvelope envelope = new PresentationEnvelope(
                1,
                new Subject(authorisationModels.isEmpty() ? "validation.Subject"
                        : authorisationModels.getFirst().subjectType(), "validation"),
                new UUID(0, 0),
                Instant.EPOCH,
                semanticId,
                fields.stream().collect(Collectors.toMap(field -> field, field -> "")),
                facts.stream().sorted().map(fact -> new PresentationFact(fact, Map.of())).toList(),
                offers);
        try {
            Set<UUID> expected = offers.stream().map(PresentationActionOffer::id).collect(Collectors.toSet());
            if (!pack.render(envelope).renderedActionOffers().equals(expected)) {
                throw new IllegalStateException("Presentation Pack renderer omits or forges promised Action Offers");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Presentation Pack renderer rejected its declared references", exception);
        }
    }

    private static Set<String> bindingNames(Properties semantic, BindingKind kind) {
        return listProperty(semantic, "Semantic Pack", "bindings").stream()
                .map(binding -> binding.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(kind.name()))
                .map(parts -> parts[1])
                .collect(Collectors.toSet());
    }

    private static String taxiInput(TaxiDocument taxi, String action) {
        int separator = action.lastIndexOf('.');
        return taxi.service(action.substring(0, separator))
                .operation(action.substring(separator + 1))
                .getParameterType(0)
                .getQualifiedName();
    }

    private static void requireReference(boolean valid, String message, String reference) {
        if (!valid) throw new IllegalStateException(message + ": " + reference);
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
        bindings.get(BindingKind.ADAPTER).forEach(adapter -> validateAdapter(adapter, bindings));
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

    private static void validateAdapter(String adapter, Map<BindingKind, Set<String>> bindings) {
        var match = Pattern.compile("(PAYLOAD|EVENT):(.+)@(\\d+)->(\\d+)").matcher(adapter);
        if (!match.matches()
                || Integer.parseInt(match.group(3)) < 1
                || Integer.parseInt(match.group(4)) <= Integer.parseInt(match.group(3))
                || !bindings.get(BindingKind.valueOf(match.group(1))).contains(match.group(2))) {
            throw new IllegalStateException("Invalid Semantic Pack compatibility adapter: " + adapter);
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
        HANDLER,
        ADAPTER
    }
}
