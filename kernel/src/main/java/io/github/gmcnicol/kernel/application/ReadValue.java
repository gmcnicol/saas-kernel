package io.github.gmcnicol.kernel.application;

/** Hidden and missing are identical; a permitted null is distinct. */
public record ReadValue<V>(boolean visible, V value) {
    public ReadValue {
        if (!visible && value != null) throw new IllegalArgumentException("Hidden read value");
    }
    public static <V> ReadValue<V> hidden() { return new ReadValue<>(false, null); }
    public static <V> ReadValue<V> shown(V value) { return new ReadValue<>(true, value); }
}
