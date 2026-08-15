package io.github.gmcnicol.kernel.application;

import java.util.Map;

public record ProjectedState(String tenantId, Subject subject, long version, Map<String, String> values) {

    public ProjectedState {
        if (tenantId == null || tenantId.isBlank() || subject == null || version < 0) {
            throw new IllegalArgumentException("Projected State requires tenant, subject, and non-negative version");
        }
        values = Map.copyOf(values);
    }
}
