package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.UUID;

/** Execution receipt only. Event values remain in Application-owned domain records. */
public record IntentEvent(UUID id, int sequence, String eventType, int contractVersion,
                          long resultingStateVersion, Instant occurredAt) {}
