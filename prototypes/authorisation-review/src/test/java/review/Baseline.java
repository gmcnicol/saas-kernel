package review;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import review.ScopedRead.*;
import review.bindings.review.*;

/** Manual public-seam measurement, NOT a performance assertion and NOT batch authorisation. */
public final class Baseline {
    public static void main(String[] args) throws Exception {
        ScopedReadTest.POSTGRES.start();
        try {
            var reader = new PostgresCedarRead(ScopedReadTest::login,
                    (scope, now) -> new Authority(scope, new EffectiveRead(true), now, now.plusSeconds(30), "benchmark-1"));
            System.out.println("BASELINE java=" + Runtime.version() + " cedar=4.2.3 postgres=" + ScopedReadTest.POSTGRES.getDockerImageName());
            for (int fields : List.of(1, 3)) for (int rows : List.of(1, 8, 32)) {
                var model = new Model<>(ScopedReadTest.CONTACT.identity(), ScopedReadTest.CONTACT.leaves().subList(0, fields));
                Query<Contact> query = connection -> candidates(connection, rows);
                for (int warm = 0; warm < 10; warm++) reader.read(ScopedReadTest.MEMBER, model, query);
                long callsBefore = reader.cedarCalls;
                long[] nanos = new long[30];
                for (int iteration = 0; iteration < nanos.length; iteration++) {
                    long start = System.nanoTime();
                    reader.read(ScopedReadTest.MEMBER, model, query);
                    nanos[iteration] = System.nanoTime() - start;
                }
                Arrays.sort(nanos);
                long calls = (reader.cedarCalls - callsBefore) / nanos.length;
                System.out.printf("BASELINE rows=%d leaves=%d calls/request=%d calls/record=%.1f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
                        rows, fields, calls, (double) calls / rows, nanos[14] / 1e6, nanos[28] / 1e6, nanos[29] / 1e6);
            }
            long calls = reader.cedarCalls, rows = reader.candidates;
            reader.read(ScopedReadTest.MEMBER, ScopedReadTest.CONTACT, ScopedReadTest::contacts);
            System.out.printf("BASELINE mixed_candidates=%d single_calls=%d (record gate then leaves for allowed records only)%n",
                    reader.candidates - rows, reader.cedarCalls - calls);
        } finally { ScopedReadTest.POSTGRES.stop(); }
    }
    private static List<Contact> candidates(Connection connection, int count) throws Exception {
        var rows = new ArrayList<Contact>();
        try (var statement = connection.prepareStatement("""
                SELECT tenant, network, id || '-' || n AS id, person, revision, display_name, email, private_note
                FROM contact CROSS JOIN generate_series(1, ?) AS n
                WHERE network = 'network-a' ORDER BY n LIMIT 33
                """)) {
            statement.setInt(1, count);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) rows.add(new Contact(rs.getString("tenant"), rs.getString("network"), rs.getString("id"),
                        rs.getString("person"), rs.getLong("revision"), new ContactDetails(rs.getString("display_name"),
                        rs.getString("email"), rs.getString("private_note"))));
            }
        }
        return rows;
    }
}
