package io.github.gmcnicol.kernel.presentationpack;

import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;

/** Application renderer that can consume only Cedar-filtered generated values. */
@FunctionalInterface
public interface TypedPresentationPack<I, P> {
    PresentationResult render(TypedPresentationEnvelope<I, P> envelope);
}
