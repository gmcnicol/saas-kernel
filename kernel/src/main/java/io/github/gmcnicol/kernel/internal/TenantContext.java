package io.github.gmcnicol.kernel.internal;

import java.util.regex.Pattern;
import io.github.gmcnicol.kernel.application.Subject;
import org.springframework.jdbc.core.JdbcTemplate;

final class TenantContext {

    private static final Pattern VALID_TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private TenantContext() {}

    static void use(JdbcTemplate jdbc, String tenantId) {
        assumeRuntimeRole(jdbc);
        useAfterRole(jdbc, tenantId);
    }

    static void assumeRuntimeRole(JdbcTemplate jdbc) {
        jdbc.execute("SET LOCAL ROLE kernel_runtime");
    }

    static void useAfterRole(JdbcTemplate jdbc, String tenantId) {
        if (tenantId == null || !VALID_TENANT.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("Invalid tenant context");
        }
        jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
    }

    static void lockSubject(JdbcTemplate jdbc, String tenantId, Subject subject) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                tenantId.length() + ":" + tenantId
                        + subject.type().length() + ":" + subject.type()
                        + subject.id().length() + ":" + subject.id());
    }
}
