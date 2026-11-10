package io.github.gmcnicol.kernel.application;

/** Typed immutable authorised leaves, distinct from generated raw candidates. Not an authority token. */
public interface AuthorisedRecord<P> {
    <V> ReadValue<V> get(ReadPath<P, V> path);
}
