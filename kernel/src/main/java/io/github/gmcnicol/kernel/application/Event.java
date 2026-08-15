package io.github.gmcnicol.kernel.application;

import java.util.Map;

/** Application-owned domain outcome plus complete resulting Projected State. */
public record Event(String type, int version, Map<String, String> payload, Map<String, String> resultingState) {

    public Event {
        if (type == null || type.isBlank() || version < 1 || payload == null || resultingState == null) {
            throw new IllegalArgumentException("Event requires type, version, payload, and resulting state");
        }
        payload = Map.copyOf(payload);
        resultingState = Map.copyOf(resultingState);
    }
}
