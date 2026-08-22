package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.FactType;
import java.time.Instant;
import java.util.Optional;

/** Compile-time typed Fact derivation bound through a generated slot. */
public interface TypedFactDerivation<P, F> {

    FactType<F> factType();

    String id();

    Result<F> derive(P projection, Instant evaluatedAt);

    record Result<F>(Optional<F> value, Optional<Instant> reevaluateAt) {

        public Result {
            value = value.map(java.util.Objects::requireNonNull);
            java.util.Objects.requireNonNull(reevaluateAt, "reevaluateAt");
        }

        public static <F> Result<F> fact(F value) {
            return new Result<>(Optional.of(value), Optional.empty());
        }

        public static <F> Result<F> later(Instant reevaluateAt) {
            return new Result<>(Optional.empty(), Optional.of(reevaluateAt));
        }

        public static <F> Result<F> none() {
            return new Result<>(Optional.empty(), Optional.empty());
        }
    }
}
