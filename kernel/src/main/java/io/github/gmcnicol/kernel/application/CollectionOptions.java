package io.github.gmcnicol.kernel.application;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Cancellation is cooperative: synchronous native calls must return to a checkpoint. */
public record CollectionOptions(int pageSize, int decisions, BooleanSupplier cancelled, Optional<String> continuation) {
    public CollectionOptions {
        if (pageSize < 1 || pageSize > 127 || decisions < 1 || decisions > 4224
                || cancelled == null || continuation == null) throw new IllegalArgumentException("Invalid collection limits");
    }
    public static CollectionOptions page(int size) { return new CollectionOptions(size, 4224, () -> false, Optional.empty()); }
    @Override public String toString() { return "CollectionOptions[redacted]"; }
}
