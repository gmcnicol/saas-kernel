package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ApplicationBootTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withInitScript("postgres-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "kernel_test_login");
        properties.add("spring.datasource.password", () -> "kernel-test");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", postgres::getUsername);
        properties.add("spring.flyway.password", postgres::getPassword);
        properties.add("spring.security.user.password", () -> "test-password");
    }

    @LocalManagementPort int managementPort;

    @Test
    void bootsKernelAndBothMigrationStreams() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        assertThat(jdbc.queryForObject("select count(*) from kernel.flyway_kernel_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_application_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from crm_contact", Integer.class)).isZero();
    }

    @Test
    void exposesOnlyHealthWithoutPrivateManagementAuthentication() throws Exception {
        var client = HttpClient.newHttpClient();
        var health = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        var privateInfo = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/info")).build(),
                HttpResponse.BodyHandlers.ofString());
        String credentials = Base64.getEncoder().encodeToString("gareth:test-password".getBytes(StandardCharsets.UTF_8));
        var authorisedInfo = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/info"))
                        .header("Authorization", "Basic " + credentials).build(),
                HttpResponse.BodyHandlers.ofString());
        var authorisedMetrics = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/metrics"))
                        .header("Authorization", "Basic " + credentials).build(),
                HttpResponse.BodyHandlers.ofString());
        var authorisedMigrations = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/flyway"))
                        .header("Authorization", "Basic " + credentials).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(privateInfo.statusCode()).isEqualTo(401);
        assertThat(authorisedInfo.statusCode()).isEqualTo(200);
        assertThat(authorisedMetrics.statusCode()).isEqualTo(200);
        assertThat(authorisedMigrations.statusCode()).isEqualTo(200);
        assertThat(authorisedInfo.body()).contains("application", "kernel", "semanticPack", "checksum");
    }
}
