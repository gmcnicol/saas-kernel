package io.github.gmcnicol.kernel.internal;

import java.util.concurrent.atomic.LongAdder;

/** THROWAWAY aggregate counters. No payload, identity or SDK diagnostics. */
public final class PrototypeReadMetrics {
    static final LongAdder prep = new LongAdder(), encoding = new LongAdder(), nativeNanos = new LongAdder(),
            decoding = new LongAdder(), invocations = new LongAdder(), checks = new LongAdder();
    private PrototypeReadMetrics() {}
    public static long[] snapshot() { return new long[] { prep.sum(), encoding.sum(), nativeNanos.sum(), decoding.sum(), invocations.sum(), checks.sum() }; }
}
