package example;

import example.bindings.example.directory.*;
import io.github.gmcnicol.kernel.application.*;
import java.sql.Connection;
import java.util.*;
import java.util.function.Consumer;

/** Called only after trusted authentication. SQL/mapping remain Application-owned. */
final class DirectoryQueries {
    private final String policy;
    DirectoryQueries(String policy) { this.policy = policy; }
    ReadRequest<Entry> request(String tenant, Principal principal, String directory) {
        return new ReadRequest<>(tenant, principal, new TypedSubject<>(DirectoryId.TYPE, new DirectoryId(directory)),
                Entry.TYPE, Set.of(Entry.READ_DETAILS_NAME, Entry.READ_DETAILS_PRIVATE_NOTE, Entry.READ_REVIEWED),
                "example.adoption.authorisation", policy, new ReadLimits(16, 3, 4096, 2000));
    }
    ReadSource<Entry> source(ReadRequest<Entry> request) {
        return new ReadSource<>() {
            public String revision(Connection c) throws Exception {
                try (var q = c.prepareStatement("SELECT revision FROM source_revision WHERE scope_id = ? AND principal_id = ?")) {
                    q.setString(1, request.scope().externalId()); q.setString(2, request.principal().id());
                    try (var row = q.executeQuery()) { return row.next() ? row.getString(1) : null; }
                }
            }
            public void select(Connection c, Consumer<ReadCandidate<Entry>> sink) throws Exception {
                // Deliberately broad for the negative example: RLS contains tenants, Cedar filters owner/scope.
                // Real detail endpoints should additionally bind the requested entry/scope in SQL.
                try (var q = c.prepareStatement("SELECT * FROM directory_entry")) {
                    q.setFetchSize(8);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            var id = new EntryId(row.getString("id"));
                            sink.accept(new ReadCandidate<>(row.getString("tenant_id"),
                                    new TypedSubject<>(DirectoryId.TYPE, new DirectoryId(row.getString("directory"))),
                                    new TypedSubject<>(EntryId.TYPE, id), new Entry(id, row.getString("directory"), row.getString("owner"),
                                    new Details(row.getString("name"), Optional.ofNullable(row.getString("private_note"))), row.getBoolean("reviewed"))));
                        }
                    }
                }
            }
        };
    }
}
