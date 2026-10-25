package review;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Compilation-only alternatives for #71: no implementation, persistence, service or production dependency. */
public final class DisclosureContracts {
    private DisclosureContracts() {}
    public record Purpose(String qualifiedName) {}
    public record Source(ScopedRead.Identity identity, Set<String> authorisedLeafPaths) {
        public Source { authorisedLeafPaths = Set.copyOf(authorisedLeafPaths); }
        @Override public String toString() { return "Source[redacted]"; }
    }
    public record Stamp(ScopedRead.Scope scope, String authorityRevision, String bundleChecksum,
                        List<Source> sources, Purpose purpose, Instant authorisedAt, Instant expiresAt) {
        public Stamp { sources = List.copyOf(sources); }
        @Override public String toString() { return "DisclosureStamp[redacted]"; }
    }
    // Not a public record/constructor; a consumer must not mint handles as proof of authority.
    public static final class Handle<R> {
        private final Stamp stamp;
        private Handle(Stamp stamp) { this.stamp = stamp; }
        @Override public String toString() { return "DisclosureHandle[redacted]"; }
    }
    @FunctionalInterface public interface Sink { void send(byte[] authorisedBytes) throws Exception; }
    public interface FinalRelease {
        <R> Handle<R> prepare(ScopedRead.Scope scope, ScopedRead.Model<R> model,
                              ScopedRead.Query<R> query, Purpose purpose);
        // Must reread current authority/policy/source immediately before one bounded send.
        // Application supplies purpose-specific formatting; no raw model or mutable caller payload.
        <R> void release(Handle<R> handle, ScopedRead.Scope authenticatedScope, Purpose purpose, Sink sink);
    }
}
