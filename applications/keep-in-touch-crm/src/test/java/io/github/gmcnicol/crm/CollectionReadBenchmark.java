package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.NetworkContactRead;
import io.github.gmcnicol.kernel.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit opt-in: -Dtest=CollectionReadBenchmark#measure. Real public read boundary, not a CI threshold. */
@org.springframework.context.annotation.Import(AuthorisedReadTests.Configuration.class)
class CollectionReadBenchmark extends AuthorisedReadTests {
    @Test void measure() throws Exception {
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // Synthetic fixture only. Count actual PostgreSQL logged statements, not estimated round trips.
        admin.execute("ALTER SYSTEM SET log_statement = 'all'");
        admin.execute("SELECT pg_reload_conf()");
        Thread.sleep(100);
        for (int fields : List.of(1, 2)) for (int rows : List.of(1, 8, 32, 96)) {
            var base = request("bench-" + fields + "-" + rows);
            var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(),
                    fields == 1 ? Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME) : base.fields(), base.bundleId(), base.bundleChecksum(), base.limits());
            var index = networkIndex(request, rows + 1, 1000, false);
            var binding = new AuthorisedIndex.Binding<>(request, "1", "all-v1", Set.of());
            var source = new ReadSource<NetworkContactRead>() {
                public String revision(java.sql.Connection c) throws Exception { return index.current(c, binding).sourceRevision(); }
                public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                    index.select(c, binding, 0, rows, sink);
                }
            };
            for (String mode : List.of("many", "index")) {
                Runnable read = switch (mode) {
                    case "many" -> () -> reads.many(request, source);
                    default -> () -> reads.collection(request, index, CollectionOptions.page(rows));
                };
                for (int i = 0; i < 5; i++) read.run();
                String prior = postgres.getLogs();
                double before = mode.equals("many") ? 0 : meters.find("kernel.read.collection.decisions").tag("mode", mode).summary().totalAmount();
                read.run();
                // Logging delivery is asynchronous; timing samples below exclude this measurement pause.
                Thread.sleep(100);
                String appended = postgres.getLogs().substring(prior.length());
                long statements = appended.lines().filter(line -> line.contains("LOG:  statement:") || line.contains("LOG:  execute ")).count();
                if (statements == 0) throw new AssertionError("PostgreSQL statement logging was not active");
                double calls = mode.equals("many") ? (long) rows * (fields + 1)
                        : meters.find("kernel.read.collection.decisions").tag("mode", mode).summary().totalAmount() - before;
                var samples = new long[20];
                for (int i = 0; i < samples.length; i++) { long start = System.nanoTime(); read.run(); samples[i] = System.nanoTime() - start; }
                Arrays.sort(samples);
                System.out.printf(Locale.ROOT, "COLLECTION_BENCH rows=%d leaves=%d mode=%s cedar=%.0f sql=%d p50_ms=%.3f p95_ms=%.3f%n",
                        rows, fields, mode, calls, statements, samples[9] / 1e6, samples[18] / 1e6);
            }
        }
    }
}
