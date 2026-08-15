package io.github.gmcnicol.kernel.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record CandidatePayload(
        String type,
        int version,
        Map<String, String> values,
        Optional<W3cTraceContext> traceContext,
        Optional<UUID> priorIntentId) {

    public CandidatePayload(String type, int version, Map<String, String> values) {
        this(type, version, values, Optional.empty(), Optional.empty());
    }

    public CandidatePayload {
        if (type == null || type.isBlank() || version < 1 || values == null
                || traceContext == null || priorIntentId == null) {
            throw new IllegalArgumentException("Candidate payload requires a qualified type, version, and values");
        }
        values = Map.copyOf(values);
    }
}
