package io.github.gmcnicol.kernel.application;

import java.util.OptionalInt;
import java.util.UUID;

public record IntentAuditQuery(String tenantId, UUID intentId, OptionalInt afterSequence, int limit) {

    public IntentAuditQuery {
        if (tenantId == null || tenantId.isBlank() || intentId == null || afterSequence == null) {
            throw new IllegalArgumentException("Intent audit query requires tenant, Intent ID, and cursor");
        }
        if (afterSequence.isPresent() && afterSequence.getAsInt() < 0) {
            throw new IllegalArgumentException("Intent audit cursor cannot be negative");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Intent audit query limit must be 1..100");
        }
    }

    public static IntentAuditQuery firstPage(String tenantId, UUID intentId) {
        return new IntentAuditQuery(tenantId, intentId, OptionalInt.empty(), 100);
    }
}
