package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** One Cedar-authorised generated Fact value. */
public record TypedFact<F>(FactType<F> type, F value) {
    public TypedFact {
        Objects.requireNonNull(type, "type");
        type.javaType().cast(Objects.requireNonNull(value, "value"));
    }
}
