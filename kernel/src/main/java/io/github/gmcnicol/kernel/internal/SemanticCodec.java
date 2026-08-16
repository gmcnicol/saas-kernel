package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CanonicalEvidence;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed canonical codec prepared once from generated descriptors. */
final class SemanticCodec {
    private final Map<Key, SemanticType<?>> descriptors;
    private final CanonicalCodec canonical;

    SemanticCodec(List<SemanticBindings> bindings, CanonicalCodec.Limits limits) {
        var registered = new LinkedHashMap<Key, SemanticType<?>>();
        bindings.forEach(binding -> {
            binding.projections().forEach(type -> add(registered, type));
            binding.facts().forEach(type -> add(registered, type));
            binding.candidates().forEach(type -> add(registered, type));
            binding.events().forEach(type -> add(registered, type));
        });
        descriptors = Map.copyOf(registered);
        canonical = new CanonicalCodec(registered.values(), limits);
    }

    <T> CanonicalEvidence encode(SemanticType<T> type, T value) {
        requireRegistered(type);
        return canonical.encode(type, value);
    }

    <T> T decode(CanonicalEvidence evidence, SemanticType<T> type) {
        requireRegistered(type);
        return canonical.decode(type, evidence);
    }

    <T> T decode(SemanticType<T> type, CanonicalEvidence evidence) {
        return decode(evidence, type);
    }

    <E> E decodeEvent(CanonicalEvidence evidence, List<EventType<? extends E>> eventTypes) {
        SemanticType<?> descriptor = descriptors.get(new Key(evidence.qualifiedType(), evidence.contractVersion()));
        if (!(descriptor instanceof EventType<?>) || eventTypes.stream().noneMatch(type -> type == descriptor)) {
            throw new IllegalArgumentException("Event is outside generated Action contract");
        }
        return decodeEventUnchecked(evidence, descriptor);
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

    @SuppressWarnings("unchecked")
    private <E> E decodeEventUnchecked(CanonicalEvidence evidence, SemanticType<?> type) {
        return (E) decode(evidence, (SemanticType<Object>) type);
    }

    private record Key(String qualifiedName, int contractVersion) {
        static Key of(SemanticType<?> type) {
            return new Key(type.qualifiedName(), type.contractVersion());
        }
    }
}
