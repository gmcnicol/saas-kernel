package example;

import example.bindings.example.directory.*;
import example.bindings.example.practice.*;
import io.github.gmcnicol.kernel.application.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/** Application-owned current grant resolution. Paid/admin/preview are not Cedar attributes. */
final class CurrentCapabilities implements EffectiveCapabilityResolver {
    private final DataSource data;
    CurrentCapabilities(DataSource data) { this.data = data; }
    public Resolution resolve(Request request) {
        // A separate short scoped connection also works when workflow/read already has a transaction.
        try (var c = data.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                try (var s = c.createStatement()) {
                    s.execute("SET LOCAL ROLE kernel_runtime");
                    s.execute("SET LOCAL statement_timeout = '2s'");
                }
                try (var q = c.prepareStatement("SELECT set_config('kernel.tenant_id', ?, true)")) {
                    q.setString(1, request.scope().tenantId()); q.execute();
                }
                try (var q = c.prepareStatement("""
                        SELECT a.revision, max(g.valid_until) FILTER (WHERE g.valid_until > ?) AS valid_until
                        FROM authority_revision a LEFT JOIN access_grant g USING (tenant_id, principal_type, principal_id, scope_type, scope_id)
                        WHERE a.principal_type = ? AND a.principal_id = ? AND a.scope_type = ? AND a.scope_id = ?
                        GROUP BY a.revision
                        """)) {
                    q.setTimestamp(1, java.sql.Timestamp.from(request.evaluatedAt()));
                    q.setString(2, request.scope().principal().type()); q.setString(3, request.scope().principal().id());
                    q.setString(4, request.scope().resource().type().qualifiedName());
                    q.setString(5, request.scope().resource().externalId());
                    try (var row = q.executeQuery()) {
                        if (!row.next()) throw new IllegalStateException("No current authority");
                        String revision = row.getString("revision");
                        var until = row.getTimestamp("valid_until");
                        Instant expiry = request.evaluatedAt().plusSeconds(30);
                        if (until != null && until.toInstant().isBefore(expiry)) expiry = until.toInstant();
                        var scopeType = request.scope().resource().type();
                        TypedCapability<?> capability;
                        if (scopeType == DirectoryId.TYPE || scopeType == EntryId.TYPE) {
                            capability = new TypedCapability<>(Access.TYPE, new Access(request.scope().principal().id()));
                        } else if (scopeType == PracticeId.TYPE) {
                            capability = new TypedCapability<>(PracticeAccess.TYPE, new PracticeAccess(request.scope().principal().id()));
                        } else throw new IllegalArgumentException("Unknown capability scope");
                        return new Resolution(revision, new Context(1, request.scope(), request.evaluatedAt(), expiry,
                                revision, until == null ? List.of() : List.of(capability)));
                    }
                }
            } finally { c.rollback(); }
        } catch (Exception failure) { throw new IllegalStateException("Authority unavailable"); }
    }
}
