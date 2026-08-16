package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.function.Function;

/** Generated identity and durable text conversion for one Taxi Subject scalar. */
public final class SubjectType<I> {
    private final String qualifiedName;
    private final Class<I> javaType;
    private final Function<I, String> externalId;

    public SubjectType(String qualifiedName, Class<I> javaType, Function<I, String> externalId) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Subject type requires qualified Taxi identity");
        }
        this.qualifiedName = qualifiedName;
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.externalId = Objects.requireNonNull(externalId, "externalId");
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    public Class<I> javaType() {
        return javaType;
    }

    public String externalId(I value) {
        String id = externalId.apply(javaType.cast(Objects.requireNonNull(value, "value")));
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Subject ID must not be blank");
        return id;
    }
}
