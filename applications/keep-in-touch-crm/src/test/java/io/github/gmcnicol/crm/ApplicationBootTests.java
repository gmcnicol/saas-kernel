package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired JdbcTemplate runtimeJdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CrmContactQueries contacts;

    @Test
    void bootsKernelAndBothMigrationStreams() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        assertThat(jdbc.queryForObject("select count(*) from kernel.flyway_kernel_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_application_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from crm_contact", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_application_schema_history where version = '2'", Integer.class)).isOne();
    }

    @Test
    void servesDueContactsFromApplicationOwnedIndexedProjectionWithoutKernelEvaluation() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        int snapshotsBefore = admin.queryForObject("SELECT count(*) FROM kernel.evaluation_snapshot", Integer.class);
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES
                    (?, ?, ?, ?, ?),
                    (?, ?, ?, ?, ?),
                    (?, ?, ?, ?, ?)
                """,
                "tenant-hot-query", "ada", "Ada Lovelace",
                java.sql.Timestamp.from(Instant.parse("2026-08-15T09:00:00Z")), UUID.randomUUID(),
                "tenant-hot-query", "grace", "Grace Hopper",
                java.sql.Timestamp.from(Instant.parse("2026-08-15T09:30:00Z")), UUID.randomUUID(),
                "tenant-other", "hidden", "Hidden Person",
                java.sql.Timestamp.from(Instant.parse("2026-08-15T08:00:00Z")), UUID.randomUUID());

        var firstPage = contacts.dueBy(
                "tenant-hot-query", Instant.parse("2026-08-15T10:00:00Z"), Optional.empty(), 1);
        assertThat(firstPage)
                .singleElement().satisfies(contact -> {
                    assertThat(contact.contactId()).isEqualTo("ada");
                    assertThat(contact.displayName()).isEqualTo("Ada Lovelace");
                });
        assertThat(contacts.dueBy(
                        "tenant-hot-query", Instant.parse("2026-08-15T10:00:00Z"),
                        Optional.of(firstPage.getFirst()), 1))
                .singleElement().satisfies(contact -> assertThat(contact.contactId()).isEqualTo("grace"));
        Integer crossTenantRows = new TransactionTemplate(transactionManager).execute(status -> {
            runtimeJdbc.queryForObject(
                    "SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-hot-query");
            return runtimeJdbc.queryForObject(
                    "SELECT count(*) FROM crm_contact_engagement_projection WHERE tenant_id = 'tenant-other'",
                    Integer.class);
        });
        assertThat(crossTenantRows).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM kernel.evaluation_snapshot", Integer.class))
                .isEqualTo(snapshotsBefore);
    }

    @Test
    void exposesOnlyHealthWithoutPrivateManagementAuthentication() throws Exception {
        var client = HttpClient.newHttpClient();
        var health = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        var liveness = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health/liveness")).build(),
                HttpResponse.BodyHandlers.ofString());
        var readiness = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/health/readiness")).build(),
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
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(privateInfo.statusCode()).isEqualTo(401);
        assertThat(authorisedInfo.statusCode()).isEqualTo(200);
        assertThat(authorisedMetrics.statusCode()).isEqualTo(200);
        assertThat(authorisedMigrations.statusCode()).isEqualTo(200);
        assertThat(authorisedInfo.body()).contains("application", "kernel", "semanticPack", "checksum");
    }
}
