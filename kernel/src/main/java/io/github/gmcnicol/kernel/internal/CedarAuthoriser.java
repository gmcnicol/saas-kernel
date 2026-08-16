package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.CedarMap;
import com.cedarpolicy.value.Value;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.FieldType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedSubject;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CedarAuthoriser {
    private static final String TYPED_ACTION_PREFIX = "typed/v1/";

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final Schema schema;
    private final PolicySet policies;
    private final AuthorisationModel model;
    private final List<TypedAuthorisationModel<?>> typedModels;
    private final String bundleId;
    private final String bundleChecksum;

    CedarAuthoriser(
            Schema schema,
            PolicySet policies,
            AuthorisationModel model,
            List<TypedAuthorisationModel<?>> typedModels,
            String bundleId,
            String bundleChecksum) {
        this.schema = schema;
        this.policies = policies;
        this.model = model;
        this.typedModels = List.copyOf(typedModels);
        if (this.typedModels.stream().map(TypedAuthorisationModel::projectionType).distinct().count()
                != this.typedModels.size()) {
            throw new IllegalArgumentException("Duplicate typed Authorisation model");
        }
        this.bundleId = bundleId;
        this.bundleChecksum = bundleChecksum;
    }

    boolean allows(Principal principal, Subject subject, String operation) {
        if (!model.subjectTypes().contains(subject.type())) {
            return false;
        }
        try {
            return allows(principal, model.resourceType(), subject.id(), operation);
        } catch (Exception exception) {
            return false;
        }
    }

    <P> TypedAuthorisationModel<P> model(ProjectionType<?, P> projectionType) {
        for (TypedAuthorisationModel<?> candidate : typedModels) {
            if (candidate.projectionType() == projectionType) return cast(candidate);
        }
        throw new IllegalStateException("Missing typed Authorisation model: " + projectionType.qualifiedName());
    }

    <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, FieldType<P, ?> field) {
        return allows(principal, subject, projectionType, projection, facts, field.qualifiedName());
    }

    <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, FactType<?> fact) {
        return allows(principal, subject, projectionType, projection, facts, fact.qualifiedName());
    }

    <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, ActionType<?, ?, ?> action) {
        return allows(principal, subject, projectionType, projection, facts, action.qualifiedName());
    }

    private <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, String operation) {
        try {
            TypedAuthorisationModel<P> model = model(projectionType);
            Entity principalEntity = entity(principal.type(), principal.id());
            Entity resourceEntity = entity(
                    simpleName(subject.type().qualifiedName()), subject.externalId(), attributes(model, projection, facts));
            var request = new AuthorizationRequest(
                    principalEntity.getEUID(), euid("Action", TYPED_ACTION_PREFIX + operation),
                    resourceEntity.getEUID(), Optional.of(Map.of()), Optional.of(schema), true);
            return engine.isAuthorized(request, policies, Set.of(principalEntity, resourceEntity))
                    .success.map(response -> response.isAllowed()).orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }

    private static <P> Map<String, Value> attributes(
            TypedAuthorisationModel<P> model, P projection, FactSet facts) {
        var attributes = new LinkedHashMap<String, Value>();
        model.fields().stream().sorted(java.util.Comparator.comparing(FieldType::qualifiedName))
                .forEach(field -> projectionAttribute(attributes, field, projection));
        model.facts().stream().sorted(java.util.Comparator.comparing(FactType::qualifiedName))
                .forEach(type -> factRecord(attributes, type, facts));
        return Map.copyOf(attributes);
    }

    private static <P, V> void projectionAttribute(
            Map<String, Value> attributes, FieldType<P, V> field, P projection) {
        field.toCedar(field.value(projection)).ifPresent(value -> attributes.put(field.name(), value));
    }

    private static <F> void factRecord(
            Map<String, Value> attributes, FactType<F> type, FactSet facts) {
        facts.find(type).ifPresent(value ->
                attributes.put(type.name(), new CedarMap(factAttributes(type, value))));
    }

    private static <F> Map<String, Value> factAttributes(FactType<F> type, F fact) {
        var attributes = new LinkedHashMap<String, Value>();
        type.fields().forEach(field -> factAttribute(attributes, field, fact));
        return Map.copyOf(attributes);
    }

    private static <F, V> void factAttribute(
            Map<String, Value> attributes, FieldType<F, V> field, F fact) {
        field.toCedar(field.value(fact)).ifPresent(value -> attributes.put(field.name(), value));
    }

    private boolean allows(Principal principal, String resourceType, String resourceId, String operation) {
        try {
            Entity principalEntity = entity(principal.type(), principal.id());
            Entity resourceEntity = entity(resourceType, resourceId);
            var request = new AuthorizationRequest(
                    principalEntity.getEUID(),
                    euid("Action", operation),
                    resourceEntity.getEUID(),
                    Optional.of(Map.of()),
                    Optional.of(schema),
                    true);
            return engine.isAuthorized(request, policies, Set.of(principalEntity, resourceEntity))
                    .success
                    .map(response -> response.isAllowed())
                    .orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <P> TypedAuthorisationModel<P> cast(TypedAuthorisationModel<?> model) {
        return (TypedAuthorisationModel<P>) model;
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    Map<String, String> fields() {
        return model.fields();
    }

    String bundleId() {
        return bundleId;
    }

    String bundleChecksum() {
        return bundleChecksum;
    }

    private static Entity entity(String type, String id) {
        return new Entity(euid(type, id), Map.of(), Set.of());
    }

    private static Entity entity(String type, String id, Map<String, Value> attributes) {
        return new Entity(euid(type, id), attributes, Set.of());
    }

    private static EntityUID euid(String type, String id) {
        return new EntityUID(
                EntityTypeName.parse(type).orElseThrow(() -> new IllegalArgumentException("Invalid Cedar entity type")),
                id);
    }
}
