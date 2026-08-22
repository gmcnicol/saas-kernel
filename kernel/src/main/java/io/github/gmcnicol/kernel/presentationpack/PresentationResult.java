package io.github.gmcnicol.kernel.presentationpack;

import java.util.Set;
import java.util.UUID;

public record PresentationResult(String html, String eventStream, Set<UUID> renderedActionOffers) {

    public PresentationResult {
        renderedActionOffers = Set.copyOf(renderedActionOffers);
    }
}
