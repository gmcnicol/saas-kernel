package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Application-resolved effective policy inputs. Grant provenance and consumption stay in the Application. */
public record TypedCapability<C>(CapabilityType<C> type, C value) {
    public TypedCapability {
        Objects.requireNonNull(type, "type");
        type.javaType().cast(Objects.requireNonNull(value, "value"));
    }
}
