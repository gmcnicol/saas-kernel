package example;

import example.bindings.example.practice.*;
import io.github.gmcnicol.kernel.application.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** One authored authorised relation, not a generic query language or policy-to-SQL compiler. */
final class PracticeQueries {
    private final String policy;
    PracticeQueries(String policy) { this.policy = policy; }
    ReadRequest<ClientFile> request(String tenant, Principal principal, String practice) {
        return new ReadRequest<>(tenant, principal, new TypedSubject<>(PracticeId.TYPE, new PracticeId(practice)),
                ClientFile.TYPE, Set.of(ClientFile.READ_REFERENCE, ClientFile.READ_NOTES),
                "example.adoption.authorisation", policy, new ReadLimits(16, 2, 4096, 2000));
    }
    private PreparedStatement count(Connection c, ReadRequest<ClientFile> r, String prefix) throws SQLException {
        var q = c.prepareStatement("SELECT count(*) FROM client_file WHERE practice = ? AND (? OR assigned_to = ?) AND starts_with(reference, ?)");
        q.setString(1, r.scope().externalId());
        q.setBoolean(2, r.principal().type().equals("Staff"));
        q.setString(3, r.principal().id()); q.setString(4, prefix);
        return q;
    }
    private void select(Connection c, ReadRequest<ClientFile> r, String prefix, long offset, int limit, Consumer<ReadCandidate<ClientFile>> sink) throws Exception {
        // reference is UNIQUE within a practice: it is the complete stable order, with no hidden tie-breaker.
        String sql = "SELECT * FROM client_file WHERE practice = ? AND (? OR assigned_to = ?) AND starts_with(reference, ?) ORDER BY reference OFFSET ? LIMIT ?";
        try (var page = c.prepareStatement(sql)) {
            page.setString(1, r.scope().externalId()); page.setBoolean(2, r.principal().type().equals("Staff"));
            page.setString(3, r.principal().id()); page.setString(4, prefix); page.setLong(5, offset); page.setInt(6, limit);
            page.setFetchSize(8);
            try (var row = page.executeQuery()) {
                while (row.next()) {
                    var id = new FileId(row.getString("id"));
                    sink.accept(new ReadCandidate<>(row.getString("tenant_id"),
                            new TypedSubject<>(PracticeId.TYPE, new PracticeId(row.getString("practice"))), new TypedSubject<>(FileId.TYPE, id),
                            new ClientFile(id, row.getString("practice"), row.getString("assigned_to"), row.getString("reference"), Optional.ofNullable(row.getString("notes")))));
                }
            }
        }
    }
    private AuthorisedIndex.State state(Connection c, ReadRequest<ClientFile> r, String prefix) throws Exception {
        try (var q = c.prepareStatement("SELECT revision, policy_checksum, authority_revision FROM practice_view WHERE practice = ? AND principal_type = ? AND principal_id = ?")) {
            q.setString(1, r.scope().externalId()); q.setString(2, r.principal().type()); q.setString(3, r.principal().id());
            try (var row = q.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("No current authorised view");
                try (var count = count(c, r, prefix); var total = count.executeQuery()) {
                    total.next();
                    return new AuthorisedIndex.State(row.getString(1), row.getString(1), row.getString(2), row.getString(3), OptionalLong.of(total.getLong(1)));
                }
            }
        }
    }
    ReadSource<ClientFile> source(ReadRequest<ClientFile> r) {
        return new ReadSource<>() {
            public String revision(Connection c) throws Exception { return state(c, r, "").sourceRevision(); }
            public void select(Connection c, Consumer<ReadCandidate<ClientFile>> sink) throws Exception {
                PracticeQueries.this.select(c, r, "", 0, r.limits().candidates() + 1, sink);
            }
        };
    }
    AuthorisedIndex<ClientFile> search(String prefix) {
        if (prefix == null || prefix.length() > 64) throw new IllegalArgumentException("Invalid search");
        final String identity;
        try { identity = "reference-prefix-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prefix.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return new AuthorisedIndex<>() {
            public String queryIdentity() { return identity; }
            public Set<ReadPath<ClientFile, ?>> queryFields() { return Set.of(ClientFile.READ_REFERENCE); }
            public State current(Connection c, Binding<ClientFile> binding) throws Exception { return state(c, binding.request(), prefix); }
            public void select(Connection c, Binding<ClientFile> binding, long offset, int limit, Consumer<ReadCandidate<ClientFile>> sink) throws Exception {
                PracticeQueries.this.select(c, binding.request(), prefix, offset, limit, sink);
            }
        };
    }
}
