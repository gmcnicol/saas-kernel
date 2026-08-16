package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.FactType;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiFunction;

/** Generated Fact implementation slot binding an ordinary Application bean. */
public record FactDerivationSlot<P, F>(FactType<F> factType) {

    public FactDerivationSlot {
        Objects.requireNonNull(factType, "factType");
    }

    public String id() {
        return factType.qualifiedName() + ".derivation";
    }

    public TypedFactDerivation<P, F> bind(BiFunction<P, Instant, TypedFactDerivation.Result<F>> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        return new TypedFactDerivation<>() {
            @Override public FactType<F> factType() {
                return factType;
            }

            @Override public String id() {
                return FactDerivationSlot.this.id();
            }

            @Override public Result<F> derive(P projection, Instant evaluatedAt) {
                return implementation.apply(projection, evaluatedAt);
            }
        };
    }
}
