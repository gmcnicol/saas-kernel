package io.github.gmcnicol.kernel.contract;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Shared workflow-test properties; fixtures retain their own containers and domain setup. */
public final class WorkflowTestDatabase {
    private WorkflowTestDatabase() {}

    /** Called inside the public scoped SQL adapter, before mapping (not a Kernel collaborator probe). */
    public static void assertRuntimeContainment(java.sql.Connection connection, String table, String tenantColumn) throws java.sql.SQLException {
        try (var q = connection.prepareStatement("""
                SELECT current_user, r.rolsuper, r.rolbypassrls, r.rolinherit,
                       s.rolsuper, s.rolbypassrls, s.rolinherit,
                       c.relrowsecurity, c.relforcerowsecurity, c.relowner = r.oid, c.relowner = s.oid
                FROM pg_roles r, pg_roles s, pg_class c
                WHERE r.rolname = current_user AND s.rolname = session_user AND c.oid = ?::regclass
                """)) {
            q.setString(1, table);
            try (var row = q.executeQuery()) {
                org.assertj.core.api.Assertions.assertThat(row.next()).isTrue();
                org.assertj.core.api.Assertions.assertThat(row.getString(1)).isEqualTo("kernel_runtime");
                for (int column : new int[] {2, 3, 4, 5, 6, 7, 10, 11})
                    org.assertj.core.api.Assertions.assertThat(row.getBoolean(column)).as("runtime/login non-owner least privilege: column %s", column).isFalse();
                org.assertj.core.api.Assertions.assertThat(row.getBoolean(8)).isTrue();
                org.assertj.core.api.Assertions.assertThat(row.getBoolean(9)).isTrue();
            }
        }
        // Identifiers are adapter-owned constants, never caller input. Test actual RLS-visible rows,
        // not merely Kernel's later candidate identity validation.
        try (var q = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + tenantColumn + " <> current_setting('kernel.tenant_id', true)");
                var row = q.executeQuery()) {
            row.next(); org.assertj.core.api.Assertions.assertThat(row.getLong(1)).isZero();
        }
    }

    public static void register(
            BiConsumer<String, Supplier<Object>> properties,
            Supplier<String> jdbcUrl, Supplier<String> migrationUser, Supplier<String> migrationPassword) {
        properties.accept("spring.datasource.url", jdbcUrl::get);
        properties.accept("spring.datasource.username", () -> "kernel_test_login");
        properties.accept("spring.datasource.password", () -> "kernel-test");
        properties.accept("spring.flyway.url", jdbcUrl::get);
        properties.accept("spring.flyway.user", migrationUser::get);
        properties.accept("spring.flyway.password", migrationPassword::get);
        properties.accept("kernel.intent-worker.enabled", () -> "false");
    }
}
