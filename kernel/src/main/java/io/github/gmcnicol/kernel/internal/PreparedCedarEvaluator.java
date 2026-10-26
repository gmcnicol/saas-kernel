package io.github.gmcnicol.kernel.internal;

import java.io.ByteArrayOutputStream;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/** Scalar-only owned codec and bounded execution/lifetime boundary. No SDK reflection or batch API. */
final class PreparedCedarEvaluator implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Semaphore ADMISSION = new Semaphore(16, true);
    private static final Semaphore WORKERS = new Semaphore(4, true);
    private final State state;
    private final Cleaner.Cleanable cleanable;
    private static final class State implements Runnable {
        final AtomicLong handle;
        State(long handle) { this.handle = new AtomicLong(handle); }
        @Override public void run() {
            long value = handle.getAndSet(0);
            if (value != 0 && NativeCedar.close(value) != 1) throw new IllegalStateException("Cannot release Cedar bundle");
        }
    }
    private PreparedCedarEvaluator(long handle) {
        state = new State(handle);
        cleanable = CLEANER.register(this, state);
    }
    static PreparedCedarEvaluator open(String schema, Map<String, String> policies) {
        return open(schema, policies, Map.of(), List.of());
    }
    static PreparedCedarEvaluator open(String schema, Map<String, String> policies, Map<String, String> templates, List<?> links) {
        CedarNativeLibrary.load();
        if (!ADMISSION.tryAcquire()) throw new IllegalStateException("Cedar admission exhausted");
        boolean acquired = false;
        try {
            if (!WORKERS.tryAcquire(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cedar preparation admission exhausted");
            acquired = true;
            long handle = NativeCedar.open(encode(Map.of("schema", schema, "policies", new TreeMap<>(policies), "templates", new TreeMap<>(templates), "links", links), 262_144));
            if (handle <= 0 || handle >= 0x4b75000000000000L) throw new IllegalStateException("Cannot prepare bounded Cedar bundle");
            try { return new PreparedCedarEvaluator(handle); }
            catch (RuntimeException | Error failure) { NativeCedar.close(handle); throw failure; }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Cedar preparation cancelled");
        } finally {
            if (acquired) WORKERS.release();
            ADMISSION.release();
        }
    }
    ReadDecision evaluate(Map<String, ?> request, long deadline) {
        long handle = state.handle.get();
        if (handle == 0) throw new IllegalStateException("Cedar bundle is closed");
        if (!ADMISSION.tryAcquire()) throw new IllegalStateException("Cedar admission exhausted");
        boolean acquired = false;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !WORKERS.tryAcquire(remaining, TimeUnit.NANOSECONDS)) throw new IllegalStateException("Cedar deadline exhausted");
            acquired = true;
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Cedar evaluation cancelled");
            String encoded = encode(request, 1_048_576);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Cedar deadline exhausted");
            long result = NativeCedar.evaluate(handle, encoded, Math.min(remaining, 5_000_000_000L));
            if (state.handle.get() != handle || System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Cedar evaluation no longer current");
            return ReadDecision.decode(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Cedar evaluation cancelled");
        } finally {
            if (acquired) WORKERS.release();
            ADMISSION.release();
        }
    }
    static String encode(Object value, int limit) {
        var bytes = new ByteArrayOutputStream(Math.min(limit, 16_384)) {
            private void bound(int length) { if (length < 0 || count > limit - length) throw new IllegalStateException("Cedar input exceeds byte bound"); }
            @Override public synchronized void write(int b) { bound(1); super.write(b); }
            @Override public synchronized void write(byte[] b, int start, int length) { bound(length); super.write(b, start, length); }
        };
        JSON.writeValue(bytes, value);
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Override public void close() { cleanable.clean(); }
}
