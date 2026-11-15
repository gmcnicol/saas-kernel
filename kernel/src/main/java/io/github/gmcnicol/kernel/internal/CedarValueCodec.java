package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.value.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
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
                    writeValue(json, value, 1, null);
                }
            }).addSerializer(CedarValueSchema.CheckedRecord.class, new ValueSerializer<CedarValueSchema.CheckedRecord>() {
                @Override public void serialize(CedarValueSchema.CheckedRecord record, JsonGenerator json, SerializationContext context) {
                    json.writeStartObject();
                    for (var entry : record.values().entrySet()) {
                        json.writeName(entry.getKey());
                        writeValue(json, entry.getValue(), 1, record.type().field(entry.getKey()));
                    }
                    json.writeEndObject();
                }
            }))
            .disable(SerializationFeature.WRAP_EXCEPTIONS)
            .build();
    private CedarValueCodec() {}

    static void validate(CedarValueSchema.CheckedRecord attributes, CedarValueSchema.CheckedRecord context) {
        // Workflow values are admitted individually; do not impose a new aggregate read-wire ceiling.
        JSON.writeValue(OutputStream.nullOutputStream(), List.of(attributes, context));
    }

    private static void writeValue(JsonGenerator json, Value value, int depth, CedarValueSchema.Type type) {
        if (depth > 64) throw new ReadEvaluationLimit(Reason.BUDGET);
        if (type != null) type.check(value);
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
                    writeValue(json, entry.getValue(), depth + 2, type == null ? null : type.field(entry.getKey()));
                }
                json.writeEndObject();
            }
            case CedarList list -> {
                json.writeStartArray();
                for (var item : list) writeValue(json, item, depth + 1, type == null ? null : type.element());
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
