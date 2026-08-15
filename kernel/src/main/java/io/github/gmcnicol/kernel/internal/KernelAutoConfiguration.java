package io.github.gmcnicol.kernel.internal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@AutoConfigureBefore(name = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
public class KernelAutoConfiguration {

    @Bean(initMethod = "migrate")
    Flyway kernelFlyway(DataSource dataSource, Environment environment) {
        return migrationFlyway(dataSource, environment)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("kernelFlyway")
    Flyway applicationFlyway(DataSource dataSource, Environment environment) {
        return migrationFlyway(dataSource, environment)
                .locations("classpath:db/application/migration")
                .table("flyway_application_schema_history")
                .load();
    }

    @Bean
    @DependsOn("applicationFlyway")
    Object runtimeRoleValidator(JdbcTemplate jdbc) {
        var access = jdbc.queryForMap("""
                SELECT current_user AS username, rolsuper, rolbypassrls,
                  pg_has_role(current_user, 'kernel_runtime', 'MEMBER') AS runtime_member,
                  EXISTS (
                      SELECT 1 FROM pg_class table_definition
                      JOIN pg_namespace schema_definition ON schema_definition.oid = table_definition.relnamespace
                      WHERE schema_definition.nspname = 'kernel'
                        AND table_definition.relkind = 'r'
                        AND table_definition.relowner = current_user::regrole) AS owns_kernel_tables
                FROM pg_roles WHERE rolname = current_user
                """);
        if (Boolean.TRUE.equals(access.get("rolsuper"))
                || Boolean.TRUE.equals(access.get("rolbypassrls"))
                || !Boolean.TRUE.equals(access.get("runtime_member"))
                || Boolean.TRUE.equals(access.get("owns_kernel_tables"))) {
            throw new IllegalStateException("Kernel datasource must use a non-owner, non-bypass runtime role: " + access);
        }
        return new Object();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration migrationFlyway(
            DataSource dataSource, Environment environment) {
        String url = environment.getProperty("spring.flyway.url");
        if (url == null) {
            return Flyway.configure().dataSource(dataSource);
        }
        return Flyway.configure().dataSource(
                url,
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
    }
}
