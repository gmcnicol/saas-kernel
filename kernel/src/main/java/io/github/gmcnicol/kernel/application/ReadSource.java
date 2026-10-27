package io.github.gmcnicol.kernel.application;

import java.sql.Connection;
import java.util.function.Consumer;

/**
 * Trusted, synchronous Application SQL/mapping, not a sandbox. Stream one mapped candidate at a time;
 * do not precollect rows, retain connection/sink, change role/tenant, commit, or perform writes.
 * SQL/RLS correctness and per-row mapping memory remain Application obligations.
 */
public interface ReadSource<P> {
    /** Authoritative source revision, read afresh on this connection; withdrawal must change it. */
    String revision(Connection connection) throws Exception;
    void select(Connection connection, Consumer<ReadCandidate<P>> sink) throws Exception;
}
