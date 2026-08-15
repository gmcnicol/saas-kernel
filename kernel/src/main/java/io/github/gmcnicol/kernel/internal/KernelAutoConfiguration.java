package io.github.gmcnicol.kernel.internal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

@AutoConfiguration
@AutoConfigureBefore(name = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
public class KernelAutoConfiguration {

    @Bean(initMethod = "migrate")
    Flyway kernelFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("kernelFlyway")
    Flyway applicationFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/application/migration")
                .table("flyway_application_schema_history")
                .load();
    }
}
