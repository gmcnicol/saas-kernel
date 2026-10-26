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
import io.github.gmcnicol.kernel.application.CapabilityType;
import io.github.gmcnicol.kernel.application.TypedCapability;
import io.github.gmcnicol.kernel.application.EffectiveCapabilityResolver;
import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.CollectionReadException;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.FactSet;
import io.github.gmcnicol.kernel.application.FieldType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedSubject;
import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CedarAuthoriser implements AutoCloseable {
    private static final String TYPED_ACTION_PREFIX = "typed/v1/";

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final ReadPolicyEvaluator readEvaluator;
    private final Schema schema;
    private final PolicySet policies;
    private final List<TypedAuthorisationModel<?>> typedModels;
    private final String bundleId;
    private final String bundleChecksum;
    private final List<CapabilityType<?>> capabilities;
    private final List<EffectiveCapabilityResolver> resolvers;
    private final Clock clock;

    CedarAuthoriser(
            Schema schema,
            PolicySet policies,
            List<TypedAuthorisationModel<?>> typedModels,
            String bundleId,
            String bundleChecksum) {
        this(schema, policies, typedModels, bundleId, bundleChecksum, List.of(), List.of(), Clock.systemUTC());
    }

    CedarAuthoriser(
            Schema schema, PolicySet policies, List<TypedAuthorisationModel<?>> typedModels,
            String bundleId, String bundleChecksum, List<SemanticBindings> bindings,
            List<EffectiveCapabilityResolver> resolvers, Clock clock) {
        this(schema, policies, typedModels, bundleId, bundleChecksum, bindings, resolvers, clock,
                System.getProperty("kernel.authorisation.read-evaluator", "auto"), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    CedarAuthoriser(
            Schema schema, PolicySet policies, List<TypedAuthorisationModel<?>> typedModels,
            String bundleId, String bundleChecksum, List<SemanticBindings> bindings,
            List<EffectiveCapabilityResolver> resolvers, Clock clock, String selection,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.capabilities = bindings.stream().flatMap(binding -> binding.capabilities().stream()).toList();
        this.resolvers = List.copyOf(resolvers);
        this.clock = clock;
        if (capabilities.stream().map(CapabilityType::name).distinct().count() != capabilities.size()) {
            throw new IllegalArgumentException("Capability policy names must be unique in assembled bindings");
        }
        typedModels.forEach(model -> model.requiredCapabilities().forEach(required -> {
            if (capabilities.stream().noneMatch(type -> type == required)
                    || bindings.stream().flatMap(binding -> binding.projections().stream())
                            .noneMatch(type -> type == model.projectionType())) {
                throw new IllegalArgumentException("Capability model requires assembled generated descriptors");
            }
        }));
        this.schema = schema;
        this.policies = policies;
        this.typedModels = List.copyOf(typedModels);
        if (this.typedModels.stream().map(TypedAuthorisationModel::projectionType).distinct().count()
                != this.typedModels.size()) {
            throw new IllegalArgumentException("Duplicate typed Authorisation model");
        }
        this.bundleId = bundleId;
        this.bundleChecksum = bundleChecksum;
        this.readEvaluator = ReadPolicyEvaluator.create(schema, policies, selection, meters);
    }

    @Override public void close() { readEvaluator.close(); }

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

    <P> Map<String, Value> capabilityContext(
            String tenantId, Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType) {
        return capabilityAuthority(tenantId, principal, subject, projectionType).values();
    }

    record CapabilityAuthority(Map<String, Value> values, String revision) {}

    <P> CapabilityAuthority capabilityAuthority(
            String tenantId, Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType) {
        return capabilityAuthority(tenantId, principal, subject, projectionType, false);
    }

    <P> CapabilityAuthority capabilityAuthority(
            String tenantId, Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType, boolean classifyReadFailure) {
        if (model(projectionType).requiredCapabilities().isEmpty()) return new CapabilityAuthority(Map.of(), "");
        try {
            if (resolvers.size() != 1) throw new AuthorisationDeniedException();
            var request = new EffectiveCapabilityResolver.Request(
                    new EffectiveCapabilityResolver.Scope(tenantId, principal, subject), clock.instant());
            EffectiveCapabilityResolver.Resolution resolution;
            try { resolution = resolvers.getFirst().resolve(request); }
            catch (RuntimeException dependency) {
                if (classifyReadFailure) throw new CollectionReadException(CollectionReadException.Reason.DEPENDENCY);
                throw new AuthorisationDeniedException();
            }
            var context = resolution.context();
            if (context.formatVersion() != 1 || !request.scope().equals(context.scope())
                    || context.scope().resource().type() != subject.type()
                    || !request.evaluatedAt().equals(context.evaluatedAt())
                    || !context.expiresAt().isAfter(request.evaluatedAt())
                    || !context.expiresAt().isAfter(clock.instant())
                    || resolution.currentRevision() == null || resolution.currentRevision().isBlank()
                    || !resolution.currentRevision().equals(context.revision())) {
                throw new AuthorisationDeniedException();
            }
            var values = new LinkedHashMap<String, Value>();
            for (TypedCapability<?> capability : context.capabilities()) {
                if (capabilities.stream().noneMatch(type -> type == capability.type())
                        || values.putIfAbsent(capability.type().name(), capabilityRecord(capability)) != null) {
                    throw new AuthorisationDeniedException();
                }
            }
            if (model(projectionType).requiredCapabilities().stream()
                    .anyMatch(type -> !values.containsKey(type.name()))) {
                throw new AuthorisationDeniedException();
            }
            return new CapabilityAuthority(Map.of("capabilities", new CedarMap(values)), context.revision());
        } catch (RuntimeException exception) {
            if (classifyReadFailure) {
                if (exception instanceof CollectionReadException safe) throw safe;
                throw new CollectionReadException(CollectionReadException.Reason.DENIED);
            }
            throw new AuthorisationDeniedException();
        }
    }

    private static <C> Value capabilityRecord(TypedCapability<C> capability) {
        var attributes = new LinkedHashMap<String, Value>();
        C value = capability.type().javaType().cast(capability.value());
        capability.type().fields().forEach(field -> factAttribute(attributes, field, value));
        return new CedarMap(attributes);
    }

    private <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, String operation) {
        return allows(principal, subject, projectionType, projection, facts, operation, Map.of());
    }

    <P> boolean allows(
            Principal principal, TypedSubject<?> subject, ProjectionType<?, P> projectionType,
            P projection, FactSet facts, String operation, Map<String, Value> context) {
        try {
            TypedAuthorisationModel<P> model = model(projectionType);
            Entity principalEntity = entity(principal.type(), principal.id());
            Entity resourceEntity = entity(
                    simpleName(subject.type().qualifiedName()), subject.externalId(), attributes(model, projection, facts));
            var request = new AuthorizationRequest(
                    principalEntity.getEUID(), euid("Action", TYPED_ACTION_PREFIX + operation),
                    resourceEntity.getEUID(), Optional.of(context), Optional.of(schema), true);
            return engine.isAuthorized(request, policies, Set.of(principalEntity, resourceEntity))
                    .success.map(response -> response.isAllowed()
                            && (model.requiredCapabilities().isEmpty() || response.getErrors().isEmpty()))
                    .orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }

    <P> boolean allowsRead(io.github.gmcnicol.kernel.application.ReadRequest<P> read,
            io.github.gmcnicol.kernel.application.ReadCandidate<P> candidate, String operation,
            Map<String, Value> authority, long deadline) {
        try {
            String identity = key(read.tenantId(), candidate.scope().type().qualifiedName(), candidate.scope().externalId(),
                    read.type().qualifiedName(), Integer.toString(read.type().contractVersion()),
                    candidate.record().type().qualifiedName(), candidate.record().externalId());
            return strictReadDecision(read, simpleName(candidate.record().type().qualifiedName()), identity,
                    attributes(model(read.type()), candidate.value(), FactSet.empty()), operation, authority, deadline);
        } catch (ReadEvaluationLimit limit) { throw new CollectionReadException(limit.reason); }
        catch (Exception failure) { throw new AuthorisationDeniedException(); }
    }

    <P> boolean allowsReadQuery(io.github.gmcnicol.kernel.application.ReadRequest<P> read,
            io.github.gmcnicol.kernel.application.ReadPath<P, ?> path, Map<String, Value> authority, long deadline) {
        try {
            return strictReadDecision(read, simpleName(read.scope().type().qualifiedName()),
                    key(read.tenantId(), read.scope().type().qualifiedName(), read.scope().externalId()), Map.of(),
                    path.qualifiedName() + ".query", authority, deadline);
        } catch (ReadEvaluationLimit limit) { throw new CollectionReadException(limit.reason); }
        catch (Exception failure) { throw new AuthorisationDeniedException(); }
    }

    private boolean strictReadDecision(io.github.gmcnicol.kernel.application.ReadRequest<?> read,
            String resourceType, String resourceId, Map<String, Value> attributes, String operation,
            Map<String, Value> authority, long deadline) throws Exception {
        var context = new LinkedHashMap<String, Value>(authority);
        context.put("readScope", new com.cedarpolicy.value.PrimString(read.scope().externalId()));
        var result = readEvaluator.evaluate(read.principal(), resourceType, resourceId, attributes,
                TYPED_ACTION_PREFIX + operation, context, deadline);
        return switch (result) {
            case ALLOW -> true;
            case DENY -> false;
            case INVALID -> throw new AuthorisationDeniedException();
            case EXPIRED -> throw new ReadEvaluationLimit(CollectionReadException.Reason.BUDGET);
        };
    }

    private static String key(String... parts) {
        var key = new StringBuilder();
        for (String part : parts) key.append(part.length()).append(':').append(part);
        return key.toString();
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

    @SuppressWarnings("unchecked")
    private static <P> TypedAuthorisationModel<P> cast(TypedAuthorisationModel<?> model) {
        return (TypedAuthorisationModel<P>) model;
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
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
