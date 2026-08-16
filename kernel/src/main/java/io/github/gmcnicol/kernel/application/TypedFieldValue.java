package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** One Cedar-authorised value identified by its generated field descriptor. */
public record TypedFieldValue<M, V>(FieldType<M, V> type, V value) {
    public TypedFieldValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
