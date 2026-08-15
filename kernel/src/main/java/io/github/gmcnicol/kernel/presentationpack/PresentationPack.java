package io.github.gmcnicol.kernel.presentationpack;

import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/** Application-owned presentation definitions discovered as ordinary Spring beans. */
public interface PresentationPack {

    String manifestResource();

    PresentationResult render(PresentationEnvelope envelope);

    default PresentationPack observed(ObservationRegistry observations) {
        PresentationPack delegate = this;
        return of(manifestResource(), envelope -> {
            Observation observation;
            try {
                observation = Observation.start("kernel.presentation.rendering", observations);
            } catch (RuntimeException exporterFailure) {
                return delegate.render(envelope);
            }
            try {
                return delegate.render(envelope);
            } catch (RuntimeException | Error businessFailure) {
                try {
                    observation.error(businessFailure);
                } catch (RuntimeException ignored) {
                    // Telemetry is never authoritative.
                }
                throw businessFailure;
            } finally {
                try {
                    observation.stop();
                } catch (RuntimeException ignored) {
                    // Telemetry is never authoritative.
                }
            }
        });
    }

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
