package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.SemanticType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Generated, reflection-free decoder for the historical flat name/value representation. */
public interface LegacySemanticDecoder<T> {
    SemanticType<T> type();
    T decode(Map<String, String> values, CanonicalCodec.Limits limits);

    static <T> LegacySemanticDecoder<T> of(
            SemanticType<T> type, Set<String> fields, Function<Fields, T> factory) {
        Objects.requireNonNull(type, "type");
        Set<String> expected = Set.copyOf(fields);
        Objects.requireNonNull(factory, "factory");
        return new LegacySemanticDecoder<>() {
            @Override public SemanticType<T> type() { return type; }
            @Override public T decode(Map<String, String> values, CanonicalCodec.Limits limits) {
                return Objects.requireNonNull(factory.apply(new Fields(values, expected, limits)));
            }
        };
    }

    final class Fields {
        private final Map<String, String> values;

        private Fields(Map<String, String> values, Set<String> expected, CanonicalCodec.Limits limits) {
            this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
            if (values.size() > limits.fields() || !expected.containsAll(values.keySet())) throw invalid();
            long bytes = 0;
            for (var entry : values.entrySet()) {
                if (entry.getValue().length() > limits.stringLength()) throw invalid();
                bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
                bytes += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > limits.bytes()) throw invalid();
            }
        }

        public <T> T required(String name, Function<String, T> parser) {
            String value = values.get(name);
            if (value == null) throw invalid();
            return parse(parser, value);
        }

        public <T> Optional<T> optional(String name, Function<String, T> parser) {
            String value = values.get(name);
            return value == null ? Optional.empty() : Optional.of(parse(parser, value));
        }

        private static <T> T parse(Function<String, T> parser, String value) {
            try {
                return Objects.requireNonNull(parser.apply(value));
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid legacy semantic evidence");
        }
    }
}
