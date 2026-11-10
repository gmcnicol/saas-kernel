package io.github.gmcnicol.kernel.internal;

/** Owned, versioned JNI boundary. Handles are tokens, never native pointers. */
final class NativeCedar {
    private NativeCedar() {}
    static native String identity();
    static native long open(String bundle);
    static native long evaluate(long handle, String request, long remainingNanos);
    static native long close(long handle);
    static native long[] statistics();
}
