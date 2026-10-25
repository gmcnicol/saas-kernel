package io.github.gmcnicol.kernel.application;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;

/** Bounded final-release fences. Authentication, purpose, SQL, formatters and sinks are trusted Application code. */
public interface AuthorisedDisclosures {
    /** Opaque process-local lineage, not a grant, durable job or payload accessor. Foreign implementations reject. */
    interface Prepared<P> {}
    /** Buffered provider output still bound to its original contributing sources. */
    interface Derived<P> {}
    @FunctionalInterface interface Formatter<P> {
        void format(List<AuthorisedRecord<P>> views, OutputStream output) throws Exception;
    }
    @FunctionalInterface interface Sink {
        void send(DisclosureBytes bytes) throws Exception;
    }
    @FunctionalInterface interface Provider {
        void exchange(DisclosureBytes context, OutputStream response) throws Exception;
    }
    <P> Prepared<P> prepare(ReadRequest<P> request, ReadSource<P> source, String purpose, Duration lifetime);
    <P> void send(Prepared<P> prepared, ReadRequest<P> current, String purpose, ReadSource<P> source,
            Formatter<P> formatter, Sink sink);
    <P> Derived<P> derive(Prepared<P> prepared, ReadRequest<P> current, String purpose, ReadSource<P> source,
            Formatter<P> formatter, Provider provider);
    <P> void send(Derived<P> derived, ReadRequest<P> current, String purpose, ReadSource<P> source, Sink sink);
}
