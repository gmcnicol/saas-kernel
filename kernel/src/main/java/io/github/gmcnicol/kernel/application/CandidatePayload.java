package io.github.gmcnicol.kernel.application;

import java.util.Map;

public record CandidatePayload(String type, int version, Map<String, String> values) {

    public CandidatePayload {
        if (type == null || type.isBlank() || version < 1 || values == null) {
            throw new IllegalArgumentException("Candidate payload requires a qualified type, version, and values");
        }
        values = Map.copyOf(values);
    }
}
