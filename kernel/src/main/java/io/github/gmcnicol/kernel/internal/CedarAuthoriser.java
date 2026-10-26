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

final class CedarAuthoriser {
    private static final String TYPED_ACTION_PREFIX = "typed/v1/";

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final PreparedReadEvaluator prepared;
    private final String prototypeMode = System.getProperty("kernel.prototype.evaluator", "sdk");
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
        if (!Set.of("sdk", "sdk-profile", "scalar", "batch").contains(prototypeMode)) throw new IllegalArgumentException("Prototype mode");
        try { this.prepared = prototypeMode.startsWith("sdk") ? null : new PreparedReadEvaluator(schema, policies); }
        catch (Exception failure) { throw new IllegalStateException("Prototype evaluator unavailable"); }
        this.typedModels = List.copyOf(typedModels);
        if (this.typedModels.stream().map(TypedAuthorisationModel::projectionType).distinct().count()
                != this.typedModels.size()) {
            throw new IllegalArgumentException("Duplicate typed Authorisation model");
        }
        this.bundleId = bundleId;
        this.bundleChecksum = bundleChecksum;
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
            Map<String, Value> authority) {
        try {
            long started = System.nanoTime();
            String identity = key(read.tenantId(), candidate.scope().type().qualifiedName(), candidate.scope().externalId(),
                    read.type().qualifiedName(), Integer.toString(read.type().contractVersion()),
                    candidate.record().type().qualifiedName(), candidate.record().externalId());
            Entity resource = entity(simpleName(candidate.record().type().qualifiedName()), identity,
                    attributes(model(read.type()), candidate.value(), FactSet.empty()));
            PrototypeReadMetrics.prep.add(System.nanoTime() - started);
            return strictReadDecision(read, resource, operation, authority);
        } catch (Exception failure) { throw new AuthorisationDeniedException(); }
    }

    <P> boolean allowsReadQuery(io.github.gmcnicol.kernel.application.ReadRequest<P> read,
            io.github.gmcnicol.kernel.application.ReadPath<P, ?> path, Map<String, Value> authority) {
        try {
            Entity resource = entity(simpleName(read.scope().type().qualifiedName()),
                    key(read.tenantId(), read.scope().type().qualifiedName(), read.scope().externalId()), Map.of());
            return strictReadDecision(read, resource, path.qualifiedName() + ".query", authority);
        } catch (Exception failure) { throw new AuthorisationDeniedException(); }
    }

    private boolean strictReadDecision(io.github.gmcnicol.kernel.application.ReadRequest<?> read,
            Entity resource, String operation, Map<String, Value> authority) throws Exception {
        long started = System.nanoTime();
        Entity principal = entity(read.principal().type(), read.principal().id());
        var context = new LinkedHashMap<String, Value>(authority);
        context.put("readScope", new com.cedarpolicy.value.PrimString(read.scope().externalId()));
        var request = new AuthorizationRequest(principal.getEUID(), euid("Action", TYPED_ACTION_PREFIX + operation),
                resource.getEUID(), Optional.of(context), Optional.of(schema), true);
        PrototypeReadMetrics.prep.add(System.nanoTime() - started);
        var result = evaluateRead(request, Set.of(principal, resource));
        return strict(result);
    }

    private com.cedarpolicy.model.AuthorizationResponse evaluateRead(AuthorizationRequest request, Set<Entity> entities) throws Exception {
        if (prototypeMode.equals("sdk-profile")) return PreparedReadEvaluator.profileSdk(request, policies, entities);
        if (prepared != null) return prepared.evaluate(List.of(new PreparedReadEvaluator.Input(request, entities)), 0).getFirst();
        long started = System.nanoTime();
        var result = engine.isAuthorized(request, policies, entities);
        PrototypeReadMetrics.nativeNanos.add(System.nanoTime() - started); // SDK includes encoding/decoding, not native-only.
        PrototypeReadMetrics.invocations.increment(); PrototypeReadMetrics.checks.increment();
        return result;
    }

    private static boolean strict(com.cedarpolicy.model.AuthorizationResponse result) {
        if (!result.warnings.isEmpty() || result.success.isEmpty() || !result.success.get().getErrors().isEmpty())
            throw new AuthorisationDeniedException();
        return result.success.get().isAllowed();
    }

    /** THROWAWAY immutable candidate frame; never retained across candidates, reads or release fences. */
    <P> ReadFrame<P> readFrame(io.github.gmcnicol.kernel.application.ReadRequest<P> read,
            io.github.gmcnicol.kernel.application.ReadCandidate<P> candidate, Map<String, Value> authority, long deadline) {
        return new ReadFrame<>(read, candidate, authority, deadline);
    }
    final class ReadFrame<P> {
        final io.github.gmcnicol.kernel.application.ReadRequest<P> read;
        final io.github.gmcnicol.kernel.application.ReadCandidate<P> candidate;
        final Map<String, Value> authority;
        Entity principal, resource;
        Map<String, Value> context;
        final long deadline;
        ReadFrame(io.github.gmcnicol.kernel.application.ReadRequest<P> read,
                io.github.gmcnicol.kernel.application.ReadCandidate<P> candidate, Map<String, Value> authority, long deadline) {
            this.read = read; this.candidate = candidate; this.authority = authority; this.deadline = deadline;
        }
        private PreparedReadEvaluator.Input input(String operation) {
            long started = System.nanoTime();
            if (resource == null) {
                String identity = key(read.tenantId(), candidate.scope().type().qualifiedName(), candidate.scope().externalId(),
                        read.type().qualifiedName(), Integer.toString(read.type().contractVersion()),
                        candidate.record().type().qualifiedName(), candidate.record().externalId());
                resource = entity(simpleName(candidate.record().type().qualifiedName()), identity,
                        attributes(model(read.type()), candidate.value(), FactSet.empty()));
                principal = entity(read.principal().type(), read.principal().id());
                context = new LinkedHashMap<>(authority);
                context.put("readScope", new com.cedarpolicy.value.PrimString(read.scope().externalId()));
            }
            var q = new AuthorizationRequest(principal.getEUID(), euid("Action", TYPED_ACTION_PREFIX + operation), resource.getEUID(),
                    Optional.of(context), Optional.of(schema), true);
            PrototypeReadMetrics.prep.add(System.nanoTime() - started);
            return new PreparedReadEvaluator.Input(q, Set.of(principal, resource));
        }
        boolean allows(String operation) {
            if (prepared == null) return allowsRead(read, candidate, operation, authority);
            try { return strict(prepared.evaluate(List.of(input(operation)), 0, deadline - System.nanoTime()).getFirst()); }
            catch (Exception failure) { throw new AuthorisationDeniedException(); }
        }
        java.util.Iterator<Boolean> plan(List<String> operations, boolean gates, java.util.function.IntSupplier capacity) {
            return new java.util.Iterator<>() {
                int next;
                List<com.cedarpolicy.model.AuthorizationResponse> results;
                public boolean hasNext() { return next < operations.size(); }
                public Boolean next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    if (!prototypeMode.equals("batch")) return allows(operations.get(next++));
                    try {
                        if (results == null) {
                            int limit = Math.min(operations.size(), capacity.getAsInt());
                            if (limit < 1) throw new AuthorisationDeniedException();
                            var inputs = operations.subList(0, limit).stream().map(ReadFrame.this::input).toList();
                            results = prepared.evaluate(inputs, gates ? inputs.size() : 0, deadline - System.nanoTime());
                        }
                        return strict(results.get(next++));
                    } catch (Exception failure) { throw new AuthorisationDeniedException(); }
                }
            };
        }
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
