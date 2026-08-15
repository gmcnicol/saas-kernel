package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record EvaluationSnapshot(
        UUID id,
        String tenantId,
        Subject subject,
        long projectedStateVersion,
        String projectedStateChecksum,
        Instant evaluatedAt,
        ApplicationVersion applicationVersion,
        String kernelVersion,
        SemanticPackVersion semanticPackVersion,
        List<Fact> facts,
        List<ApplicableAction> applicableActions,
        Optional<Instant> reevaluateAt) {

    public EvaluationSnapshot {
        facts = List.copyOf(facts);
        applicableActions = List.copyOf(applicableActions);
    }
}
