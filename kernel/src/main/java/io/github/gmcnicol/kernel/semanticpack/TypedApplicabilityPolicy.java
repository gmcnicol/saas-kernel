package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import java.time.Instant;
import java.util.Optional;

/** Principal-independent applicability over one generated Projection type. */
public interface TypedApplicabilityPolicy<P> {

    ActionType<P, ?, ?> actionType();

    default ProjectionType<?, P> projectionType() {
        return actionType().projectionType();
    }

    boolean isApplicable(P projection, FactSet facts);

    default Optional<Instant> nextChange(P projection, FactSet facts, Instant evaluatedAt) {
        return Optional.empty();
    }
}
