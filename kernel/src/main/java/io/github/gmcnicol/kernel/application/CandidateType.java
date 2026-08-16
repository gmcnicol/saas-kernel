package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;

/** Exact durable contract for one generated Candidate Payload model. */
public record CandidateType<C>(
        String qualifiedName, int contractVersion, String contractFamily,
        Class<C> javaType, List<FieldType<C, ?>> fields)
        implements SemanticType<C> {

    public CandidateType(String qualifiedName, int contractVersion, Class<C> javaType) {
        this(qualifiedName, contractVersion, qualifiedName, javaType, List.of());
    }

    public CandidateType(
            String qualifiedName, int contractVersion, Class<C> javaType, List<FieldType<C, ?>> fields) {
        this(qualifiedName, contractVersion, qualifiedName, javaType, fields);
    }

    public CandidateType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Candidate Payload descriptor requires identity and contract version");
        }
        if (contractFamily == null || contractFamily.isBlank()) {
            throw new IllegalArgumentException("Candidate Payload descriptor requires contract family");
        }
        Objects.requireNonNull(javaType, "javaType");
        fields = List.copyOf(fields);
    }
}
