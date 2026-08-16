package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;

/** Exact durable contract for one generated Event model. */
public record EventType<E>(
        String qualifiedName, int contractVersion,
        Class<E> javaType, List<FieldType<E, ?>> fields)
        implements SemanticType<E> {
    public EventType(String qualifiedName, int contractVersion, Class<E> javaType) {
        this(qualifiedName, contractVersion, javaType, List.of());
    }

    public EventType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Event descriptor requires identity and contract version");
        }
        Objects.requireNonNull(javaType, "javaType");
        fields = List.copyOf(fields);
    }
}
