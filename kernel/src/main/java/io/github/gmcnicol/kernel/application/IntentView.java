package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IntentView(
        UUID id,
        String tenantId,
        Subject subject,
        String actionId,
        IntentStatus status,
        Instant acceptedAt,
        int attemptCount,
        Optional<IntentFailureReason> failureReason,
        Optional<UUID> priorIntentId) {}
