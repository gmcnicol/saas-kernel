package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.application.TypedFact;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.LegacySemanticDecoder;
import io.github.gmcnicol.kernel.semanticpack.TypedSemanticAdapter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact historical decode followed by one validated forward adapter chain. */
final class TypedSemanticCompatibility {
    private final Map<Key, SemanticType<?>> descriptors;
    private final Map<Key, TypedSemanticAdapter<?, ?>> adapters;
    private final Map<Key, LegacySemanticDecoder<?>> legacyDecoders;
    private final CanonicalCodec canonical;
    private final CanonicalCodec.Limits limits;

    TypedSemanticCompatibility(
            List<SemanticBindings> bindings,
            List<TypedSemanticAdapter<?, ?>> adapters,
            CanonicalCodec.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        var registered = new LinkedHashMap<Key, SemanticType<?>>();
        bindings.forEach(binding -> {
            binding.projections().forEach(type -> add(registered, type));
            binding.facts().forEach(type -> add(registered, type));
            binding.candidates().forEach(type -> add(registered, type));
            binding.events().forEach(type -> add(registered, type));
        });
        var bySource = new LinkedHashMap<Key, TypedSemanticAdapter<?, ?>>();
        adapters.forEach(adapter -> {
            requireRegistered(registered, adapter.source());
            requireRegistered(registered, adapter.target());
            requireCompatible(adapter.source(), adapter.target());
            if (bySource.putIfAbsent(Key.of(adapter.source()), adapter) != null) {
                throw new IllegalStateException("Ambiguous typed semantic adapter source: " + Key.of(adapter.source()));
            }
        });
        this.descriptors = Map.copyOf(registered);
        this.adapters = Map.copyOf(bySource);
        var decoders = new LinkedHashMap<Key, LegacySemanticDecoder<?>>();
        bindings.stream().flatMap(binding -> binding.legacyDecoders().stream()).forEach(decoder -> {
            requireRegistered(registered, decoder.type());
            if (decoders.putIfAbsent(Key.of(decoder.type()), decoder) != null) {
                throw new IllegalStateException("Duplicate legacy semantic decoder: " + Key.of(decoder.type()));
            }
        });
        this.legacyDecoders = Map.copyOf(decoders);
        validateChains();
        this.canonical = new CanonicalCodec(registered.values(), limits);
    }

    <T> CanonicalEvidence encode(SemanticType<T> type, T value) {
        requireRegistered(descriptors, type);
        return canonical.encode(type, value);
    }

    <T> T decode(CanonicalEvidence evidence, SemanticType<T> target) {
        requireRegistered(descriptors, target);
        SemanticType<?> source = descriptors.get(new Key(evidence.qualifiedType(), evidence.contractVersion()));
        if (source == null) throw new IllegalArgumentException("Unknown historical semantic descriptor");
        return adaptTo(source, decodeExact(source, evidence), target);
    }

    <T> T decode(SemanticType<T> target, CanonicalEvidence evidence) {
        return decode(evidence, target);
    }

    <T> T decodeLegacy(SemanticType<?> source, Map<String, String> values, SemanticType<T> target) {
        requireRegistered(descriptors, source);
        requireRegistered(descriptors, target);
        LegacySemanticDecoder<?> decoder = legacyDecoders.get(Key.of(source));
        if (decoder == null) throw new IllegalArgumentException("Missing generated legacy semantic decoder");
        return adaptTo(source, decoder.decode(values, limits), target);
    }

    private <T> T adaptTo(SemanticType<?> source, Object value, SemanticType<T> target) {
        SemanticType<?> current = source;
        while (current != target) {
            TypedSemanticAdapter<?, ?> adapter = adapters.get(Key.of(current));
            if (adapter == null) throw new IllegalArgumentException("Missing typed semantic adapter path");
            value = adapt(adapter, value);
            current = adapter.target();
        }
        return target.javaType().cast(value);
    }

    <T> T decodeLegacyProjection(Map<String, String> values, ProjectionType<?, T> target) {
        requireRegistered(descriptors, target);
        SemanticType<?> source = descriptors.values().stream()
                .filter(ProjectionType.class::isInstance)
                .filter(type -> type.contractFamily().equals(target.contractFamily()))
                .min(java.util.Comparator.comparingInt(SemanticType::contractVersion))
                .orElseThrow(() -> new IllegalArgumentException("Unknown legacy Projection contract"));
        return decodeLegacy(source, values, target);
    }

    Object decodeLegacy(String qualifiedName, int contractVersion, Map<String, String> values) {
        SemanticType<?> source = descriptors.get(new Key(qualifiedName, contractVersion));
        if (source == null) throw new IllegalArgumentException("Unknown historical semantic descriptor");
        return decodeLegacyUnchecked(source, values, current(source));
    }

    <T> T decodeLegacy(
            String qualifiedName, int contractVersion, Map<String, String> values, SemanticType<T> target) {
        SemanticType<?> source = descriptors.get(new Key(qualifiedName, contractVersion));
        if (source == null) throw new IllegalArgumentException("Unknown historical semantic descriptor");
        return decodeLegacy(source, values, target);
    }

    <E> E decodeEvent(CanonicalEvidence evidence, List<io.github.gmcnicol.kernel.application.EventType<? extends E>> targets) {
        SemanticType<?> source = descriptors.get(new Key(evidence.qualifiedType(), evidence.contractVersion()));
        if (!(source instanceof io.github.gmcnicol.kernel.application.EventType<?>)) {
            throw new IllegalArgumentException("Unknown historical Event descriptor");
        }
        return decodeEventTarget(source, targets, target -> decodeUnchecked(evidence, target));
    }

    <E> E decodeLegacyEvent(
            String qualifiedName,
            int contractVersion,
            Map<String, String> values,
            List<io.github.gmcnicol.kernel.application.EventType<? extends E>> targets) {
        SemanticType<?> source = descriptors.get(new Key(qualifiedName, contractVersion));
        if (!(source instanceof io.github.gmcnicol.kernel.application.EventType<?>)) {
            throw new IllegalArgumentException("Unknown historical Event descriptor");
        }
        return decodeEventTarget(source, targets, target -> decodeLegacyUnchecked(source, values, target));
    }

    private <E> E decodeEventTarget(
            SemanticType<?> source,
            List<io.github.gmcnicol.kernel.application.EventType<? extends E>> targets,
            java.util.function.Function<SemanticType<?>, Object> decoder) {
        SemanticType<?> target = current(source);
        if (targets.stream().noneMatch(candidate -> candidate == target)) {
            throw new IllegalArgumentException("Event is outside generated Action contract");
        }
        @SuppressWarnings("unchecked") E value = (E) decoder.apply(target);
        return value;
    }

    TypedFact<?> decodeLegacyFact(String qualifiedName, Map<String, String> values) {
        SemanticType<?> source = descriptors.values().stream()
                .filter(FactType.class::isInstance)
                .filter(type -> type.qualifiedName().equals(qualifiedName))
                .min(java.util.Comparator.comparingInt(SemanticType::contractVersion))
                .orElseThrow(() -> new IllegalArgumentException("Unknown legacy Fact contract"));
        FactType<?> target = (FactType<?>) current(source);
        return typedFact(target, decodeLegacyUnchecked(source, values, target));
    }

    void requireLegacyCollectionSize(int size) {
        if (size > limits.collectionElements()) {
            throw new IllegalArgumentException("Semantic evidence exceeds collection limit");
        }
    }

    void requireCurrent(SemanticType<?> type) {
        requireRegistered(descriptors, type);
        if (adapters.containsKey(Key.of(type))) {
            throw new IllegalArgumentException("New durable evidence must use the current semantic contract");
        }
    }

    boolean isCurrent(SemanticType<?> type) {
        requireRegistered(descriptors, type);
        return !adapters.containsKey(Key.of(type));
    }

    SemanticType<?> current(SemanticType<?> type) {
        requireRegistered(descriptors, type);
        SemanticType<?> current = type;
        while (adapters.containsKey(Key.of(current))) current = adapters.get(Key.of(current)).target();
        return current;
    }

    private void validateChains() {
        var versionedRoles = new HashMap<String, java.util.ArrayList<SemanticType<?>>>();
        descriptors.values().forEach(type ->
                versionedRoles.computeIfAbsent(role(type).getName() + ":" + type.contractFamily(),
                        ignored -> new java.util.ArrayList<>()).add(type));
        versionedRoles.forEach((name, types) -> {
            if (types.size() < 2) return;
            List<SemanticType<?>> current = types.stream()
                    .filter(type -> !adapters.containsKey(Key.of(type))).toList();
            if (current.size() != 1) throw new IllegalStateException("Durable role requires one current contract: " + name);
            types.forEach(source -> path(source, current.getFirst()));
        });
        adapters.keySet().forEach(source -> path(descriptors.get(source), null));
    }

    private void path(SemanticType<?> source, SemanticType<?> expectedTarget) {
        var seen = new java.util.HashSet<Key>();
        SemanticType<?> current = source;
        while (adapters.containsKey(Key.of(current))) {
            if (!seen.add(Key.of(current))) throw new IllegalStateException("Cyclic typed semantic adapter path");
            current = adapters.get(Key.of(current)).target();
        }
        if (expectedTarget != null && current != expectedTarget) {
            throw new IllegalStateException("Missing typed semantic adapter path from " + Key.of(source));
        }
    }

    private static void requireCompatible(SemanticType<?> source, SemanticType<?> target) {
        if (target.contractVersion() <= source.contractVersion()
                || !role(source).equals(role(target))
                || !source.contractFamily().equals(target.contractFamily())
                || source instanceof io.github.gmcnicol.kernel.application.ProjectionType<?, ?> left
                && target instanceof io.github.gmcnicol.kernel.application.ProjectionType<?, ?> right
                && left.subjectType() != right.subjectType()
                || source instanceof FactType<?> left && target instanceof FactType<?> right
                && !left.projectionType().contractFamily().equals(right.projectionType().contractFamily())) {
            throw new IllegalStateException("Typed semantic adapter roles do not match");
        }
    }

    private static Class<?> role(SemanticType<?> type) {
        for (Class<?> role : List.of(
                io.github.gmcnicol.kernel.application.ProjectionType.class,
                io.github.gmcnicol.kernel.application.FactType.class,
                io.github.gmcnicol.kernel.application.CandidateType.class,
                io.github.gmcnicol.kernel.application.EventType.class)) {
            if (role.isInstance(type)) return role;
        }
        throw new IllegalStateException("Unsupported durable semantic descriptor");
    }

    private static void add(Map<Key, SemanticType<?>> descriptors, SemanticType<?> type) {
        if (descriptors.putIfAbsent(Key.of(type), type) != null) {
            throw new IllegalStateException("Duplicate generated semantic descriptor: " + Key.of(type));
        }
    }

    private static void requireRegistered(Map<Key, SemanticType<?>> descriptors, SemanticType<?> type) {
        Objects.requireNonNull(type, "type");
        if (descriptors.get(Key.of(type)) != type) {
            throw new IllegalStateException("Typed semantic adapter descriptor is not generated: " + Key.of(type));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T decodeExact(SemanticType<T> type, CanonicalEvidence evidence) {
        return canonical.decode(type, evidence);
    }

    @SuppressWarnings("unchecked")
    private Object decodeUnchecked(CanonicalEvidence evidence, SemanticType<?> target) {
        return decode(evidence, (SemanticType<Object>) target);
    }

    @SuppressWarnings("unchecked")
    private static Object adapt(TypedSemanticAdapter<?, ?> adapter, Object value) {
        return ((TypedSemanticAdapter<Object, Object>) adapter).adapt(value);
    }

    @SuppressWarnings("unchecked")
    private Object decodeLegacyUnchecked(
            SemanticType<?> source, Map<String, String> values, SemanticType<?> target) {
        return decodeLegacy(source, values, (SemanticType<Object>) target);
    }

    @SuppressWarnings("unchecked")
    private static TypedFact<?> typedFact(FactType<?> type, Object value) {
        return new TypedFact<>((FactType<Object>) type, value);
    }

    private record Key(String qualifiedName, int contractVersion) {
        static Key of(SemanticType<?> type) { return new Key(type.qualifiedName(), type.contractVersion()); }
    }
}
