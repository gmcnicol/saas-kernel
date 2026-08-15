package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PresentationEnvelope(
        int version,
        Subject subject,
        UUID evaluationId,
        Instant evaluatedAt,
        String semanticPackId,
        Map<String, String> fields,
        List<PresentationFact> facts,
        List<PresentationActionOffer> actionOffers) {

    public PresentationEnvelope {
        fields = Map.copyOf(fields);
        facts = List.copyOf(facts);
        actionOffers = List.copyOf(actionOffers);
    }
}
