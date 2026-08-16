package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Generated descriptor for one versioned Fact Taxi Java Binding. */
public record FactType<F>(
        String qualifiedName,
        int contractVersion,
        ProjectionType<?, ?> projectionType,
        Class<F> javaType) implements SemanticType<F> {

    public FactType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Fact type requires identity and positive contract version");
        }
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(javaType, "javaType");
    }
}
