package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.KernelRuntime;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class ApplicationBootTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired KernelRuntime kernel;
    @Autowired DataSource dataSource;

    @Test
    void bootsKernelAndBothMigrationStreams() {
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(kernel.active()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from kernel.flyway_kernel_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_application_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from crm_contact", Integer.class)).isZero();
    }
}
