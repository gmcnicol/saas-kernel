package io.github.gmcnicol.kernel.semanticpack;

import java.util.Objects;

/** One Application-owned implementation bound to a qualified Taxi target. */
public record SemanticImplementation(Kind kind, String target) {

    public SemanticImplementation {
        Objects.requireNonNull(kind, "kind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
    }

    public enum Kind {
        DERIVATION,
        APPLICABILITY,
        HANDLER
    }
}
