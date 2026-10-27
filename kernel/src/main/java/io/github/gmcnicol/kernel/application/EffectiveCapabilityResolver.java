package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;

/**
 * Trusted Application boundary. Resolve current grants AND their authoritative revision on every call;
 * never return caller claims or cached authority. Failures deny. No grant persistence or consumption
 * is performed by Kernel. Applications own atomic allowance reservation/consumption.
 */
@FunctionalInterface
public interface EffectiveCapabilityResolver {
    Resolution resolve(Request request);

    /** Canonical requested resource, not a shared person's identity or caller-selected grant scope. */
    record Scope(String tenantId, Principal principal, TypedSubject<?> resource) {}

    /** Time is Kernel's current clock, independent of workflow evaluation/execution timestamps. */
    record Request(Scope scope, Instant evaluatedAt) {}

    /** currentRevision must come from the authoritative current source, not Context's version claim. */
    record Resolution(String currentRevision, Context context) {}

    /** Version 1: exactly request time/scope, current revision, and a strictly future expiry. */
    record Context(int formatVersion, Scope scope, Instant evaluatedAt, Instant expiresAt,
                   String revision, List<TypedCapability<?>> capabilities) {
        public Context {
            if (capabilities != null) capabilities = List.copyOf(capabilities);
        }
    }
}
