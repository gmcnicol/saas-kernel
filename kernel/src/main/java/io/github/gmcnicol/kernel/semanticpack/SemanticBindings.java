package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import java.util.List;

/** Generated inventory used to validate and prepare one typed Semantic Pack. */
public record SemanticBindings(
        List<ProjectionType<?, ?>> projections,
        List<FactType<?>> facts) {

    public SemanticBindings {
        projections = List.copyOf(projections);
        facts = List.copyOf(facts);
    }
}
