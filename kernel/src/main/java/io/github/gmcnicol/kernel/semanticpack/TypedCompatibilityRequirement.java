package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.SemanticType;
import java.util.Objects;

/** Declares that one historical generated semantic contract must adapt to one current contract before readiness. */
public record TypedCompatibilityRequirement<S, T>(SemanticType<S> source, SemanticType<T> target) {
    public TypedCompatibilityRequirement {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) throw new IllegalArgumentException("Compatibility requirement source and target must differ");
    }
}
