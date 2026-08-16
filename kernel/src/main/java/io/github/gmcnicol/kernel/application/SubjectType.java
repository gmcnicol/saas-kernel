package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.function.Function;

/** Generated identity and durable text conversion for one Taxi Subject scalar. */
public final class SubjectType<I> {
    private final String qualifiedName;
    private final Class<I> javaType;
    private final Function<I, String> externalId;
    private final Function<String, I> externalIdParser;

    public SubjectType(String qualifiedName, Class<I> javaType, Function<I, String> externalId) {
        this(qualifiedName, javaType, externalId, value -> {
            throw new IllegalStateException("Subject type cannot restore a typed external ID");
        });
    }

    public SubjectType(
            String qualifiedName,
            Class<I> javaType,
            Function<I, String> externalId,
            Function<String, I> externalIdParser) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("Subject type requires qualified Taxi identity");
        }
        this.qualifiedName = qualifiedName;
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.externalIdParser = Objects.requireNonNull(externalIdParser, "externalIdParser");
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

    public I fromExternalId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Subject ID must not be blank");
        return javaType.cast(Objects.requireNonNull(externalIdParser.apply(value), "parsed Subject ID"));
    }
}
