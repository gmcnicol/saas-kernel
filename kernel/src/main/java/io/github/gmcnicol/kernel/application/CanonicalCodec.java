package io.github.gmcnicol.kernel.application;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Hardened canonical JSON codec restricted to generated semantic descriptors. */
public final class CanonicalCodec {
    private static final int FORMAT_VERSION = 1;

    private final Limits limits;
    private final JsonMapper mapper;
    private final Map<Key, SemanticType<?>> types;
    private final Map<Key, ObjectReader> readers;
    private final Map<Key, ObjectWriter> writers;

    public CanonicalCodec(Collection<? extends SemanticType<?>> types) {
        this(types, Limits.defaults());
    }

    public CanonicalCodec(Collection<? extends SemanticType<?>> types, Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        var constraints = StreamReadConstraints.builder()
                .maxDocumentLength(limits.bytes())
                .maxNestingDepth(limits.depth())
                .maxStringLength(limits.stringLength())
                .build();
        var factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
                .build();
        this.types = new HashMap<>();
        this.readers = new HashMap<>();
        this.writers = new HashMap<>();
        for (SemanticType<?> type : types) {
            Key key = Key.of(type);
            if (this.types.putIfAbsent(key, type) != null) {
                throw new IllegalArgumentException("Duplicate semantic descriptor: " + key);
            }
            readers.put(key, mapper.readerFor(type.javaType()));
            writers.put(key, mapper.writerFor(type.javaType()));
        }
    }

    public <T> CanonicalEvidence encode(SemanticType<T> type, T value) {
        Key key = registered(type);
        try {
            JsonNode tree = mapper.readTree(writers.get(key).writeValueAsBytes(
                    type.javaType().cast(Objects.requireNonNull(value, "value"))));
            JsonNode canonical = canonical(tree, 1);
            byte[] content = mapper.writeValueAsBytes(canonical);
            if (content.length > limits.bytes()) throw invalid("byte limit");
            return new CanonicalEvidence(
                    type.qualifiedName(), type.contractVersion(), FORMAT_VERSION, content, sha256(content));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException(
                    "Semantic value cannot be canonically encoded: " + exception.getMessage(), exception);
        }
    }

    public <T> T decode(SemanticType<T> type, CanonicalEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        byte[] content = evidence.canonicalUtf8();
        if (!MessageDigest.isEqual(
                sha256(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                evidence.checksum().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Semantic evidence checksum mismatch");
        }
        Key key = registered(type);
        if (!key.equals(new Key(evidence.qualifiedType(), evidence.contractVersion()))
                || evidence.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Semantic evidence descriptor mismatch");
        }
        if (content.length > limits.bytes()) throw invalid("byte limit");
        try {
            JsonNode tree = mapper.readTree(content);
            byte[] normalised = mapper.writeValueAsBytes(canonical(tree, 1));
            if (!Arrays.equals(content, normalised)) {
                throw new IllegalArgumentException("Semantic evidence is not canonical JSON");
            }
            return Objects.requireNonNull(
                    readers.get(key).readValue(mapper.treeAsTokens(tree)), "Semantic evidence value");
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("Semantic evidence is malformed", exception);
        }
    }

    private Key registered(SemanticType<?> type) {
        Objects.requireNonNull(type, "type");
        Key key = Key.of(type);
        if (types.get(key) != type) {
            throw new IllegalArgumentException("Semantic descriptor is not registered: " + key);
        }
        return key;
    }

    private JsonNode canonical(JsonNode node, int depth) {
        if (depth > limits.depth()) throw invalid("depth limit");
        if (node.isObject()) {
            if (node.size() > limits.fields()) throw invalid("field limit");
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue(), depth + 1)));
            return JsonNodeFactory.instance.objectNode().setAll(sorted);
        }
        if (node.isArray()) {
            if (node.size() > limits.collectionElements()) throw invalid("collection limit");
            var array = JsonNodeFactory.instance.arrayNode(node.size());
            node.forEach(value -> array.add(canonical(value, depth + 1)));
            return array;
        }
        if (node.isTextual() && node.textValue().length() > limits.stringLength()) throw invalid("string limit");
        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) throw invalid("non-finite number");
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            if (decimal.signum() == 0) decimal = BigDecimal.ZERO;
            return JsonNodeFactory.instance.numberNode(decimal);
        }
        return node;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Semantic evidence exceeds " + reason);
    }

    static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Key(String qualifiedName, int contractVersion) {
        static Key of(SemanticType<?> type) {
            return new Key(type.qualifiedName(), type.contractVersion());
        }
    }

    public record Limits(int bytes, int depth, int fields, int collectionElements, int stringLength) {
        private static final Limits MAXIMUM = new Limits(1_048_576, 32, 256, 10_000, 65_536);

        public Limits {
            validatePositive(bytes, depth, fields, collectionElements, stringLength);
            if (bytes > 1_048_576 || depth > 32 || fields > 256
                    || collectionElements > 10_000 || stringLength > 65_536) {
                throw new IllegalArgumentException("Canonical codec limits may only be tightened");
            }
        }

        public static Limits defaults() {
            return MAXIMUM;
        }

        public Limits tightened(int bytes, int depth, int fields, int collectionElements, int stringLength) {
            validatePositive(bytes, depth, fields, collectionElements, stringLength);
            return new Limits(bytes, depth, fields, collectionElements, stringLength);
        }

        private static void validatePositive(int... values) {
            if (Arrays.stream(values).anyMatch(value -> value < 1)) {
                throw new IllegalArgumentException("Canonical codec limits must be positive");
            }
        }
    }
}
