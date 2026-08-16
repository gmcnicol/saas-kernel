package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import com.cedarpolicy.value.Value;

/** Generated, compile-time typed identity for one Taxi model field. */
public record FieldType<M, V>(
        String qualifiedName, Function<M, V> read, Function<V, Optional<Value>> cedarValue) {

    public FieldType(String qualifiedName, Function<M, V> read) {
        this(qualifiedName, read, value -> Optional.empty());
    }

    public FieldType {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Field type requires qualified Taxi identity");
        }
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(cedarValue, "cedarValue");
    }

    public V value(M model) {
        return read.apply(Objects.requireNonNull(model, "model"));
    }

    public String name() {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    public Optional<Value> toCedar(V value) {
        return Objects.requireNonNull(cedarValue.apply(Objects.requireNonNull(value, "value")), "Cedar value");
    }
}
