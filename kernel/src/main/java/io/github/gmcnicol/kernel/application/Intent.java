package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.UUID;

public record Intent(UUID id, UUID actionOfferId, IntentStatus status, Instant acceptedAt) {}
