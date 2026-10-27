package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;

/** Generated descriptor for one versioned Projected State Taxi Java Binding. */
public record ProjectionType<I, P>(
        String qualifiedName,
        int contractVersion,
        SubjectType<I> subjectType,
        Class<P> javaType,
        List<FieldType<P, ?>> fields,
        List<ReadPath<P, ?>> readPaths) implements SemanticType<P> {

    public ProjectionType(String qualifiedName, int contractVersion, SubjectType<I> subjectType,
            Class<P> javaType, List<FieldType<P, ?>> fields) {
        this(qualifiedName, contractVersion, subjectType, javaType, fields, List.of());
    }

    public ProjectionType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Projection type requires identity and positive contract version");
        }
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(javaType, "javaType");
        fields = List.copyOf(fields);
        readPaths = List.copyOf(readPaths);
    }
}
