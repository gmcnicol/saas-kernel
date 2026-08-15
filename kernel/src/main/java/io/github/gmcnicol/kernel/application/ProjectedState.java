package io.github.gmcnicol.kernel.application;

import java.util.Map;

public record ProjectedState(String tenantId, String subject, long version, Map<String, String> values) {

    public ProjectedState {
        if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank() || version < 0) {
            throw new IllegalArgumentException("Projected State requires tenant, subject, and non-negative version");
        }
        values = Map.copyOf(values);
    }
}
