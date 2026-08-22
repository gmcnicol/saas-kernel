package io.github.gmcnicol.kernel.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable heterogeneous Facts addressed only by generated descriptors. */
public final class FactSet {
    private final Map<FactType<?>, Object> values;

    private FactSet(Map<FactType<?>, ?> values) {
        var checked = new LinkedHashMap<FactType<?>, Object>();
        values.forEach((type, value) -> checked.put(
                Objects.requireNonNull(type, "type"), type.javaType().cast(Objects.requireNonNull(value, "value"))));
        this.values = Map.copyOf(checked);
    }

    public static FactSet of(Map<FactType<?>, ?> values) {
        return new FactSet(values);
    }

    public static FactSet empty() {
        return new FactSet(Map.of());
    }

    public <F> Optional<F> find(FactType<F> type) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(type, "type"))).map(type.javaType()::cast);
    }

    public int size() {
        return values.size();
    }
}
