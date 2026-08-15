package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.ProjectedState;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public interface FactDerivation extends SemanticImplementation {

    String id();

    Derivation derive(ProjectedState state, Instant evaluatedAt);

    @Override
    default Kind kind() {
        return Kind.DERIVATION;
    }

    static FactDerivation of(
            String target, String id, BiFunction<ProjectedState, Instant, Derivation> implementation) {
        return new FactDerivation() {
            @Override public String target() { return target; }
            @Override public String id() { return id; }
            @Override public Derivation derive(ProjectedState state, Instant evaluatedAt) {
                return implementation.apply(state, evaluatedAt);
            }
        };
    }

    record Derivation(Optional<Map<String, String>> values, Optional<Instant> reevaluateAt) {

        public Derivation {
            values = values.map(Map::copyOf);
        }

        public static Derivation fact(Map<String, String> values) {
            return new Derivation(Optional.of(values), Optional.empty());
        }

        public static Derivation later(Instant reevaluateAt) {
            return new Derivation(Optional.empty(), Optional.of(reevaluateAt));
        }

        public static Derivation none() {
            return new Derivation(Optional.empty(), Optional.empty());
        }
    }
}
