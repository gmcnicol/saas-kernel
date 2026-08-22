package io.github.gmcnicol.kernel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CanonicalCodecTests {
    private static final SubjectType<Identifier> SUBJECT =
            new SubjectType<>("test.Identifier", Identifier.class, Identifier::value);
    private static final ProjectionType<Identifier, Example> TYPE = new ProjectionType<>(
            "test.Example", 3, SUBJECT, Example.class, List.of());

    @Test
    void canonicalEvidenceHasStableShapeAndRoundTripsThroughClosedDescriptor() {
        var codec = new CanonicalCodec(List.of(TYPE));
        var value = new Example(
                new Identifier("é"), Optional.empty(), List.of(2, 1), new BigDecimal("1.2300"),
                Status.Active, Instant.parse("2026-08-16T09:10:11Z"));

        var evidence = codec.encode(TYPE, value);

        assertThat(evidence.qualifiedType()).isEqualTo("test.Example");
        assertThat(evidence.contractVersion()).isEqualTo(3);
        assertThat(evidence.formatVersion()).isEqualTo(1);
        assertThat(evidence.canonicalJson()).isEqualTo(
                "{\"absent\":null,\"amount\":1.23,\"id\":\"é\",\"order\":[2,1],\"status\":\"Active\",\"when\":\"2026-08-16T09:10:11Z\"}");
        assertThat(evidence.checksum()).isEqualTo("49dde5f6fa3a3d39fc77fc5f5ce3c5117b4a9829e73d8da724e3ceadd702199b");
        assertThat(codec.decode(TYPE, evidence)).isEqualTo(new Example(
                new Identifier("é"), Optional.empty(), List.of(2, 1), new BigDecimal("1.23"),
                Status.Active, Instant.parse("2026-08-16T09:10:11Z")));
    }

    @Test
    void decodingFailsClosedBeforeApplicationTypesSeeMalformedEvidence() {
        var codec = new CanonicalCodec(List.of(TYPE));
        var wrongChecksum = new CanonicalEvidence(
                "unknown.Type", 1, 1, "{".getBytes(StandardCharsets.UTF_8), "0".repeat(64));
        assertThatThrownBy(() -> codec.decode(TYPE, wrongChecksum))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Semantic evidence checksum mismatch");

        var valid = codec.encode(TYPE, new Example(
                new Identifier("x"), Optional.empty(), List.of(), BigDecimal.ONE, Status.Active, Instant.EPOCH));
        var unknown = new ProjectionType<>("test.Unknown", 1, SUBJECT, Example.class, List.of());
        assertThatThrownBy(() -> codec.decode(unknown, valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");

        assertRejected(codec, "{\"absent\":null,\"amount\":1,\"id\":\"x\",\"id\":\"y\",\"order\":[],\"status\":\"Active\",\"when\":\"1970-01-01T00:00:00Z\"}");
        assertRejected(codec, "{\"absent\":null,\"amount\":1,\"extra\":true,\"id\":\"x\",\"order\":[],\"status\":\"Active\",\"when\":\"1970-01-01T00:00:00Z\"}");
        assertRejected(codec, valid.canonicalJson() + " true");
        assertRejected(codec, "{\"@class\":\"java.lang.Runtime\"}");
        assertThatThrownBy(() -> codec.decode(TYPE, evidence(new byte[] {(byte) 0xc3, (byte) 0x28})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredLimitsCanTightenButNeverLoosenKernelCeilings() {
        assertThatThrownBy(() -> CanonicalCodec.Limits.defaults().tightened(
                1_048_577, 32, 256, 10_000, 65_536))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalCodec.Limits(1_048_577, 32, 256, 10_000, 65_536))
                .isInstanceOf(IllegalArgumentException.class);

        var stringCodec = new CanonicalCodec(List.of(TYPE),
                CanonicalCodec.Limits.defaults().tightened(1_024, 8, 8, 8, 1));
        assertThatThrownBy(() -> stringCodec.encode(TYPE, new Example(
                new Identifier("xx"), Optional.empty(), List.of(), BigDecimal.ONE, Status.Active, Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("String");

        var fieldCodec = new CanonicalCodec(List.of(TYPE),
                CanonicalCodec.Limits.defaults().tightened(1_024, 8, 5, 8, 64));
        assertThatThrownBy(() -> fieldCodec.encode(TYPE, example(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field limit");

        var collectionCodec = new CanonicalCodec(List.of(TYPE),
                CanonicalCodec.Limits.defaults().tightened(1_024, 8, 8, 1, 64));
        assertThatThrownBy(() -> collectionCodec.encode(TYPE, example(List.of(1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection limit");

        var byteCodec = new CanonicalCodec(List.of(TYPE),
                CanonicalCodec.Limits.defaults().tightened(64, 8, 8, 8, 64));
        assertThatThrownBy(() -> byteCodec.encode(TYPE, example(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document length");

        var nestedType = new ProjectionType<>("test.Nested", 1, SUBJECT, Nested.class, List.of());
        var depthCodec = new CanonicalCodec(List.of(nestedType),
                CanonicalCodec.Limits.defaults().tightened(1_024, 2, 8, 8, 64));
        assertThatThrownBy(() -> depthCodec.encode(nestedType, new Nested(new Nested(null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth limit");

        var doubleType = new ProjectionType<>("test.DoubleValue", 1, SUBJECT, DoubleValue.class, List.of());
        var doubleCodec = new CanonicalCodec(List.of(doubleType));
        assertThatThrownBy(() -> doubleCodec.encode(doubleType, new DoubleValue(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readersAndWritersArePreparedOnceAndReused() throws ReflectiveOperationException {
        var codec = new CanonicalCodec(List.of(TYPE));
        var readers = field(codec, "readers");
        var writers = field(codec, "writers");
        var evidence = codec.encode(TYPE, example(List.of(1)));

        codec.decode(TYPE, evidence);
        codec.encode(TYPE, example(List.of(2)));

        assertThat(field(codec, "readers")).isSameAs(readers);
        assertThat(field(codec, "writers")).isSameAs(writers);
    }

    private static Object field(CanonicalCodec codec, String name) throws ReflectiveOperationException {
        var field = CanonicalCodec.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(codec);
    }

    private static Example example(List<Integer> order) {
        return new Example(
                new Identifier("x"), Optional.empty(), order, BigDecimal.ONE, Status.Active, Instant.EPOCH);
    }

    private static void assertRejected(CanonicalCodec codec, String json) {
        assertThatThrownBy(() -> codec.decode(TYPE, evidence(json.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CanonicalEvidence evidence(byte[] content) {
        return new CanonicalEvidence("test.Example", 3, 1, content, CanonicalCodec.sha256(content));
    }

    record Identifier(@JsonValue String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        Identifier {}
    }

    record Example(
            Identifier id,
            Optional<String> absent,
            List<Integer> order,
            BigDecimal amount,
            Status status,
            Instant when) {}

    enum Status { Active }

    record Nested(Nested child) {}

    record DoubleValue(double value) {}
}
