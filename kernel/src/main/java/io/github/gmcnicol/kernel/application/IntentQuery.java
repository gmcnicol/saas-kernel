package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IntentQuery(
        String tenantId,
        Optional<IntentStatus> status,
        Optional<Subject> subject,
        Optional<UUID> intentId,
        Optional<Instant> acceptedBefore) {

    public IntentQuery {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Tenant ID is required");
        if (status == null || subject == null || intentId == null || acceptedBefore == null) {
            throw new IllegalArgumentException("Intent query filters cannot be null");
        }
    }

    public static IntentQuery tenant(String tenantId) {
        return new IntentQuery(tenantId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
