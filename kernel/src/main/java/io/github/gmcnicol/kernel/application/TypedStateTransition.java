package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** One ordered typed Event and its exact resulting Projection. */
public record TypedStateTransition<P, E>(E event, P resultingProjection) {
    public TypedStateTransition {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(resultingProjection, "resultingProjection");
    }
}
