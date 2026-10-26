package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.value.*;
import java.io.ByteArrayOutputStream;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import io.github.gmcnicol.kernel.application.CollectionReadException.Reason;

/** Scalar-only owned codec and bounded execution/lifetime boundary. No SDK reflection or batch API. */
final class PreparedCedarEvaluator implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Value.class, new ValueSerializer<Value>() {
                @Override public void serialize(Value value, JsonGenerator json, SerializationContext context) {
                    writeValue(json, value, 1);
                }
            }))
            .disable(SerializationFeature.WRAP_EXCEPTIONS)
            .build();
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
        if (!ADMISSION.tryAcquire()) throw new ReadEvaluationLimit(Reason.BUDGET);
        boolean acquired = false;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !WORKERS.tryAcquire(remaining, TimeUnit.NANOSECONDS)) throw new ReadEvaluationLimit(Reason.BUDGET);
            acquired = true;
            if (Thread.currentThread().isInterrupted()) throw new ReadEvaluationLimit(Reason.CANCELLED);
            String encoded = encode(request, 1_048_576);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ReadEvaluationLimit(Reason.BUDGET);
            var result = ReadDecision.decode(NativeCedar.evaluate(handle, encoded, Math.min(remaining, 5_000_000_000L)));
            if (state.handle.get() != handle) throw new IllegalStateException("Cedar bundle closed during evaluation");
            // Preserve policy/validation error precedence; do not turn an errored SDK-equivalent
            // response into a timeout/cancellation merely because its callback returned late.
            if (result == ReadDecision.INVALID) return result;
            if (Thread.currentThread().isInterrupted()) throw new ReadEvaluationLimit(Reason.CANCELLED);
            if (result == ReadDecision.EXPIRED || System.nanoTime() - deadline >= 0) throw new ReadEvaluationLimit(Reason.BUDGET);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ReadEvaluationLimit(Reason.CANCELLED);
        } finally {
            if (acquired) WORKERS.release();
            ADMISSION.release();
        }
    }
    // Traverse decision-local Cedar values only inside admitted, byte-bounded encoding; never copy a whole tree.
    private static void writeValue(JsonGenerator json, Value value, int depth) {
        if (depth > 64) throw new ReadEvaluationLimit(Reason.BUDGET);
        switch (value) {
            case PrimBool bool -> json.writeBoolean(bool.getValue());
            case PrimLong number -> json.writeNumber(number.getValue());
            case PrimString text -> json.writeString(text.toString());
            case CedarMap map -> {
                if (depth + 1 > 64) throw new ReadEvaluationLimit(Reason.BUDGET);
                json.writeStartObject();
                for (var entry : map.entrySet()) {
                    json.writeName(entry.getKey());
                    writeValue(json, entry.getValue(), depth + 2);
                }
                json.writeEndObject();
            }
            case CedarList list -> {
                json.writeStartArray();
                for (var item : list) writeValue(json, item, depth + 1);
                json.writeEndArray();
            }
            case EntityUID entity -> {
                var uid = entity.asJson();
                json.writePOJO(Map.of("__entity", Map.of("type", uid.type, "id", uid.id)));
            }
            case Decimal decimal -> json.writePOJO(Map.of("__extn", Map.of("fn", "decimal", "arg", decimal.toString())));
            case IpAddress ip -> json.writePOJO(Map.of("__extn", Map.of("fn", "ip", "arg", ip.toString())));
            default -> throw new IllegalArgumentException("Unsupported complete Cedar value");
        }
    }
    static String encode(Object value, int limit) {
        var bytes = new ByteArrayOutputStream(Math.min(limit, 16_384)) {
            boolean exceeded;
            private void bound(int length) { if (length < 0 || count > limit - length) { exceeded = true; throw new ReadEvaluationLimit(Reason.BUDGET); } }
            @Override public synchronized void write(int b) { bound(1); super.write(b); }
            @Override public synchronized void write(byte[] b, int start, int length) { bound(length); super.write(b, start, length); }
        };
        try { JSON.writeValue(bytes, value); }
        catch (RuntimeException failure) { if (bytes.exceeded) throw new ReadEvaluationLimit(Reason.BUDGET); throw failure; }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Override public void close() { cleanable.clean(); }
}
