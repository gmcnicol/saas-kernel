package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
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
    @Autowired LedgerlingFilingQueries filings;

    @Test
    void bootsKernelAndBothMigrationStreams() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        assertThat(jdbc.queryForObject("select count(*) from kernel.flyway_kernel_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_application_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from ledger_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_application_schema_history where version = '2'", Integer.class)).isOne();
    }

    @Test
    void servesOutstandingFilingsFromApplicationOwnedIndexedProjectionWithoutKernelEvaluation() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        int snapshotsBefore = admin.queryForObject("SELECT count(*) FROM kernel.evaluation_snapshot", Integer.class);
        admin.update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES
                    (?, ?, ?, ?, true, false),
                    (?, ?, ?, ?, true, false),
                    (?, ?, ?, ?, true, false)
                """,
                "tenant-hot-query", "filing-a", "ACME",
                java.sql.Timestamp.from(Instant.parse("2026-08-20T09:00:00Z")),
                "tenant-hot-query", "filing-b", "Example Ltd",
                java.sql.Timestamp.from(Instant.parse("2026-08-20T09:30:00Z")),
                "tenant-other", "filing-hidden", "Hidden Ltd",
                java.sql.Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")));

        var firstPage = filings.outstandingBy(
                "tenant-hot-query", Instant.parse("2026-08-21T09:00:00Z"), Optional.empty(), 1);
        assertThat(firstPage)
                .singleElement().satisfies(filing -> {
                    assertThat(filing.filingId()).isEqualTo("filing-a");
                    assertThat(filing.clientReference()).isEqualTo("ACME");
                });
        assertThat(filings.outstandingBy(
                        "tenant-hot-query", Instant.parse("2026-08-21T09:00:00Z"),
                        Optional.of(firstPage.getFirst()), 1))
                .singleElement().satisfies(filing -> assertThat(filing.filingId()).isEqualTo("filing-b"));
        Integer crossTenantRows = new TransactionTemplate(transactionManager).execute(status -> {
            runtimeJdbc.execute("SET LOCAL ROLE kernel_runtime");
            runtimeJdbc.queryForObject(
                    "SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-hot-query");
            return runtimeJdbc.queryForObject(
                    "SELECT count(*) FROM ledger_filing_projection WHERE tenant_id = 'tenant-other'",
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
        String credentials = Base64.getEncoder().encodeToString(
                "accountant:test-password".getBytes(StandardCharsets.UTF_8));
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
