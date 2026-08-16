package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.CandidateType;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import java.util.List;

/** Generated inventory used to validate and prepare one typed Semantic Pack. */
public record SemanticBindings(
        List<ProjectionType<?, ?>> projections,
        List<FactType<?>> facts,
        List<CandidateType<?>> candidates,
        List<EventType<?>> events,
        List<ActionType<?, ?, ?>> actions) {

    public SemanticBindings(List<ProjectionType<?, ?>> projections, List<FactType<?>> facts) {
        this(projections, facts, List.of(), List.of(), List.of());
    }

    public SemanticBindings {
        projections = List.copyOf(projections);
        facts = List.copyOf(facts);
        candidates = List.copyOf(candidates);
        events = List.copyOf(events);
        actions = List.copyOf(actions);
    }
}
