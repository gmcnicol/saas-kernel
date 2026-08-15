package io.github.gmcnicol.kernel.internal;

import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class TenantContext {

    private static final Pattern VALID_TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private TenantContext() {}

    static void use(JdbcTemplate jdbc, String tenantId) {
        if (tenantId == null || !VALID_TENANT.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("Invalid tenant context");
        }
        jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
        jdbc.execute("SET LOCAL ROLE kernel_runtime");
    }
}
