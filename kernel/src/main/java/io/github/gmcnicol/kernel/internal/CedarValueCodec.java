package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.value.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import io.github.gmcnicol.kernel.application.CollectionReadException.Reason;

/** Owned bounded value encoding shared by prepared reads and SDK input validation. */
final class CedarValueCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Value.class, new ValueSerializer<Value>() {
                @Override public void serialize(Value value, JsonGenerator json, SerializationContext context) {
                    writeValue(json, value, 1);
                }
            }))
            .disable(SerializationFeature.WRAP_EXCEPTIONS)
            .build();
    private CedarValueCodec() {}

    static void validate(Map<String, Value> attributes, Map<String, Value> context) {
        encode(List.of(attributes, context), 1_048_576);
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
                    if ("__entity".equals(entry.getKey()) || "__extn".equals(entry.getKey()))
                        throw new IllegalArgumentException("Reserved Cedar record key");
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
}
