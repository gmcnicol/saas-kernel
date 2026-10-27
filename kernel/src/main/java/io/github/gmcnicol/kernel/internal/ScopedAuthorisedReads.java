package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Kernel-owned read transaction; Application owns SQL, RLS, identities and mapping. */
final class ScopedAuthorisedReads implements AuthorisedReads {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DataSource dataSource;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final CedarAuthoriser cedar;
    private final List<ProjectionType<?, ?>> projections;
    private final List<SubjectType<?>> subjects;
    private final ReadContinuations continuations = new ReadContinuations();

    ScopedAuthorisedReads(DataSource dataSource, CedarAuthoriser cedar, List<SemanticBindings> bindings,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.dataSource = dataSource;
        this.meters = meters;
        this.cedar = cedar;
        this.projections = bindings.stream().flatMap(binding -> binding.projections().stream()).toList();
        this.subjects = bindings.stream().flatMap(binding -> binding.subjects().stream()).toList();
    }

    record Evidence(String sourceRevision, String authorityRevision, String fingerprint) {}
    record DisclosureRead<P>(List<AuthorisedRecord<P>> records, Evidence evidence) {}

    <P> DisclosureRead<P> disclosure(ReadRequest<P> request, ReadSource<P> source) {
        var work = new Work(null);
        work.lineage = true;
        var records = perform(request, source, work);
        return new DisclosureRead<>(records, new Evidence(work.sourceRevision, work.authorityRevision,
                java.util.HexFormat.of().formatHex(work.digest.digest())));
    }

    @Override public <P> List<AuthorisedRecord<P>> many(ReadRequest<P> request, ReadSource<P> source) {
        return perform(request, source, new Work(null));
    }

    @Override public <P> ReadPage<P> collection(ReadRequest<P> request, AuthorisedIndex<P> index, CollectionOptions options) {
        var work = new Work(Objects.requireNonNull(options));
        return observe(work, "index", () -> {
            var window = new IndexedReadWindow<>(request, index, options, continuations);
            var records = perform(request, window, work);
            var page = window.page(records);
            work.check(work.started + request.limits().timeoutMillis() * 1_000_000L);
            return page;
        });
    }

    private <T> T observe(Work work, String mode, java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        String outcome = "success";
        try { return action.get(); }
        catch (CollectionReadException safe) { outcome = safe.reason().name().toLowerCase(java.util.Locale.ROOT); throw safe; }
        catch (Throwable failure) {
            outcome = "dependency";
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath death) throw death;
            throw new CollectionReadException(CollectionReadException.Reason.DEPENDENCY);
        } finally {
            try {
                meters.counter("kernel.read.collections", "mode", mode, "outcome", outcome).increment();
                meters.summary("kernel.read.collection.decisions", "mode", mode).record(work.decisions);
                meters.summary("kernel.read.collection.candidates", "mode", mode).record(work.candidates);
                meters.summary("kernel.read.collection.bytes", "mode", mode).record(work.bytes);
                meters.timer("kernel.read.collection.duration", "mode", mode).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) { /* Operator telemetry is not authority. */ }
        }
    }

    private enum Denial { AUTHORITY, RECORD_POLICY, FIELD_POLICY }

    /** Aggregate operator telemetry only: no identifiers, values, SDK text or per-row public reasons. */
    private void denied(Denial category) {
        try { meters.counter("kernel.read.denials", "category", category.name().toLowerCase(java.util.Locale.ROOT)).increment(); }
        catch (RuntimeException ignored) { /* Telemetry is not authority. */ }
    }

    private <P> List<AuthorisedRecord<P>> perform(ReadRequest<P> request, ReadSource<P> source, Work work) {
        try {
            if (work.options != null && (!cedar.bundleId().equals(request.bundleId()) || !cedar.bundleChecksum().equals(request.bundleChecksum())))
                work.fail(CollectionReadException.Reason.STALE);
            try { validate(request); }
            catch (RuntimeException invalid) { work.fail(CollectionReadException.Reason.DENIED); }
            work.check();
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (active || resource != null || TransactionSynchronizationManager.isSynchronizationActive()) {
                if (!active || !TransactionSynchronizationManager.isSynchronizationActive() || !(resource instanceof ConnectionHolder)) {
                    throw new AuthorisationDeniedException();
                }
                var connection = DataSourceUtils.getConnection(dataSource);
                try {
                    if (!DataSourceUtils.isConnectionTransactional(connection, dataSource) || connection.getAutoCommit()) {
                        throw new AuthorisationDeniedException();
                    }
                    return scoped(connection, request, source, work);
                } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
            }
            try (var connection = dataSource.getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try { return scoped(connection, request, source, work); }
                finally { connection.rollback(); }
            }
        } catch (Throwable failure) {
            // Cleanup still runs for fatal VM/thread failures; do not pretend they are recoverable read denials.
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath death) throw death;
            if (work.options != null) {
                work.check();
                if (failure instanceof SQLException sql && "57014".equals(sql.getSQLState())) work.fail(CollectionReadException.Reason.BUDGET);
                if (failure instanceof CollectionReadException safe) throw safe;
                throw new CollectionReadException(CollectionReadException.Reason.DEPENDENCY);
            }
            throw new AuthorisationDeniedException();
        }
    }

    private <P> List<AuthorisedRecord<P>> scoped(Connection connection, ReadRequest<P> request, ReadSource<P> source, Work work) throws Exception {
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) throw new AuthorisationDeniedException();
        long deadline = work.started + request.limits().timeoutMillis() * 1_000_000L;
        work.check(deadline);
        var savepoint = connection.setSavepoint();
        boolean restored = false;
        try {
            String role = query(connection, "SHOW role");
            String tenant = query(connection, "SELECT current_setting('kernel.tenant_id', true)");
            String timeout = query(connection, "SHOW statement_timeout");
            execute(connection, "SET LOCAL ROLE kernel_runtime");
            config(connection, "kernel.tenant_id", request.tenantId());
            config(connection, "statement_timeout", Integer.toString(request.limits().timeoutMillis()));
            CedarAuthoriser.CapabilityAuthority authority;
            try { authority = cedar.capabilityAuthority(request.tenantId(), request.principal(), request.scope(), request.type(), work.options != null); }
            catch (RuntimeException unavailable) { denied(Denial.AUTHORITY); throw unavailable; }
            if (source instanceof IndexedReadWindow<?> rawWindow) {
                @SuppressWarnings("unchecked") var window = (IndexedReadWindow<P>) rawWindow;
                window.prepare(authority.revision());
                for (var path : window.queryFields.stream().sorted(Comparator.comparing(ReadPath::qualifiedName)).toList()) {
                    work.decision(deadline);
                    if (!cedar.allowsReadQuery(request, path, authority.values(), deadline)) work.fail(CollectionReadException.Reason.DENIED);
                }
            }
            work.check(deadline);
            String revision = source.revision(connection);
            if (revision == null || revision.isBlank()) work.fail(CollectionReadException.Reason.STALE);
            work.check(deadline);
            if (work.lineage) {
                if (revision.length() > 4096 || authority.revision().length() > 4096) throw new AuthorisationDeniedException();
                work.sourceRevision = revision;
                work.authorityRevision = authority.revision();
                // Retain only a digest, never reusable capability values/a grant snapshot.
                var authorityValues = new TreeMap<String, String>();
                authority.values().forEach((name, value) -> authorityValues.put(name, value.toString()));
                JSON.writeValue(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), work.digest), authorityValues);
            }
            var result = new ArrayList<AuthorisedRecord<P>>();
            var bytes = new ByteBudget(request.limits().outputBytes(), work);
            var fields = request.fields().stream().sorted(Comparator.comparing(ReadPath::qualifiedName)).toList();
            int[] consumed = {0};
            boolean[] failed = {false};
            source.select(connection, candidate -> {
                try {
                    work.check(deadline);
                    if (failed[0]) work.fail(CollectionReadException.Reason.DEPENDENCY);
                    if (++consumed[0] > request.limits().candidates()) work.fail(CollectionReadException.Reason.BUDGET);
                    work.candidates++;
                    if (!request.tenantId().equals(candidate.tenantId())
                            || candidate.record().type() != request.type().subjectType()
                            || candidate.scope().type() != request.scope().type()) throw new AuthorisationDeniedException();
                    if (!candidate.scope().equals(request.scope())) {
                        if (source instanceof IndexedReadWindow<?>) work.fail(CollectionReadException.Reason.STALE);
                        return;
                    }
                    work.decision(deadline);
                    if (!cedar.allowsRead(request, candidate, request.type().qualifiedName() + ".read", authority.values(), deadline)) {
                        denied(Denial.RECORD_POLICY);
                        if (source instanceof IndexedReadWindow<?>) work.fail(CollectionReadException.Reason.STALE);
                        return;
                    }
                    if (source instanceof IndexedReadWindow<?> rawWindow) {
                        @SuppressWarnings("unchecked") var window = (IndexedReadWindow<P>) rawWindow;
                        for (var path : window.queryFields.stream().sorted(Comparator.comparing(ReadPath::qualifiedName)).toList()) {
                            work.decision(deadline);
                            if (!cedar.allowsRead(request, candidate, path.qualifiedName(), authority.values(), deadline)) work.fail(CollectionReadException.Reason.STALE);
                        }
                    }
                    var values = new LinkedHashMap<ReadPath<P, ?>, ReadValue<?>>();
                    for (var path : fields) {
                        work.decision(deadline);
                        if (cedar.allowsRead(request, candidate, path.qualifiedName(), authority.values(), deadline)) {
                            work.check(deadline);
                            var value = path.read().apply(candidate.value());
                            checkScalar(path, value.value(), request.limits().outputBytes(), work);
                            values.put(path, value);
                        } else denied(Denial.FIELD_POLICY);
                    }
                    var view = new AllowedRecord<P>(values);
                    if (!result.isEmpty()) bytes.write(',');
                    JSON.writeValue(bytes, view);
                    if (work.lineage) {
                        // Include every contributing identity, even when its identifier leaf is hidden.
                        var identity = candidate.record();
                        if (identity.externalId().length() > 4096) throw new AuthorisationDeniedException();
                        JSON.writeValue(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), work.digest),
                                List.of(identity.type().qualifiedName(), identity.externalId(), view));
                    }
                    result.add(view);
                } catch (RuntimeException | Error failure) {
                    failed[0] = true;
                    if (work.failure == null && work.options != null) work.failure = failure instanceof CollectionReadException safe
                            ? safe.reason() : CollectionReadException.Reason.DEPENDENCY;
                    throw failure;
                }
            });
            work.check(deadline);
            if (!revision.equals(source.revision(connection))) work.fail(CollectionReadException.Reason.STALE);
            work.check(deadline);
            try {
                if (!authority.equals(cedar.capabilityAuthority(request.tenantId(), request.principal(), request.scope(), request.type(), work.options != null)))
                    work.fail(CollectionReadException.Reason.STALE);
            } catch (AuthorisationDeniedException invalidAuthority) { work.fail(CollectionReadException.Reason.STALE); }
            catch (CollectionReadException invalidAuthority) {
                if (invalidAuthority.reason() == CollectionReadException.Reason.DEPENDENCY) throw invalidAuthority;
                work.fail(CollectionReadException.Reason.STALE);
            }
            work.check(deadline);
            if (failed[0]) work.fail(CollectionReadException.Reason.DEPENDENCY);
            execute(connection, role.equals("none") ? "SET LOCAL ROLE NONE"
                    : "SET LOCAL ROLE \"" + role.replace("\"", "\"\"") + "\"");
            // PostgreSQL represents an unset custom GUC as empty after its first use; both mean no tenant.
            config(connection, "kernel.tenant_id", tenant == null ? "" : tenant);
            config(connection, "statement_timeout", timeout);
            connection.releaseSavepoint(savepoint);
            restored = true;
            work.check(deadline);
            return List.copyOf(result);
        } finally {
            if (!restored) {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
        }
    }

    /** Count compact JSON without allocating an encoded payload or allowing a writer to overrun its budget. */
    private static final class ByteBudget extends java.io.OutputStream {
        private int remaining;
        private final Work work;
        ByteBudget(int limit, Work work) { remaining = limit - 2; this.work = work; } // Complete array brackets, even for one().
        @Override public void write(int value) { charge(1); }
        @Override public void write(byte[] value, int offset, int length) { charge(length); }
        private void charge(int length) {
            if (length > remaining) work.fail(CollectionReadException.Reason.BUDGET);
            remaining -= length;
            work.bytes += length;
        }
    }

    private static final class Work {
        final CollectionOptions options;
        final long started = System.nanoTime();
        boolean lineage;
        String sourceRevision;
        String authorityRevision;
        final java.security.MessageDigest digest = digest();
        private static java.security.MessageDigest digest() {
            try { return java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        int decisions;
        int candidates;
        int bytes = 2;
        CollectionReadException.Reason failure;
        Work(CollectionOptions options) { this.options = options; }
        void check() {
            if (failure != null) throw new CollectionReadException(failure);
            if (options != null && (Thread.currentThread().isInterrupted() || options.cancelled().getAsBoolean()))
                fail(CollectionReadException.Reason.CANCELLED);
        }
        void check(long deadline) {
            check();
            if (System.nanoTime() - deadline >= 0) fail(CollectionReadException.Reason.BUDGET);
        }
        void decision(long deadline) {
            check(deadline);
            if (options != null && decisions >= options.decisions()) fail(CollectionReadException.Reason.BUDGET);
            decisions++;
        }
        void fail(CollectionReadException.Reason reason) {
            if (options != null) {
                if (failure == null) failure = reason;
                throw new CollectionReadException(failure);
            }
            throw new AuthorisationDeniedException();
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkScalar(ReadPath<?, ?> path, Object value, int budget, Work work) {
        if (value == null) return;
        Object scalar = ((ReadPath<Object, Object>) path).scalar().apply(value);
        if (scalar instanceof String text && text.length() > budget) work.fail(CollectionReadException.Reason.BUDGET);
        if (!(scalar instanceof String || scalar instanceof Boolean || scalar instanceof Integer || scalar instanceof Long
                || scalar instanceof Double || scalar instanceof java.math.BigDecimal || scalar instanceof java.time.LocalDate
                || scalar instanceof java.time.LocalTime || scalar instanceof java.time.LocalDateTime || scalar instanceof java.time.Instant)) {
            throw new AuthorisationDeniedException();
        }
    }

    private <P> void validate(ReadRequest<P> request) {
        TenantContext.validate(request.tenantId());
        Objects.requireNonNull(request.principal());
        Objects.requireNonNull(request.limits());
        if (projections.stream().noneMatch(type -> type == request.type())
                || subjects.stream().noneMatch(type -> type == request.scope().type())
                || !cedar.bundleId().equals(request.bundleId()) || !cedar.bundleChecksum().equals(request.bundleChecksum())
                || cedar.model(request.type()).requiredCapabilities().isEmpty()
                || request.fields().size() > request.limits().fields()
                || request.fields().stream().anyMatch(path -> request.type().readPaths().stream().noneMatch(known -> path == known))) {
            throw new AuthorisationDeniedException();
        }
    }
    private static String query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getString(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void config(Connection connection, String name, String value) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            statement.setString(1, name); statement.setString(2, value); statement.execute();
        }
    }
}
