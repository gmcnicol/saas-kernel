package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityAdapter;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityRequirement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed canonical codec prepared once from generated descriptors. */
final class SemanticCodec {
    private final Map<Key, SemanticType<?>> descriptors;
    private final Map<Key, List<Adapter<?, ?>>> adapters;
    private final CanonicalCodec canonical;

    SemanticCodec(List<SemanticBindings> bindings, CanonicalCodec.Limits limits) {
        this(bindings, List.of(), limits);
    }

    SemanticCodec(
            List<SemanticBindings> bindings,
            List<TypedCompatibilityAdapter<?, ?>> compatibilityAdapters,
            CanonicalCodec.Limits limits) {
        this(bindings, compatibilityAdapters, List.of(), limits);
    }

    SemanticCodec(
            List<SemanticBindings> bindings,
            List<TypedCompatibilityAdapter<?, ?>> compatibilityAdapters,
            List<TypedCompatibilityRequirement<?, ?>> compatibilityRequirements,
            CanonicalCodec.Limits limits) {
        var registered = new LinkedHashMap<Key, SemanticType<?>>();
        bindings.forEach(binding -> {
            binding.projections().forEach(type -> add(registered, type));
            binding.facts().forEach(type -> add(registered, type));
            binding.candidates().forEach(type -> add(registered, type));
            binding.events().forEach(type -> add(registered, type));
        });
        descriptors = Map.copyOf(registered);
        adapters = validateAdapters(compatibilityAdapters);
        validateRequirements(compatibilityRequirements);
        canonical = new CanonicalCodec(registered.values(), limits);
    }

    <T> CanonicalEvidence encode(SemanticType<T> type, T value) {
        requireRegistered(type);
        return canonical.encode(type, value);
    }

    <T> T decode(CanonicalEvidence evidence, SemanticType<T> type) {
        requireRegistered(type);
        Key target = Key.of(type);
        Key source = new Key(evidence.qualifiedType(), evidence.contractVersion());
        if (source.equals(target)) {
            return canonical.decode(type, evidence);
        }
        SemanticType<?> sourceType = descriptors.get(source);
        if (sourceType == null) {
            throw new IllegalArgumentException("Semantic evidence descriptor is not generated: " + source);
        }
        Object value = canonical.decode((SemanticType<Object>) sourceType, evidence);
        return adapt(value, source, target, type);
    }

    <T> T decode(SemanticType<T> type, CanonicalEvidence evidence) {
        return decode(evidence, type);
    }

    @SuppressWarnings("unchecked")
    <E> E decodeEvent(CanonicalEvidence evidence, List<EventType<? extends E>> eventTypes) {
        Key source = new Key(evidence.qualifiedType(), evidence.contractVersion());
        SemanticType<?> descriptor = descriptors.get(source);
        if (!(descriptor instanceof EventType<?>)) {
            throw new IllegalArgumentException("Event is outside generated Action contract");
        }
        Object sourceValue = canonical.decode((SemanticType<Object>) descriptor, evidence);
        var decoded = new ArrayList<E>();
        for (EventType<? extends E> eventType : eventTypes) {
            Key target = Key.of(eventType);
            if (source.equals(target)) {
                decoded.add(eventType.javaType().cast(sourceValue));
            } else {
                try {
                    decoded.add(adapt(sourceValue, source, target, (SemanticType<E>) eventType));
                } catch (IllegalStateException ignored) {
                    // Try the next generated event descriptor; exactly one current contract may accept this evidence.
                }
            }
        }
        if (decoded.size() != 1) {
            throw new IllegalArgumentException("Event is outside generated Action contract");
        }
        return decoded.getFirst();
    }

    void requireRegistered(SemanticType<?> type) {
        Objects.requireNonNull(type, "type");
        if (descriptors.get(Key.of(type)) != type) {
            throw new IllegalStateException("Semantic descriptor is not generated: " + Key.of(type));
        }
    }

    private static void add(Map<Key, SemanticType<?>> descriptors, SemanticType<?> type) {
        if (descriptors.putIfAbsent(Key.of(type), type) != null) {
            throw new IllegalStateException("Duplicate generated semantic descriptor: " + Key.of(type));
        }
    }

    private Map<Key, List<Adapter<?, ?>>> validateAdapters(List<TypedCompatibilityAdapter<?, ?>> definitions) {
        var graph = new LinkedHashMap<Key, List<Adapter<?, ?>>>();
        definitions.forEach(definition -> {
            requireRegistered(definition.source());
            requireRegistered(definition.target());
            graph.computeIfAbsent(Key.of(definition.source()), ignored -> new ArrayList<>())
                    .add(Adapter.of(definition));
        });
        graph.forEach((source, outgoing) -> {
            var uniqueTargets = new HashSet<Key>();
            outgoing.forEach(adapter -> {
                if (!uniqueTargets.add(Key.of(adapter.target))) {
                    throw new IllegalStateException("Ambiguous Semantic compatibility adapter: "
                            + source + " -> " + Key.of(adapter.target));
                }
            });
        });
        detectCycles(graph);
        var immutable = new LinkedHashMap<Key, List<Adapter<?, ?>>>();
        graph.forEach((source, outgoing) -> immutable.put(source, List.copyOf(outgoing)));
        return Map.copyOf(immutable);
    }

    private void validateRequirements(List<TypedCompatibilityRequirement<?, ?>> requirements) {
        requirements.forEach(requirement -> {
            requireRegistered(requirement.source());
            requireRegistered(requirement.target());
            assertOnePath(Key.of(requirement.source()), Key.of(requirement.target()));
        });
    }

    private void assertOnePath(Key source, Key target) {
        int paths = countPaths(source, target, new HashSet<>());
        if (paths == 0) {
            throw new IllegalStateException("Missing Semantic compatibility adapter: " + source + " -> " + target);
        }
        if (paths > 1) {
            throw new IllegalStateException("Ambiguous Semantic compatibility adapter chain: " + source + " -> " + target);
        }
    }

    private int countPaths(Key source, Key target, Set<Key> visited) {
        if (!visited.add(source)) return 0;
        int paths = 0;
        for (Adapter<?, ?> adapter : adapters.getOrDefault(source, List.of())) {
            Key next = Key.of(adapter.target);
            if (next.equals(target)) paths++;
            else paths += countPaths(next, target, visited);
        }
        visited.remove(source);
        return paths;
    }

    private void detectCycles(Map<Key, List<Adapter<?, ?>>> graph) {
        for (Key source : graph.keySet()) {
            detectCycles(source, source, graph, new HashSet<>());
        }
    }

    private void detectCycles(Key root, Key current, Map<Key, List<Adapter<?, ?>>> graph, Set<Key> path) {
        if (!path.add(current)) return;
        for (Adapter<?, ?> adapter : graph.getOrDefault(current, List.of())) {
            Key target = Key.of(adapter.target);
            if (target.equals(root)) {
                throw new IllegalStateException("Cyclic Semantic compatibility adapter: " + root);
            }
            detectCycles(root, target, graph, path);
        }
        path.remove(current);
    }

    @SuppressWarnings("unchecked")
    private <T> T adapt(Object value, Key source, Key target, SemanticType<T> targetType) {
        var queue = new ArrayDeque<Path>();
        queue.add(new Path(source, value, List.of(source)));
        Object result = null;
        int matches = 0;
        while (!queue.isEmpty()) {
            Path path = queue.removeFirst();
            for (Adapter<?, ?> adapter : adapters.getOrDefault(path.key, List.of())) {
                Key next = Key.of(adapter.target);
                if (path.visited.contains(next)) continue;
                Object adapted = adapter.adapt.apply(path.value);
                if (next.equals(target)) {
                    result = adapted;
                    matches++;
                    continue;
                }
                var visited = new ArrayList<>(path.visited);
                visited.add(next);
                queue.addLast(new Path(next, adapted, List.copyOf(visited)));
            }
        }
        if (matches == 0) {
            throw new IllegalStateException("Missing Semantic compatibility adapter: " + source + " -> " + target);
        }
        if (matches > 1) {
            throw new IllegalStateException("Ambiguous Semantic compatibility adapter chain: " + source + " -> " + target);
        }
        return targetType.javaType().cast(result);
    }

    private record Adapter<S, T>(
            SemanticType<S> source, SemanticType<T> target, java.util.function.Function<Object, Object> adapt) {
        @SuppressWarnings("unchecked")
        static <S, T> Adapter<S, T> of(TypedCompatibilityAdapter<S, T> definition) {
            return new Adapter<>(definition.source(), definition.target(), value -> definition.adapt().apply((S) value));
        }
    }

    private record Path(Key key, Object value, List<Key> visited) {}

    private record Key(String qualifiedName, int contractVersion) {
        static Key of(SemanticType<?> type) {
            return new Key(type.qualifiedName(), type.contractVersion());
        }
    }
}
