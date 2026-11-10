package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.*;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.*;

/** Immutable selected leaves only. No raw candidate, parent DTO, identity or skipped-count escape. */
final class AllowedRecord<P> implements AuthorisedRecord<P> {
    private final Map<ReadPath<P, ?>, ReadValue<?>> values;
    private final Map<String, Object> output;

    AllowedRecord(Map<ReadPath<P, ?>, ReadValue<?>> values) {
        this.values = Map.copyOf(values);
        var tree = new LinkedHashMap<String, Object>();
        values.forEach((path, value) -> {
            if (value.visible()) put(tree, path.path().split("\\."), 0, scalar(path, value.value()));
        });
        this.output = freeze(tree);
    }

    @SuppressWarnings("unchecked")
    public <V> ReadValue<V> get(ReadPath<P, V> path) {
        return (ReadValue<V>) values.getOrDefault(path, ReadValue.hidden());
    }

    @JsonValue private Map<String, Object> serialised() { return output; }
    @Override public String toString() { return output.toString(); }

    @SuppressWarnings("unchecked")
    private static Object scalar(ReadPath<?, ?> path, Object value) {
        return value == null ? null : ((ReadPath<Object, Object>) path).scalar().apply(value);
    }
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> target, String[] parts, int index, Object value) {
        if (index == parts.length - 1) target.put(parts[index], value);
        else put((Map<String, Object>) target.computeIfAbsent(parts[index], ignored -> new LinkedHashMap<>()), parts, index + 1, value);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Map<String, Object> tree) {
        var result = new LinkedHashMap<String, Object>();
        tree.forEach((key, value) -> result.put(key, value instanceof Map<?, ?> child ? freeze((Map<String, Object>) child) : value));
        return Collections.unmodifiableMap(result);
    }
}
