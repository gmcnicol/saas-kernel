package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.Set;

/** Application-owned Cedar surface expressed only with generated descriptors. */
public record TypedAuthorisationModel<P>(
        ProjectionType<?, P> projectionType,
        Set<FieldType<P, ?>> fields,
        Set<FactType<?>> facts) {
    public TypedAuthorisationModel(ProjectionType<?, P> projectionType, Set<FieldType<P, ?>> fields) {
        this(projectionType, fields, Set.of());
    }

    public TypedAuthorisationModel {
        Objects.requireNonNull(projectionType, "projectionType");
        fields = Set.copyOf(fields);
        facts = Set.copyOf(facts);
        if (!projectionType.fields().containsAll(fields)) {
            throw new IllegalArgumentException("Authorisation fields must belong to the generated Projection");
        }
        if (facts.stream().anyMatch(fact -> fact.projectionType() != projectionType)) {
            throw new IllegalArgumentException("Authorisation Facts must belong to the generated Projection");
        }
    }
}
