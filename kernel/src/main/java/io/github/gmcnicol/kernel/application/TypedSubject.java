package io.github.gmcnicol.kernel.application;

import java.util.Objects;

/** Subject identity carried by one generated Taxi scalar. */
public record TypedSubject<I>(SubjectType<I> type, I id) {

    public TypedSubject {
        Objects.requireNonNull(type, "type");
        type.externalId(id);
    }

    public String externalId() {
        return type.externalId(id);
    }
}
