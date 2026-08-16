package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.LedgerlingActions;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordRecordsReceivedCandidateV1;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
@Import(ApplicationBootTests.TestUsers.class)
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
    @LocalServerPort int serverPort;
    @Autowired JdbcTemplate runtimeJdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LedgerlingFilingQueries filings;
    @Autowired Kernel kernel;

    @Test
    void authenticatedHttpPresentationAcceptsGeneratedCandidate() throws Exception {
        Instant dueAt = Instant.parse("2042-08-15T09:00:00Z");
        new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, request_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES ('tenant-http', 'http-filing', 'http-request', 'HTTP Ltd', ?, true, false)
                """, java.sql.Timestamp.from(dueAt));
        FilingProjection projection = filings.projection("tenant-http", "http-filing");
        var snapshot = kernel.evaluate(new TypedProjectedState<>("tenant-http",
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), dueAt);
        var client = HttpClient.newHttpClient();
        URI page = URI.create("http://localhost:" + serverPort
                + "/presentation/ledgerling?snapshotId=" + snapshot.id());

        assertThat(client.send(HttpRequest.newBuilder(page).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
        var rendered = client.send(HttpRequest.newBuilder(page).header("Authorization", basic("accountant"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rendered.statusCode()).isEqualTo(200);
        var match = Pattern.compile("/presentation/intents/([0-9a-f-]{36})").matcher(rendered.body());
        assertThat(match.find()).isTrue();
        UUID offer = UUID.fromString(match.group(1));
        UUID intent = UUID.randomUUID();
        String form = form(Map.of(
                "intentId", intent.toString(),
                "actionType", LedgerlingActions.RECORD_RECORDS_RECEIVED.qualifiedName(),
                "payloadType", RecordRecordsReceivedCandidateV1.TYPE.qualifiedName(),
                "payloadVersion", "1",
                "receivedAt", dueAt.plusSeconds(1).toString()));
        var accepted = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + serverPort + "/presentation/intents/" + offer))
                .header("Authorization", basic("accountant"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains(intent.toString());
    }

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
        int snapshotsBefore = admin.queryForObject("SELECT count(*) FROM kernel.typed_evaluation_snapshot", Integer.class);
        admin.update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, request_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES
                    (?, ?, ?, ?, ?, true, false),
                    (?, ?, ?, ?, ?, true, false),
                    (?, ?, ?, ?, ?, true, false)
                """,
                "tenant-hot-query", "filing-a", "request-a", "ACME",
                java.sql.Timestamp.from(Instant.parse("2026-08-20T09:00:00Z")),
                "tenant-hot-query", "filing-b", "request-b", "Example Ltd",
                java.sql.Timestamp.from(Instant.parse("2026-08-20T09:30:00Z")),
                "tenant-other", "filing-hidden", "request-hidden", "Hidden Ltd",
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
        assertThat(admin.queryForObject("SELECT count(*) FROM kernel.typed_evaluation_snapshot", Integer.class))
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

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":test-password").getBytes(StandardCharsets.UTF_8));
    }

    private static String form(java.util.Map<String, String> values) {
        return values.entrySet().stream().map(entry -> java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestUsers {
        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("accountant")
                    .password("{noop}test-password")
                    .roles("TENANT_tenant-http", "PRINCIPAL_Staff").build());
        }
    }
}
