package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.ProjectedState;

/** Application-owned transactional projection of one validated Event into its relational model. */
public interface EventProjector {

    String eventType();

    void project(ProjectedState previousState, Event event);

    static EventProjector of(String eventType, Projection projection) {
        if (eventType == null || eventType.isBlank() || projection == null) {
            throw new IllegalArgumentException("Event projector requires type and projection");
        }
        return new EventProjector() {
            @Override public String eventType() { return eventType; }
            @Override public void project(ProjectedState previousState, Event event) {
                projection.project(previousState, event);
            }
        };
    }

    @FunctionalInterface
    interface Projection {
        void project(ProjectedState previousState, Event event);
    }
}
