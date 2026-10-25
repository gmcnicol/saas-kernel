package io.github.gmcnicol.kernel.internal;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.gmcnicol.kernel.application.*;
import java.io.*;
import java.time.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No archive, network client or permission cache. Application callbacks are not sandboxed. */
final class FencedDisclosures implements AuthorisedDisclosures {
    private final ScopedAuthorisedReads reads;
    private final Clock clock;
    FencedDisclosures(ScopedAuthorisedReads reads, Clock clock) { this.reads = reads; this.clock = clock; }

    private static final class Lineage<P> implements Prepared<P> {
        final FencedDisclosures issuer;
        final ReadRequest<P> request;
        final String purpose;
        final Instant evaluatedAt;
        final Instant expiresAt;
        final ScopedAuthorisedReads.Evidence evidence;
        Lineage(FencedDisclosures issuer, ReadRequest<P> request, String purpose, Instant evaluatedAt,
                Instant expiresAt, ScopedAuthorisedReads.Evidence evidence) {
            this.issuer = issuer; this.request = request; this.purpose = purpose;
            this.evaluatedAt = evaluatedAt; this.expiresAt = expiresAt; this.evidence = evidence;
        }
        @JsonValue public String redacted() { return "PreparedDisclosure[redacted]"; }
        @Override public String toString() { return redacted(); }
    }
    private static final class Buffered<P> implements Derived<P> {
        final Lineage<P> lineage;
        final DisclosureBytes bytes;
        Buffered(Lineage<P> lineage, DisclosureBytes bytes) { this.lineage = lineage; this.bytes = bytes; }
        @JsonValue public String redacted() { return "DerivedDisclosure[redacted]"; }
        @Override public String toString() { return redacted(); }
    }

    @Override public <P> Prepared<P> prepare(ReadRequest<P> request, ReadSource<P> source, String purpose, Duration lifetime) {
        return safe(() -> {
            if (purpose == null || purpose.isBlank() || purpose.length() > 128 || purpose.chars().anyMatch(Character::isISOControl)
                    || lifetime == null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(5)) > 0)
                throw new AuthorisationDeniedException();
            metadata(request.tenantId()); metadata(request.principal().type()); metadata(request.principal().id());
            metadata(request.scope().externalId()); metadata(request.bundleId()); metadata(request.bundleChecksum());
            var now = clock.instant();
            var handle = new Lineage<>(this, request, purpose, now, now.plus(lifetime), reads.disclosure(request, source).evidence());
            context(handle, request, purpose);
            return handle;
        });
    }

    private static void metadata(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) throw new AuthorisationDeniedException();
    }

    @SuppressWarnings("unchecked")
    private <P> Lineage<P> lineage(Prepared<P> prepared) {
        if (!(prepared instanceof Lineage<?> handle) || handle.issuer != this) throw new AuthorisationDeniedException();
        return (Lineage<P>) handle;
    }

    private <P> void context(Lineage<P> handle, ReadRequest<P> current, String purpose) {
        var now = clock.instant();
        if (now.isBefore(handle.evaluatedAt) || !now.isBefore(handle.expiresAt)
                || !handle.request.equals(current) || !handle.purpose.equals(purpose)) throw new AuthorisationDeniedException();
    }

    private <P> void fence(Lineage<P> handle, ReadRequest<P> current, String purpose, ReadSource<P> source) {
        context(handle, current, purpose);
        if (!handle.evidence.equals(reads.disclosure(current, source).evidence())) throw new AuthorisationDeniedException();
        context(handle, current, purpose);
    }

    private <P> DisclosureBytes format(Lineage<P> handle, ReadRequest<P> current, String purpose,
            ReadSource<P> source, Formatter<P> formatter) throws Exception {
        context(handle, current, purpose);
        var fresh = reads.disclosure(current, source);
        if (!handle.evidence.equals(fresh.evidence())) throw new AuthorisationDeniedException();
        context(handle, current, purpose);
        var output = new BoundedOutput(current.limits().outputBytes());
        formatter.format(fresh.records(), output);
        return output.freeze();
    }

    @Override public <P> void send(Prepared<P> prepared, ReadRequest<P> current, String purpose,
            ReadSource<P> source, Formatter<P> formatter, Sink sink) {
        safe(() -> {
            var handle = lineage(prepared);
            var bytes = format(handle, current, purpose, source, formatter);
            fence(handle, current, purpose, source);
            sink.send(bytes);
            return null;
        });
    }

    @Override public <P> Derived<P> derive(Prepared<P> prepared, ReadRequest<P> current, String purpose,
            ReadSource<P> source, Formatter<P> formatter, Provider provider) {
        return safe(() -> {
            var handle = lineage(prepared);
            var bytes = format(handle, current, purpose, source, formatter);
            var output = new BoundedOutput(current.limits().outputBytes());
            fence(handle, current, purpose, source);
            provider.exchange(bytes, output);
            return new Buffered<>(handle, output.freeze());
        });
    }

    @Override public <P> void send(Derived<P> derived, ReadRequest<P> current, String purpose, ReadSource<P> source, Sink sink) {
        safe(() -> {
            if (!(derived instanceof Buffered<?>)) throw new AuthorisationDeniedException();
            @SuppressWarnings("unchecked") var buffered = (Buffered<P>) derived;
            var handle = lineage(buffered.lineage);
            fence(handle, current, purpose, source);
            sink.send(buffered.bytes);
            return null;
        });
    }

    /** Sticky overflow; swallowing a write error cannot turn a truncated response into valid output. */
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int limit;
        private boolean failed;
        BoundedOutput(int limit) { this.limit = Math.min(limit, DisclosureBytes.MAX_BYTES); }
        @Override public synchronized void write(int b) { check(1); buffer.write(b); }
        @Override public synchronized void write(byte[] b, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, b.length);
            check(length); buffer.write(b, offset, length);
        }
        private void check(int length) {
            if (failed || length > limit - buffer.size()) { failed = true; throw new AuthorisationDeniedException(); }
        }
        synchronized DisclosureBytes freeze() { check(0); return new DisclosureBytes(buffer.toByteArray()); }
    }
    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
    private static <T> T safe(Operation<T> action) {
        try {
            // Unlike ordinary reads, this boundary must not join a caller's mutation transaction.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw new AuthorisationDeniedException();
            return action.run();
        }
        catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath death) throw death;
            throw new AuthorisationDeniedException();
        }
    }
}
