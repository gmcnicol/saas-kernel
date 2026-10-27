package io.github.gmcnicol.kernel.application;

/** Immutable bounded output. Copies cannot mutate a subsequent send. No payload-bearing diagnostics. */
public final class DisclosureBytes {
    public static final int MAX_BYTES = 1_048_576;
    private final byte[] value;
    public DisclosureBytes(byte[] value) {
        if (value == null || value.length > MAX_BYTES) throw new AuthorisationDeniedException();
        this.value = value.clone();
    }
    public byte[] copy() { return value.clone(); }
    public int size() { return value.length; }
    @Override public String toString() { return "DisclosureBytes[redacted]"; }
}
