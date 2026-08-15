package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.ProjectedState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

public interface ApplicabilityPolicy extends SemanticImplementation {

    String id();

    boolean isApplicable(ProjectedState state, List<Fact> facts);

    default Optional<Instant> nextChange(ProjectedState state, List<Fact> facts, Instant evaluatedAt) {
        return Optional.empty();
    }

    @Override
    default Kind kind() {
        return Kind.APPLICABILITY;
    }

    static ApplicabilityPolicy of(
            String target, String id, BiPredicate<ProjectedState, List<Fact>> implementation) {
        return of(target, id, implementation, (state, facts, evaluatedAt) -> Optional.empty());
    }

    static ApplicabilityPolicy of(
            String target,
            String id,
            BiPredicate<ProjectedState, List<Fact>> implementation,
            NextChange nextChange) {
        return new ApplicabilityPolicy() {
            @Override public String target() { return target; }
            @Override public String id() { return id; }
            @Override public boolean isApplicable(ProjectedState state, List<Fact> facts) {
                return implementation.test(state, facts);
            }
            @Override public Optional<Instant> nextChange(
                    ProjectedState state, List<Fact> facts, Instant evaluatedAt) {
                return nextChange.at(state, facts, evaluatedAt);
            }
        };
    }

    @FunctionalInterface
    interface NextChange {
        Optional<Instant> at(ProjectedState state, List<Fact> facts, Instant evaluatedAt);
    }
}
