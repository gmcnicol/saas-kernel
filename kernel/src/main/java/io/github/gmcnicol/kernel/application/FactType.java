package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.List;

/** Generated descriptor for one versioned Fact Taxi Java Binding. */
public record FactType<F>(
        String qualifiedName,
        int contractVersion,
        String contractFamily,
        ProjectionType<?, ?> projectionType,
        Class<F> javaType,
        List<FieldType<F, ?>> fields) implements SemanticType<F> {

    public FactType(
            String qualifiedName, int contractVersion, ProjectionType<?, ?> projectionType, Class<F> javaType) {
        this(qualifiedName, contractVersion, qualifiedName, projectionType, javaType, List.of());
    }

    public FactType(
            String qualifiedName, int contractVersion, ProjectionType<?, ?> projectionType,
            Class<F> javaType, List<FieldType<F, ?>> fields) {
        this(qualifiedName, contractVersion, qualifiedName, projectionType, javaType, fields);
    }

    public FactType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Fact type requires identity and positive contract version");
        }
        if (contractFamily == null || contractFamily.isBlank()) {
            throw new IllegalArgumentException("Fact type requires contract family");
        }
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(javaType, "javaType");
        fields = List.copyOf(fields);
    }

    public String name() {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
