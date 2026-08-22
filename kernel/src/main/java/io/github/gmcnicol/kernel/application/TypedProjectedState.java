package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Application-owned typed Projected State supplied to Kernel evaluation. */
public record TypedProjectedState<I, P>(
        String tenantId,
        TypedSubject<I> subject,
        long version,
        ProjectionType<I, P> type,
        P value) {

    public TypedProjectedState {
        if (tenantId == null || tenantId.isBlank() || version < 0) {
            throw new IllegalArgumentException("Typed Projected State requires tenant and non-negative version");
        }
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(type, "type");
        value = type.javaType().cast(Objects.requireNonNull(value, "value"));
        if (subject.type() != type.subjectType()) {
            throw new IllegalArgumentException("Projected State Subject descriptor does not match Projection");
        }
    }
}
