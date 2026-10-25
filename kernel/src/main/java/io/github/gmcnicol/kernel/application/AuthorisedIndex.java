package io.github.gmcnicol.kernel.application;

import java.sql.Connection;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Trusted Application-owned authorised relation, NOT a raw candidate scan. SQL, index maintenance,
 * domain relations and query-template correctness remain Application obligations. Not sandboxed.
 */
public interface AuthorisedIndex<P> {
    /** Stable template version plus a digest of bound parameters; never raw private query text. */
    default String queryIdentity() { return "all-v1"; }
    /** All predicate/order/aggregate paths used by the Application template, including non-output paths. */
    default Set<ReadPath<P, ?>> queryFields() { return Set.of(); }
    /**
     * Independently read current authorised-view source/index state for this binding. Verify the active
     * Application policy and index freshness; never echo a token/binding as proof of currency.
     */
    State current(Connection connection, Binding<P> binding) throws Exception;
    /** Stream at most limit authorised candidates in stable authorised order, starting at authorised offset. */
    void select(Connection connection, Binding<P> binding, long offset, int limit, Consumer<ReadCandidate<P>> sink) throws Exception;

    record Binding<P>(ReadRequest<P> request, String authorityRevision, String queryIdentity, Set<ReadPath<P, ?>> queryFields) {
        public Binding { queryFields = Set.copyOf(queryFields); }
        @Override public String toString() { return "IndexBinding[redacted]"; }
    }
    /**
     * Index must incorporate current record/query-field permissions for the entire relation. Checksums
     * and authority revisions describe the policy/authority actually supported by that current index.
     * Revisions cover relevant authorised-view changes, not hidden-only global mutation counters.
     */
    record State(String sourceRevision, String indexRevision, String bundleChecksum, String authorityRevision, OptionalLong exactCount) {
        @Override public String toString() { return "IndexState[redacted]"; }
    }
}
