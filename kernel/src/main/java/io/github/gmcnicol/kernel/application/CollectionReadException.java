package io.github.gmcnicol.kernel.application;

/** Fixed, cause-free failure. No failed operation releases a partial page or continuation. */
public final class CollectionReadException extends RuntimeException {
    public enum Reason { DENIED, DEPENDENCY, STALE, BUDGET, CANCELLED, UNSUPPORTED }
    private final Reason reason;
    public CollectionReadException(Reason reason) { super("Collection read failed: " + reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
