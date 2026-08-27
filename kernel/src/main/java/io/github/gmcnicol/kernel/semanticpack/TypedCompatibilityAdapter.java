package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.SemanticType;
import java.util.Objects;
import java.util.function.Function;

/** Explicit Application-owned adapter between two generated durable semantic contracts. */
public record TypedCompatibilityAdapter<S, T>(
        SemanticType<S> source,
        SemanticType<T> target,
        Function<S, T> adapt) {
    public TypedCompatibilityAdapter {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(adapt, "adapt");
        if (source == target) throw new IllegalArgumentException("Compatibility adapter source and target must differ");
    }
}
