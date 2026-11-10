package review;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Throwaway adapter, not wired into Kernel or either Application. SINGLE DECISIONS, NOT A BATCH. */
public final class PostgresCedarRead implements ScopedRead {
    private final Supplier<Connection> connections;
    private final Resolver resolver;
    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final Schema schema;
    private final PolicySet policies;
    // Operator-only benchmark counters; never returned in consumer pages/errors.
    long cedarCalls;
    long candidates;

    public PostgresCedarRead(Supplier<Connection> connections, Resolver resolver) {
        this.connections = connections;
        this.resolver = resolver;
        try {
            schema = new Schema(resource("schema.cedarschema"));
            policies = PolicySet.parsePolicies(resource("policies.cedar"));
        } catch (Exception e) { throw new IllegalStateException("Authorisation unavailable"); }
    }
    @Override public <R> List<View<R>> read(Scope scope, Model<R> model, Query<R> query) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) { statement.execute("SET LOCAL ROLE review_runtime"); }
                try (var statement = connection.prepareStatement("SELECT set_config('review.tenant', ?, true)")) {
                    statement.setString(1, scope.tenant()); statement.execute();
                }
                var now = Instant.now();
                var authority = resolver.current(scope, now);
                if (!scope.equals(authority.scope()) || authority.evaluatedAt().isAfter(now)
                        || !authority.expiresAt().isAfter(now) || authority.revision().isBlank()) throw new IllegalStateException();
                var principal = new Entity(uid("Principal", key(scope.tenant(), scope.principal())), Map.of(
                        "tenant", new PrimString(scope.tenant()), "scope", new PrimString(scope.resourceScope()),
                        "kind", new PrimString(scope.principalKind()), "actor", new PrimString(scope.principal()),
                        "enabled", review.bindings.review.EffectiveRead.ENABLED.toCedar(authority.capability().enabled()).orElseThrow()), Set.of());
                List<R> rows = query.select(connection);
                if (rows.size() > 32 || model.leaves().size() > 8) throw new IllegalStateException();
                candidates += rows.size();
                var result = new ArrayList<View<R>>();
                for (R row : rows) {
                    Identity id = model.identity().apply(row);
                    if (!id.tenant().equals(scope.tenant())) throw new IllegalStateException();
                    var resource = new Entity(uid("Resource", key(id.tenant(), id.scope(), id.qualifiedType(), id.id())), Map.of(
                            "tenant", new PrimString(id.tenant()), "scope", new PrimString(id.scope()),
                            "model", new PrimString(id.qualifiedType()), "owner", new PrimString(id.owner())), Set.of());
                    if (!allows(principal, resource, "read")) continue;
                    var fields = new LinkedHashMap<String, String>();
                    for (Leaf<R> leaf : model.leaves()) {
                        if (allows(principal, resource, leaf.qualifiedPath())) fields.put(leaf.qualifiedPath(), leaf.value().apply(row));
                    }
                    result.add(new View<>(fields));
                }
                return List.copyOf(result);
            } finally { connection.rollback(); }
        } catch (Exception e) { throw new IllegalStateException("Authorised read unavailable"); }
    }
    private boolean allows(Entity principal, Entity resource, String action) throws Exception {
        cedarCalls++;
        var response = engine.isAuthorized(new AuthorizationRequest(principal.getEUID(), uid("Action", action),
                resource.getEUID(), Optional.of(Map.of()), Optional.of(schema), true), policies, Set.of(principal, resource));
        var success = response.success.orElseThrow();
        if (!success.getErrors().isEmpty() || !response.warnings.isEmpty()) throw new IllegalStateException();
        return success.isAllowed();
    }
    private static EntityUID uid(String type, String id) {
        return new EntityUID(EntityTypeName.parse(type).orElseThrow(), id);
    }
    private static String key(String... parts) {
        var value = new StringBuilder();
        for (String part : parts) value.append(part.length()).append(':').append(part);
        return value.toString();
    }
    private static String resource(String name) throws Exception {
        try (var stream = PostgresCedarRead.class.getResourceAsStream("/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
