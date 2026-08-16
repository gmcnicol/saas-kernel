package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Exact durable contract for one generated Candidate Payload model. */
public record CandidateType<C>(String qualifiedName, int contractVersion, Class<C> javaType)
        implements SemanticType<C> {
    public CandidateType {
        if (qualifiedName == null || qualifiedName.isBlank() || contractVersion < 1) {
            throw new IllegalArgumentException("Candidate Payload descriptor requires identity and contract version");
        }
        Objects.requireNonNull(javaType, "javaType");
    }
}
