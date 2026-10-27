package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.*;
import java.sql.Connection;
import java.util.*;
import java.util.function.Consumer;

/** Adapts a current Application index to the SAME scoped coordinator as ordinary reads. */
final class IndexedReadWindow<P> implements ReadSource<P> {
    final ReadRequest<P> request;
    final AuthorisedIndex<P> index;
    final CollectionOptions options;
    final ReadContinuations tokens;
    final String queryIdentity;
    final Set<ReadPath<P, ?>> queryFields;
    private AuthorisedIndex.Binding<P> binding;
    private AuthorisedIndex.State state;
    private byte[] fingerprint;
    private long offset;
    private boolean overrun;

    IndexedReadWindow(ReadRequest<P> request, AuthorisedIndex<P> index, CollectionOptions options, ReadContinuations tokens) {
        this.request = request; this.index = index; this.options = options; this.tokens = tokens;
        queryIdentity = index.queryIdentity(); queryFields = Set.copyOf(index.queryFields());
        if (queryIdentity == null || queryIdentity.isBlank() || queryIdentity.length() > 256
                || queryFields.size() > request.limits().fields()
                || queryFields.stream().anyMatch(path -> request.type().readPaths().stream().noneMatch(known -> known == path))) {
            throw new CollectionReadException(CollectionReadException.Reason.DENIED);
        }
        if (options.pageSize() >= request.limits().candidates()) throw new CollectionReadException(CollectionReadException.Reason.BUDGET);
    }

    void prepare(String authorityRevision) {
        binding = new AuthorisedIndex.Binding<>(request, authorityRevision, queryIdentity, queryFields);
    }

    @Override public String revision(Connection connection) throws Exception {
        if (overrun) throw new CollectionReadException(CollectionReadException.Reason.BUDGET);
        var current = index.current(connection, binding);
        if (current == null || current.sourceRevision() == null || current.sourceRevision().isBlank()
                || current.indexRevision() == null || current.indexRevision().isBlank() || current.exactCount() == null
                || current.sourceRevision().length() > 256 || current.indexRevision().length() > 256
                || (current.exactCount().isPresent() && current.exactCount().getAsLong() < 0)) stale();
        if (!request.bundleChecksum().equals(current.bundleChecksum()) || !binding.authorityRevision().equals(current.authorityRevision())) stale();
        if (state == null) {
            state = current;
            var parts = new ArrayList<>(List.of(request.tenantId(), request.principal().type(), request.principal().id(),
                    request.scope().type().qualifiedName(), request.scope().externalId(), request.type().qualifiedName(),
                    Integer.toString(request.type().contractVersion()), request.bundleId(), request.bundleChecksum(),
                    binding.authorityRevision(), queryIdentity, current.sourceRevision(), current.indexRevision(),
                    current.exactCount().toString(), Integer.toString(options.pageSize())));
            parts.add("output"); request.fields().stream().map(ReadPath::qualifiedName).sorted().forEach(parts::add);
            parts.add("query"); queryFields.stream().map(ReadPath::qualifiedName).sorted().forEach(parts::add);
            fingerprint = ReadContinuations.digest(parts);
            offset = options.continuation().map(token -> tokens.resume(token, fingerprint)).orElse(0L);
        } else if (!state.equals(current)) stale();
        return Base64.getEncoder().encodeToString(fingerprint);
    }

    @Override public void select(Connection connection, Consumer<ReadCandidate<P>> sink) throws Exception {
        int[] consumed = {0};
        index.select(connection, binding, offset, options.pageSize() + 1, candidate -> {
            if (++consumed[0] > options.pageSize() + 1) {
                overrun = true;
                throw new CollectionReadException(CollectionReadException.Reason.BUDGET);
            }
            sink.accept(candidate);
        });
    }

    ReadPage<P> page(List<AuthorisedRecord<P>> records) {
        boolean more = records.size() > options.pageSize();
        int size = Math.min(records.size(), options.pageSize());
        long end = offset + size;
        if (state.exactCount().isPresent()) {
            long count = state.exactCount().getAsLong();
            if ((more && count <= end) || (!more && count != end)) stale();
        }
        if (offset > 0 && records.isEmpty()) stale(); // No hidden-density empty page as a normal result.
        if (end > 1_000_000_000L) throw new CollectionReadException(CollectionReadException.Reason.BUDGET);
        return new ReadPage<>(records.subList(0, size), state.exactCount(), more ? Optional.of(tokens.issue(fingerprint, end)) : Optional.empty());
    }

    static void stale() { throw new CollectionReadException(CollectionReadException.Reason.STALE); }
}
