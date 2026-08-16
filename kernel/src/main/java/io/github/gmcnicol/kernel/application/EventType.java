package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Exact durable contract for one generated Event model. */
public record EventType<E>(String qualifiedName, int contractVersion, Class<E> javaType)
        implements SemanticType<E> {
    public EventType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Event descriptor requires identity and contract version");
        }
        Objects.requireNonNull(javaType, "javaType");
    }
}
