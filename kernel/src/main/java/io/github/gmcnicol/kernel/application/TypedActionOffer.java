package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.UUID;

/** Authorised offer bound to one exact generated Action descriptor. */
public record TypedActionOffer<P, C, E>(UUID id, ActionType<P, C, E> actionType) {
    public TypedActionOffer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actionType, "actionType");
    }
}
