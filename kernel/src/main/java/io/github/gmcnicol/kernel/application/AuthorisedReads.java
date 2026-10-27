package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Optional;

/** Ordinary bounded reads; no workflow evaluation, Offers or persisted domain values. */
public interface AuthorisedReads {
    <P> List<AuthorisedRecord<P>> many(ReadRequest<P> request, ReadSource<P> source);
    /** Raw scans cannot safely supply hidden-density-independent pagination/count metadata. Never falls back to many(). */
    default <P> ReadPage<P> collection(ReadRequest<P> request, ReadSource<P> source, CollectionOptions options) {
        throw new CollectionReadException(CollectionReadException.Reason.UNSUPPORTED);
    }
    default <P> ReadPage<P> collection(ReadRequest<P> request, AuthorisedIndex<P> index, CollectionOptions options) {
        throw new CollectionReadException(CollectionReadException.Reason.UNSUPPORTED);
    }
    default <P> Optional<AuthorisedRecord<P>> one(ReadRequest<P> request, ReadSource<P> source) {
        var results = many(request, source);
        if (results.size() > 1) throw new AuthorisationDeniedException();
        return results.stream().findFirst();
    }
}
