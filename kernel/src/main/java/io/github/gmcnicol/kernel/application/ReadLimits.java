package io.github.gmcnicol.kernel.application;

/** Hard maxima, not latency promises. Application must stream mapping and bound individual SQL values. */
public record ReadLimits(int candidates, int fields, int outputBytes, int timeoutMillis) {
    public static final ReadLimits DEFAULT = new ReadLimits(128, 32, 1_048_576, 5_000);
    public ReadLimits {
        if (candidates < 1 || candidates > 128 || fields < 1 || fields > 32
                || outputBytes < 2 || outputBytes > 1_048_576 || timeoutMillis < 1 || timeoutMillis > 5_000) {
            throw new IllegalArgumentException("Invalid authorised read limits");
        }
    }
}
