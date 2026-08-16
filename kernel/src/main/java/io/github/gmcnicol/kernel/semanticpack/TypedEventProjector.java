package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.TypedTransitionProvenance;

/** Application-owned transactional projection of one validated typed Event. */
public interface TypedEventProjector<P, E> {
    EventType<E> eventType();
    void project(TypedTransitionProvenance<P, E> transition);
}
