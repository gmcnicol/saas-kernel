package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AuthorisationEnvelope(
        UUID evaluationSnapshotId,
        Map<String, String> fields,
        List<Fact> facts,
        List<ActionOffer> actionOffers) {

    public AuthorisationEnvelope {
        fields = Map.copyOf(fields);
        facts = List.copyOf(facts);
        actionOffers = List.copyOf(actionOffers);
    }
}
