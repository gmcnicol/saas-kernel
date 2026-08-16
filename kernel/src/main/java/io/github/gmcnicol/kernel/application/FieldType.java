package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.function.Function;

/** Generated, compile-time typed identity for one Taxi model field. */
public record FieldType<M, V>(String qualifiedName, Function<M, V> read) {

    public FieldType {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Field type requires qualified Taxi identity");
        }
        Objects.requireNonNull(read, "read");
    }

    public V value(M model) {
        return read.apply(Objects.requireNonNull(model, "model"));
    }
}
