package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IntentQuery(
        String tenantId,
        Optional<IntentStatus> status,
        Optional<Subject> subject,
        Optional<UUID> intentId,
        Optional<Instant> acceptedBefore,
        Optional<Cursor> after,
        int limit) {

    public IntentQuery {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Tenant ID is required");
        if (status == null || subject == null || intentId == null || acceptedBefore == null || after == null) {
            throw new IllegalArgumentException("Intent query filters cannot be null");
        }
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Intent query limit must be 1..100");
    }

    public IntentQuery(
            String tenantId,
            Optional<IntentStatus> status,
            Optional<Subject> subject,
            Optional<UUID> intentId,
            Optional<Instant> acceptedBefore) {
        this(tenantId, status, subject, intentId, acceptedBefore, Optional.empty(), 100);
    }

    public static IntentQuery tenant(String tenantId) {
        return new IntentQuery(
                tenantId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 100);
    }

    public record Cursor(Instant acceptedAt, UUID intentId) {
        public Cursor {
            if (acceptedAt == null || intentId == null) {
                throw new IllegalArgumentException("Intent cursor requires acceptance time and ID");
            }
        }
    }
}
