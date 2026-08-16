package io.github.gmcnicol.kernel.application;

import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Generated identity and contracts for one executable Taxi Action. */
public record ActionType<P, C, E>(
        String qualifiedName,
        ProjectionType<?, P> projectionType,
        CandidateType<C> candidateType,
        List<EventType<? extends E>> eventTypes) {
    public ActionType {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Action descriptor requires identity");
        }
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(candidateType, "candidateType");
        eventTypes = List.copyOf(eventTypes);
        if (eventTypes.isEmpty()) throw new IllegalArgumentException("Action requires at least one Event type");
    }

    public TypedCandidatePayload<C> candidate(C value) {
        return new TypedCandidatePayload<>(this, value);
    }

    public TypedApplicabilityPolicy<P> bindApplicability(BiPredicate<P, FactSet> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        return new TypedApplicabilityPolicy<>() {
            @Override public ProjectionType<?, P> projectionType() { return projectionType; }
            @Override public String target() { return qualifiedName; }
            @Override public String id() { return qualifiedName + ".applicability"; }
            @Override public boolean isApplicable(P projection, FactSet facts) {
                return implementation.test(projection, facts);
            }
        };
    }

    public TypedIntentHandler<P, C, E> bindHandler(TypedIntentHandler.Handling<P, C, E> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        return new TypedIntentHandler<>() {
            @Override public ActionType<P, C, E> actionType() { return ActionType.this; }
            @Override public List<TypedStateTransition<P, E>> handle(Intent intent, C payload, P projection) {
                return implementation.handle(intent, payload, projection);
            }
        };
    }

    public <T extends E> TypedEventProjector<P, T> bindProjector(
            EventType<T> eventType, Consumer<TypedTransitionProvenance<P, T>> implementation) {
        if (eventTypes.stream().noneMatch(type -> type == eventType)) {
            throw new IllegalArgumentException("Event descriptor is outside Action contract");
        }
        Objects.requireNonNull(implementation, "implementation");
        return new TypedEventProjector<>() {
            @Override public EventType<T> eventType() { return eventType; }
            @Override public void project(TypedTransitionProvenance<P, T> transition) {
                implementation.accept(transition);
            }
        };
    }
}
