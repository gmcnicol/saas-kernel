package io.github.gmcnicol.kernel.application;

/** Closed generated identity for one durable Taxi Java Binding. */
public interface SemanticType<T> {

    String qualifiedName();

    int contractVersion();

    Class<T> javaType();
}
