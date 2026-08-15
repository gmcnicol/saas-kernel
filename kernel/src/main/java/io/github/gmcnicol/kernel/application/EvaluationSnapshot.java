package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record EvaluationSnapshot(
        UUID id,
        String subject,
        long projectedStateVersion,
        Instant evaluatedAt,
        String applicationId,
        SemanticPackIdentity semanticPack,
        List<Fact> facts,
        List<ApplicableAction> applicableActions,
        Optional<Instant> reevaluateAt) {

    public EvaluationSnapshot {
        facts = List.copyOf(facts);
        applicableActions = List.copyOf(applicableActions);
    }
}
