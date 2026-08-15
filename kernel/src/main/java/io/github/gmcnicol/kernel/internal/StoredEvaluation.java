package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

record StoredEvaluation(
        UUID id,
        Subject subject,
        long stateVersion,
        String stateChecksum,
        Instant evaluatedAt,
        String applicationId,
        String applicationVersion,
        String kernelVersion,
        SemanticPackVersion semanticPack,
        Map<String, String> state,
        List<Fact> facts,
        List<ApplicableAction> actions) {}
