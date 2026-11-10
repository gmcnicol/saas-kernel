package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.TypedCandidatePayload;
import java.time.Instant;
import java.util.UUID;

/** Internal adaptation of the mandated public exact-execution signature. */
record ExactIntentRequest<C>(
        String tenantId,
        Principal principal,
        UUID actionOfferId,
        UUID intentId,
        TypedCandidatePayload<C> candidate,
        Instant executedAt) {}
