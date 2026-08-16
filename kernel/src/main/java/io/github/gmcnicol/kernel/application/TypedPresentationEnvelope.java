package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Published typed presentation authority and provenance. */
public record TypedPresentationEnvelope<I, P>(
        int version,
        TypedSubject<I> subject,
        ProjectionType<I, P> projectionType,
        UUID evaluationId,
        Instant evaluatedAt,
        String semanticPackId,
        List<TypedFieldValue<P, ?>> fields,
        List<TypedFact<?>> facts,
        List<TypedActionOffer<P, ?, ?>> actionOffers) {
    public TypedPresentationEnvelope {
        if (version != 1) throw new IllegalArgumentException("Unsupported typed presentation envelope version");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(projectionType, "projectionType");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(semanticPackId, "semanticPackId");
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
