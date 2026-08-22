package io.github.gmcnicol.kernel.application;

import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Closed generated allowlist for decoding one Candidate at the transport boundary. */
public final class SemanticRegistry {
    private static final int MAX_FORM_BYTES = 65_536;
    private static final CanonicalCodec.Limits LIMITS = CanonicalCodec.Limits.defaults();
    private final Map<String, ActionType<?, ?, ?>> actions;
    private final JsonMapper json;
    private final Map<String, ObjectReader> readers;
    private final Map<CandidateType<?>, FormDecoder<?>> formDecoders;

    private SemanticRegistry(SemanticBindings bindings, List<FormDecoder<?>> decoders) {
        var registered = new HashMap<String, ActionType<?, ?, ?>>();
        this.json = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder()
                                .maxDocumentLength(MAX_FORM_BYTES)
                                .maxNestingDepth(32)
                                .maxStringLength(MAX_FORM_BYTES)
                                .build())
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        var generatedReaders = new HashMap<String, ObjectReader>();
        bindings.actions().forEach(action -> {
            if (registered.putIfAbsent(action.qualifiedName(), action) != null) {
                throw new IllegalArgumentException("Duplicate generated Action: " + action.qualifiedName());
            }
            generatedReaders.put(action.qualifiedName(), json.readerFor(action.candidateType().javaType()));
        });
        this.actions = Map.copyOf(registered);
        this.readers = Map.copyOf(generatedReaders);
        var generatedDecoders = new HashMap<CandidateType<?>, FormDecoder<?>>();
        decoders.forEach(decoder -> {
            if (!bindings.candidates().contains(decoder.type())
                    || generatedDecoders.putIfAbsent(decoder.type(), decoder) != null) {
                throw new IllegalArgumentException("Invalid generated Candidate form decoder");
            }
        });
        this.formDecoders = Map.copyOf(generatedDecoders);
    }

    public static SemanticRegistry generated(SemanticBindings bindings) {
        return new SemanticRegistry(bindings, List.of());
    }

    public static SemanticRegistry generated(SemanticBindings bindings, List<FormDecoder<?>> decoders) {
        return new SemanticRegistry(bindings, List.copyOf(decoders));
    }

    public static <C> FormDecoder<C> formDecoder(CandidateType<C> type, Function<Form, C> decode) {
        return new Decoder<>(Objects.requireNonNull(type, "type"), Objects.requireNonNull(decode, "decode"));
    }

    public TypedCandidatePayload<?> decodeForm(
            String actionType,
            String type,
            int version,
            Map<String, ? extends List<String>> form,
            Set<String> transportFields,
            Optional<W3cTraceContext> traceContext,
            Optional<UUID> priorIntentId) {
        if (actionType == null || type == null || form == null || transportFields == null
                || traceContext == null || priorIntentId == null) {
            throw invalid();
        }
        ActionType<?, ?, ?> action = action(actionType, type, version);
        if (encodedSize(type, form) > MAX_FORM_BYTES) throw invalid();
        form.values().forEach(values -> {
            if (values == null || values.size() > LIMITS.collectionElements()) throw invalid();
        });
        try {
            FormDecoder<?> decoder = Optional.ofNullable(formDecoders.get(action.candidateType()))
                    .orElseThrow(SemanticRegistry::invalid);
            Object value = decoder.decode(new Form(action.candidateType(), form, transportFields));
            return payload(action, value, traceContext, priorIntentId);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public TypedCandidatePayload<?> decodeJson(
            String actionType,
            String type,
            int version,
            byte[] content,
            Optional<W3cTraceContext> traceContext,
            Optional<UUID> priorIntentId) {
        if (actionType == null || type == null || content == null || traceContext == null || priorIntentId == null
                || content.length > MAX_FORM_BYTES) {
            throw invalid();
        }
        ActionType<?, ?, ?> action = action(actionType, type, version);
        try {
            JsonNode tree = json.readTree(content);
            validateShape(tree);
            return payload(action, readers.get(actionType).readValue(json.treeAsTokens(tree)),
                    traceContext, priorIntentId);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private ActionType<?, ?, ?> action(String actionType, String type, int version) {
        ActionType<?, ?, ?> action = actions.get(actionType);
        if (action == null || !action.candidateType().qualifiedName().equals(type)
                || action.candidateType().contractVersion() != version) throw invalid();
        return action;
    }

    @SuppressWarnings("unchecked")
    private static TypedCandidatePayload<?> payload(
            ActionType<?, ?, ?> action,
            Object value,
            Optional<W3cTraceContext> traceContext,
            Optional<UUID> priorIntentId) {
        ActionType<?, Object, ?> typed = (ActionType<?, Object, ?>) action;
        return new TypedCandidatePayload<>(typed, value, traceContext, priorIntentId);
    }

    private static int encodedSize(String type, Map<String, ? extends List<String>> form) {
        long bytes = type.getBytes(StandardCharsets.UTF_8).length;
        for (var entry : form.entrySet()) {
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            for (String value : entry.getValue()) {
                if (value == null) throw invalid();
                bytes += value.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_FORM_BYTES) return MAX_FORM_BYTES + 1;
            }
        }
        return (int) bytes;
    }

    private static void validateShape(JsonNode node) {
        if (node.isArray() && node.size() > LIMITS.collectionElements()) throw invalid();
        if (node.isObject() && node.size() > LIMITS.fields()) throw invalid();
        node.forEach(SemanticRegistry::validateShape);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid generated Candidate Payload");
    }

    public interface FormDecoder<C> {
        CandidateType<C> type();
        C decode(Form form);
    }

    public static final class Form {
        private final Map<String, ? extends List<String>> values;

        private Form(
                CandidateType<?> type,
                Map<String, ? extends List<String>> values,
                Set<String> transportFields) {
            this.values = values;
            Set<String> expected = type.fields().stream().map(FieldType::name)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> supplied = new HashSet<>(values.keySet());
            supplied.removeAll(transportFields);
            if (!expected.containsAll(supplied)) throw invalid();
        }

        public <T> T required(String name, Function<String, T> parse) {
            List<String> field = values.get(name);
            if (field == null || field.size() != 1) throw invalid();
            return parsed(parse, field.getFirst());
        }

        public <T> Optional<T> optional(String name, Function<String, T> parse) {
            List<String> field = values.get(name);
            if (field == null) return Optional.empty();
            if (field.size() != 1) throw invalid();
            return Optional.of(parsed(parse, field.getFirst()));
        }

        public <T> List<T> list(String name, Function<String, T> parse) {
            List<String> field = values.get(name);
            if (field == null || field.isEmpty()) throw invalid();
            return field.stream().map(value -> parsed(parse, value)).toList();
        }

        public <T> Optional<List<T>> optionalList(String name, Function<String, T> parse) {
            List<String> field = values.get(name);
            return field == null ? Optional.empty() : Optional.of(list(name, parse));
        }

        private static <T> T parsed(Function<String, T> parse, String value) {
            if (value == null) throw invalid();
            try {
                return Objects.requireNonNull(parse.apply(value), "parsed Candidate field");
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
    }

    private record Decoder<C>(CandidateType<C> type, Function<Form, C> factory) implements FormDecoder<C> {
        @Override public C decode(Form form) {
            return factory.apply(form);
        }
    }
}
