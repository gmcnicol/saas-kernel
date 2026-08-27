package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.CookieManager;
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
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
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
    @Autowired CrmContactQueries contacts;
    @Autowired Kernel kernel;

    @Test
    void ownerCreatesOpensAndCompletesContactThroughHttp() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        URI contactsPage = URI.create("http://localhost:" + serverPort + "/contacts");

        assertThat(client.send(HttpRequest.newBuilder(contactsPage).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
        var empty = client.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(empty.statusCode()).isEqualTo(200);
        var csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(empty.body());
        assertThat(csrf.find()).isTrue();
        var created = client.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "displayName", "Ada Lovelace",
                                "nextContactDueAt", "2020-08-15T09:00",
                                "_csrf", csrf.group(1)))))
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(created.statusCode()).isEqualTo(303);
        var listed = client.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(listed.body()).contains("Ada Lovelace", "Follow-up due");
        var contactLink = Pattern.compile("href=\"(/contacts/[0-9a-f-]{36})\"").matcher(listed.body());
        assertThat(contactLink.find()).isTrue();

        var opened = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + contactLink.group(1)))
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(opened.statusCode()).isEqualTo(303);
        var presentation = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + opened.headers().firstValue("Location").orElseThrow()))
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        var offer = Pattern.compile("/presentation/intents/([0-9a-f-]{36})").matcher(presentation.body());
        assertThat(presentation.statusCode()).isEqualTo(200);
        assertThat(offer.find()).isTrue();

        var accepted = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + "/presentation/intents/" + offer.group(1)))
                        .header("Authorization", basic("gareth"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "intentId", UUID.randomUUID().toString(),
                                "actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName(),
                                "payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName(),
                                "payloadVersion", "1",
                                "note", "Spoke today"))))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(200);

        String completed = "";
        String completedAda = "Ada Lovelace</a> <span>Follow-up complete";
        for (int attempt = 0; attempt < 50 && !completed.contains(completedAda); attempt++) {
            Thread.sleep(100);
            completed = client.send(HttpRequest.newBuilder(contactsPage)
                            .header("Authorization", basic("gareth")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
        assertThat(completed).contains(completedAda);

        var reopened = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + contactLink.group(1)))
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(reopened.statusCode()).isEqualTo(303);
        var completedPresentation = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort
                                + reopened.headers().firstValue("Location").orElseThrow()))
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(completedPresentation.statusCode()).isEqualTo(200);
        assertThat(completedPresentation.body()).doesNotContain("/presentation/intents/");
    }

    @Test
    void tenantIsolationAndCedarHideContactCapability() throws Exception {
        URI contactsPage = URI.create("http://localhost:" + serverPort + "/contacts");
        var owner = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var ownerPage = owner.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        var csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(ownerPage.body());
        assertThat(csrf.find()).isTrue();
        assertThat(owner.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "displayName", "Grace Hopper",
                                "nextContactDueAt", "2020-08-15T09:00",
                                "_csrf", csrf.group(1)))))
                        .build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(303);
        var ownerList = owner.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("gareth")).build(),
                HttpResponse.BodyHandlers.ofString());
        var contact = Pattern.compile("href=\"(/contacts/[0-9a-f-]{36})\"[^>]*>Grace Hopper</a>")
                .matcher(ownerList.body());
        assertThat(contact.find()).isTrue();

        var otherTenant = HttpClient.newHttpClient();
        var otherList = otherTenant.send(HttpRequest.newBuilder(contactsPage)
                        .header("Authorization", basic("alice")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(otherList.statusCode()).isEqualTo(200);
        assertThat(otherList.body()).doesNotContain("Grace Hopper");
        assertThat(otherTenant.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + contact.group(1)))
                        .header("Authorization", basic("alice")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);

        var viewer = HttpClient.newHttpClient();
        var opened = viewer.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + contact.group(1)))
                        .header("Authorization", basic("victor")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(opened.statusCode()).isEqualTo(303);
        var presentation = viewer.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + serverPort + opened.headers().firstValue("Location").orElseThrow()))
                        .header("Authorization", basic("victor")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(presentation.statusCode()).isEqualTo(200);
        assertThat(presentation.body()).doesNotContain("/presentation/intents/");
    }

    @Test
    void authenticatedHttpPresentationAcceptsGeneratedCandidate() throws Exception {
        Instant dueAt = Instant.parse("2020-08-15T09:00:00Z");
        new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES ('tenant-http', 'http-contact', 'HTTP Contact', ?, ?)
                """, java.sql.Timestamp.from(dueAt), UUID.randomUUID());
        FollowUpProjection projection = contacts.projection("tenant-http", "http-contact");
        var snapshot = kernel.evaluate(new TypedProjectedState<>("tenant-http",
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), dueAt.plusSeconds(1));
        var client = HttpClient.newHttpClient();
        URI page = URI.create("http://localhost:" + serverPort
                + "/presentation/crm/desktop?snapshotId=" + snapshot.id());

        assertThat(client.send(HttpRequest.newBuilder(page).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
        var rendered = client.send(HttpRequest.newBuilder(page).header("Authorization", basic("gareth"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rendered.statusCode()).isEqualTo(200);
        var match = Pattern.compile("/presentation/intents/([0-9a-f-]{36})").matcher(rendered.body());
        assertThat(match.find()).isTrue();
        UUID offer = UUID.fromString(match.group(1));
        UUID intent = UUID.randomUUID();
        String form = form(Map.of(
                "intentId", intent.toString(),
                "actionType", TypedCrmActions.RECORD_INTERACTION.qualifiedName(),
                "payloadType", RecordInteractionCandidateV1.TYPE.qualifiedName(),
                "payloadVersion", "1",
                "note", "HTTP workflow"));
        var accepted = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + serverPort + "/presentation/intents/" + offer))
                .header("Authorization", basic("gareth"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains(intent.toString(), "PENDING");
    }

    @Test
    void bootsKernelAndBothMigrationStreams() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        assertThat(jdbc.queryForObject("select count(*) from kernel.flyway_kernel_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from flyway_application_schema_history where version = '1'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from crm_contact", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_application_schema_history where version = '2'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('kernel_runtime', 'crm_contact', 'INSERT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('kernel_worker', 'crm_contact', 'INSERT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('kernel_runtime', 'crm_contact_engagement_projection', 'UPDATE')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('kernel_worker', 'crm_contact_engagement_projection', 'UPDATE')",
                Boolean.class)).isTrue();
    }

    @Test
    void servesDueContactsFromApplicationOwnedIndexedProjectionWithoutKernelEvaluation() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        int snapshotsBefore = admin.queryForObject("SELECT count(*) FROM kernel.typed_evaluation_snapshot", Integer.class);
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
            runtimeJdbc.execute("SET LOCAL ROLE kernel_runtime");
            runtimeJdbc.queryForObject(
                    "SELECT set_config('kernel.tenant_id', ?, true)", String.class, "tenant-hot-query");
            return runtimeJdbc.queryForObject(
                    "SELECT count(*) FROM crm_contact_engagement_projection WHERE tenant_id = 'tenant-other'",
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
            return new InMemoryUserDetailsManager(
                    User.withUsername("gareth").password("{noop}test-password")
                            .roles("TENANT_tenant-http", "PRINCIPAL_Owner").build(),
                    User.withUsername("alice").password("{noop}test-password")
                            .roles("TENANT_tenant-other", "PRINCIPAL_Owner").build(),
                    User.withUsername("victor").password("{noop}test-password")
                            .roles("TENANT_tenant-http", "PRINCIPAL_Viewer").build());
        }
    }
}
