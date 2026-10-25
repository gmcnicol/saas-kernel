package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;

/** Generated identity and policy inputs for one versioned effective capability, not a workflow Fact. */
public record CapabilityType<C>(
        String qualifiedName, int contractVersion, Class<C> javaType,
        List<FieldType<C, ?>> fields) implements SemanticType<C> {
    public CapabilityType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Capability requires identity and positive contract version");
        }
        Objects.requireNonNull(javaType, "javaType");
        fields = List.copyOf(fields);
    }

    public String name() {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
