package io.github.gmcnicol.kernel.application;

import java.util.Set;

/** Trusted authentication/scope and explicit generated leaf selection. Bundle identity pins expected policy. */
public record ReadRequest<P>(String tenantId, Principal principal, TypedSubject<?> scope,
        ProjectionType<?, P> type, Set<ReadPath<P, ?>> fields,
        String bundleId, String bundleChecksum, ReadLimits limits) {
    public ReadRequest { fields = Set.copyOf(fields); }
    @Override public String toString() { return "ReadRequest[redacted]"; }
}
