package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.SemanticType;
import java.util.Objects;
import java.util.function.Function;

/** Application-owned forward adapter between two exact generated durable contracts. */
public interface TypedSemanticAdapter<S, T> {

    SemanticType<S> source();

    SemanticType<T> target();

    T adapt(S value);

    static <S, T> TypedSemanticAdapter<S, T> of(
            SemanticType<S> source, SemanticType<T> target, Function<S, T> adapter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(adapter, "adapter");
        if (source == target || target.contractVersion() <= source.contractVersion()) {
            throw new IllegalArgumentException("Typed semantic adapter requires a forward contract step");
        }
        return new TypedSemanticAdapter<>() {
            @Override public SemanticType<S> source() { return source; }
            @Override public SemanticType<T> target() { return target; }
            @Override public T adapt(S value) { return Objects.requireNonNull(adapter.apply(value)); }
        };
    }
}
