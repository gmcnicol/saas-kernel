package io.github.gmcnicol.kernel.presentationpack;

import io.github.gmcnicol.kernel.application.PresentationEnvelope;

/** Application-owned presentation definitions discovered as ordinary Spring beans. */
public interface PresentationPack {

    String manifestResource();

    PresentationResult render(PresentationEnvelope envelope);

    static PresentationPack of(String manifestResource, Renderer renderer) {
        return new Binding(manifestResource, renderer);
    }

    @FunctionalInterface
    interface Renderer {
        PresentationResult render(PresentationEnvelope envelope);
    }

    record Binding(String manifestResource, Renderer renderer) implements PresentationPack {

        @Override
        public PresentationResult render(PresentationEnvelope envelope) {
            return renderer.render(envelope);
        }
    }
}
