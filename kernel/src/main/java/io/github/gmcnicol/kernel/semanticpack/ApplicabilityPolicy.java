package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.ProjectedState;
import java.util.List;
import java.util.function.BiPredicate;

public interface ApplicabilityPolicy extends SemanticImplementation {

    String id();

    boolean isApplicable(ProjectedState state, List<Fact> facts);

    @Override
    default Kind kind() {
        return Kind.APPLICABILITY;
    }

    static ApplicabilityPolicy of(
            String target, String id, BiPredicate<ProjectedState, List<Fact>> implementation) {
        return new ApplicabilityPolicy() {
            @Override public String target() { return target; }
            @Override public String id() { return id; }
            @Override public boolean isApplicable(ProjectedState state, List<Fact> facts) {
                return implementation.test(state, facts);
            }
        };
    }
}
