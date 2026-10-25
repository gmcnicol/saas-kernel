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
    private final CedarAuthoriser cedar;
    private final List<ProjectionType<?, ?>> projections;
    private final List<SubjectType<?>> subjects;

    ScopedAuthorisedReads(DataSource dataSource, CedarAuthoriser cedar, List<SemanticBindings> bindings) {
        this.dataSource = dataSource;
        this.cedar = cedar;
        this.projections = bindings.stream().flatMap(binding -> binding.projections().stream()).toList();
        this.subjects = bindings.stream().flatMap(binding -> binding.subjects().stream()).toList();
    }

    @Override public <P> List<AuthorisedRecord<P>> many(ReadRequest<P> request, ReadSource<P> source) {
        try {
            validate(request);
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
                    return scoped(connection, request, source);
                } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
            }
            try (var connection = dataSource.getConnection()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try { return scoped(connection, request, source); }
                finally { connection.rollback(); }
            }
        } catch (Throwable failure) {
            // Cleanup still runs for fatal VM/thread failures; do not pretend they are recoverable read denials.
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath death) throw death;
            throw new AuthorisationDeniedException();
        }
    }

    private <P> List<AuthorisedRecord<P>> scoped(Connection connection, ReadRequest<P> request, ReadSource<P> source) throws Exception {
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) throw new AuthorisationDeniedException();
        long deadline = System.nanoTime() + request.limits().timeoutMillis() * 1_000_000L;
        var savepoint = connection.setSavepoint();
        boolean restored = false;
        try {
            String role = query(connection, "SHOW role");
            String tenant = query(connection, "SELECT current_setting('kernel.tenant_id', true)");
            String timeout = query(connection, "SHOW statement_timeout");
            execute(connection, "SET LOCAL ROLE kernel_runtime");
            config(connection, "kernel.tenant_id", request.tenantId());
            config(connection, "statement_timeout", Integer.toString(request.limits().timeoutMillis()));
            String revision = source.revision(connection);
            if (revision == null || revision.isBlank()) throw new AuthorisationDeniedException();
            var authority = cedar.capabilityAuthority(request.tenantId(), request.principal(), request.scope(), request.type());
            var result = new ArrayList<AuthorisedRecord<P>>();
            var bytes = new ByteBudget(request.limits().outputBytes());
            var fields = request.fields().stream().sorted(Comparator.comparing(ReadPath::qualifiedName)).toList();
            int[] consumed = {0};
            boolean[] failed = {false};
            source.select(connection, candidate -> {
                try {
                    checkDeadline(deadline);
                    if (failed[0] || ++consumed[0] > request.limits().candidates()) throw new AuthorisationDeniedException();
                    if (!request.tenantId().equals(candidate.tenantId())
                            || candidate.record().type() != request.type().subjectType()
                            || candidate.scope().type() != request.scope().type()) throw new AuthorisationDeniedException();
                    if (!candidate.scope().equals(request.scope())) return;
                    if (!cedar.allowsRead(request, candidate, request.type().qualifiedName() + ".read", authority.values())) return;
                    var values = new LinkedHashMap<ReadPath<P, ?>, ReadValue<?>>();
                    for (var path : fields) {
                        checkDeadline(deadline);
                        if (cedar.allowsRead(request, candidate, path.qualifiedName(), authority.values())) {
                            checkDeadline(deadline);
                            var value = path.read().apply(candidate.value());
                            checkScalar(path, value.value(), request.limits().outputBytes());
                            values.put(path, value);
                        }
                    }
                    var view = new AllowedRecord<P>(values);
                    if (!result.isEmpty()) bytes.write(',');
                    JSON.writeValue(bytes, view);
                    result.add(view);
                } catch (RuntimeException | Error failure) {
                    failed[0] = true;
                    throw failure;
                }
            });
            if (!revision.equals(source.revision(connection))) throw new AuthorisationDeniedException();
            if (!authority.equals(cedar.capabilityAuthority(request.tenantId(), request.principal(), request.scope(), request.type()))) {
                throw new AuthorisationDeniedException();
            }
            checkDeadline(deadline);
            if (failed[0]) throw new AuthorisationDeniedException();
            execute(connection, role.equals("none") ? "SET LOCAL ROLE NONE"
                    : "SET LOCAL ROLE \"" + role.replace("\"", "\"\"") + "\"");
            // PostgreSQL represents an unset custom GUC as empty after its first use; both mean no tenant.
            config(connection, "kernel.tenant_id", tenant == null ? "" : tenant);
            config(connection, "statement_timeout", timeout);
            connection.releaseSavepoint(savepoint);
            restored = true;
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
        ByteBudget(int limit) { remaining = limit - 2; } // Complete array brackets, even for one().
        @Override public void write(int value) { charge(1); }
        @Override public void write(byte[] value, int offset, int length) { charge(length); }
        private void charge(int length) {
            if (length > remaining) throw new AuthorisationDeniedException();
            remaining -= length;
        }
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new AuthorisationDeniedException();
    }

    @SuppressWarnings("unchecked")
    private static void checkScalar(ReadPath<?, ?> path, Object value, int budget) {
        if (value == null) return;
        Object scalar = ((ReadPath<Object, Object>) path).scalar().apply(value);
        if (scalar instanceof String text && text.length() > budget) throw new AuthorisationDeniedException();
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
