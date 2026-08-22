package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Cedar-filtered typed capabilities without the complete Projection. */
public record TypedAuthorisationEnvelope<I, P>(
        UUID evaluationSnapshotId,
        Instant evaluatedAt,
        String semanticPackId,
        TypedSubject<I> subject,
        ProjectionType<I, P> projectionType,
        List<TypedFieldValue<P, ?>> fields,
        List<TypedFact<?>> facts,
        List<TypedActionOffer<P, ?, ?>> actionOffers) {
    public TypedAuthorisationEnvelope {
        Objects.requireNonNull(evaluationSnapshotId, "evaluationSnapshotId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(semanticPackId, "semanticPackId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(projectionType, "projectionType");
        fields = List.copyOf(fields);
        facts = List.copyOf(facts);
        actionOffers = List.copyOf(actionOffers);
    }

    public <V> Optional<V> field(FieldType<P, V> type) {
        for (TypedFieldValue<P, ?> field : fields) {
            if (field.type() == type) return Optional.of(cast(field.value()));
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <V> V cast(Object value) {
        return (V) value;
    }
}
