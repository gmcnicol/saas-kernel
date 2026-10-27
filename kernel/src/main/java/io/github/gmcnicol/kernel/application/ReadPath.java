package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.function.Function;

/** Generated model-bound scalar leaf. Structured parents and collections are not output paths. */
public record ReadPath<P, V>(String qualifiedName, String path,
        Function<P, ReadValue<V>> read, Function<V, Object> scalar) {
    public ReadPath {
        Objects.requireNonNull(qualifiedName);
        Objects.requireNonNull(path);
        Objects.requireNonNull(read);
        Objects.requireNonNull(scalar);
    }
}
