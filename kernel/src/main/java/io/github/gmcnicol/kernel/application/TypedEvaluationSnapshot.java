package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable typed evaluation result for one exact Projection contract. */
public record TypedEvaluationSnapshot<I, P>(
        UUID id,
        String tenantId,
        TypedSubject<I> subject,
        long projectedStateVersion,
        ProjectionType<I, P> projectionType,
        String projectedStateChecksum,
        Instant evaluatedAt,
        ApplicationVersion applicationVersion,
        String kernelVersion,
        SemanticPackVersion semanticPackVersion,
        FactSet facts,
        List<ApplicableAction> applicableActions,
        Optional<Instant> reevaluateAt) {

    public TypedEvaluationSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(facts, "facts");
        applicableActions = List.copyOf(applicableActions);
        Objects.requireNonNull(reevaluateAt, "reevaluateAt");
    }
}
