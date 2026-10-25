package io.github.gmcnicol.ledgerling;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.*;
import io.github.gmcnicol.kernel.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class AuthorisedReadTests {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", postgres::getJdbcUrl);
        p.add("spring.datasource.username", () -> "kernel_test_login");
        p.add("spring.datasource.password", () -> "kernel-test");
        p.add("spring.flyway.url", postgres::getJdbcUrl);
        p.add("spring.flyway.user", postgres::getUsername);
        p.add("spring.flyway.password", postgres::getPassword);
        p.add("kernel.intent-worker.enabled", () -> "false");
    }
    @Autowired AuthorisedReads reads;
    @TestBean(methodName = "bundle") AuthorisationBundle ledgerlingAuthorisationBundle;
    static AuthorisationBundle bundle() { return () -> "reads/manifest.properties"; }
    @TestConfiguration static class Configuration {
        @Bean TypedAuthorisationModel<ClientFilingRead> readModel() {
            return new TypedAuthorisationModel<>(ClientFilingRead.TYPE,
                    Set.of(ClientFilingRead.PRACTICE, ClientFilingRead.ASSIGNED_CLIENT), Set.of(), Set.of(ManageFiling.TYPE));
        }
        @Bean EffectiveCapabilityResolver authority() {
            return request -> new EffectiveCapabilityResolver.Resolution("1", new EffectiveCapabilityResolver.Context(
                    1, request.scope(), request.evaluatedAt(), request.evaluatedAt().plusSeconds(60), "1",
                    List.of(new TypedCapability<>(ManageFiling.TYPE, new ManageFiling(Optional.of(2))))));
        }
    }
    static String checksum() {
        try (var input = AuthorisedReadTests.class.getResourceAsStream("/reads/checksum.sha256")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    ReadRequest<ClientFilingRead> request(String tenant, Principal principal) {
        return new ReadRequest<>(tenant, principal, new TypedSubject<>(PracticeId.TYPE, new PracticeId("practice-one")),
                ClientFilingRead.TYPE, Set.of(ClientFilingRead.READ_DETAILS_REFERENCE, ClientFilingRead.READ_DETAILS_WORKING_NOTES),
                "io.github.gmcnicol.ledgerling.reads", checksum(), ReadLimits.DEFAULT);
    }
    ReadSource<ClientFilingRead> source(ReadRequest<ClientFilingRead> request) {
        return new ReadSource<>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<ClientFilingRead>> sink) throws Exception {
                try (var q = c.prepareStatement("SELECT * FROM read_filing ORDER BY id")) {
                    q.setFetchSize(1);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            var id = new ClientFilingId(row.getString("id"));
                            sink.accept(new ReadCandidate<>(row.getString("tenant_id"),
                                    new TypedSubject<>(PracticeId.TYPE, new PracticeId(row.getString("practice"))),
                                    new TypedSubject<>(ClientFilingId.TYPE, id),
                                    new ClientFilingRead(id, row.getString("practice"), row.getString("assigned_client"),
                                            Optional.of(new FilingReadDetails(new ClientReference(row.getString("reference")),
                                                    Optional.ofNullable(row.getString("notes")))))));
                        }
                    }
                }
            }
        };
    }
    String records() {
        String tenant = "practice-read-" + UUID.randomUUID();
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE TABLE IF NOT EXISTS read_filing (tenant_id text, practice text, id text, assigned_client text, reference text, notes text)");
        admin.execute("ALTER TABLE read_filing ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE read_filing FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS read_tenant ON read_filing");
        admin.execute("CREATE POLICY read_tenant ON read_filing USING (tenant_id = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON read_filing TO kernel_runtime");
        admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', '1', 'client-one', 'CLIENT-REF', NULL)", tenant);
        admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', '2', 'client-two', 'OTHER-REF', 'STAFF-SECRET')", tenant);
        admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', '3', 'client-one', 'FOREIGN-TENANT', 'FOREIGN-SECRET')", tenant + "-foreign");
        return tenant;
    }
    @Test void largePracticeAndClientListsUseCurrentAuthorisedRelationsAndNeverCandidateTotals() {
        String tenant = records();
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("INSERT INTO read_filing SELECT ?, 'practice-one', 'a-' || n, 'client-one', 'CLIENT-' || n, 'STAFF-ONLY' FROM generate_series(1, 179) n", tenant);
        admin.update("INSERT INTO read_filing SELECT ?, 'practice-one', 'b-' || n, 'client-two', 'OTHER-' || n, 'STAFF-ONLY' FROM generate_series(1, 199) n", tenant);
        for (var principal : List.of(new Principal("Staff", "employee"), new Principal("Client", "client-one"))) {
            boolean staff = principal.type().equals("Staff");
            var request = request(tenant, principal);
            var index = new AuthorisedIndex<ClientFilingRead>() {
                public Set<ReadPath<ClientFilingRead, ?>> queryFields() { return Set.of(ClientFilingRead.READ_FILING_ID); }
                public State current(java.sql.Connection c, Binding<ClientFilingRead> b) {
                    return new State("practice-source-1", "assigned-relation-1", checksum(), "1", staff ? OptionalLong.of(380) : OptionalLong.empty());
                }
                public void select(java.sql.Connection c, Binding<ClientFilingRead> b, long offset, int limit,
                        java.util.function.Consumer<ReadCandidate<ClientFilingRead>> sink) throws Exception {
                    // Application owns the staff/client relation and its query/index correctness, not Kernel.
                    String sql = "SELECT * FROM read_filing WHERE practice = ?" + (staff ? "" : " AND assigned_client = ?") + " ORDER BY id LIMIT ? OFFSET ?";
                    try (var q = c.prepareStatement(sql)) {
                        int i = 1; q.setString(i++, b.request().scope().externalId());
                        if (!staff) q.setString(i++, b.request().principal().id());
                        q.setInt(i++, limit); q.setLong(i, offset); q.setFetchSize(1);
                        try (var row = q.executeQuery()) {
                            while (row.next()) {
                                var id = new ClientFilingId(row.getString("id"));
                                sink.accept(new ReadCandidate<>(row.getString("tenant_id"), request.scope(), new TypedSubject<>(ClientFilingId.TYPE, id),
                                        new ClientFilingRead(id, row.getString("practice"), row.getString("assigned_client"),
                                                Optional.of(new FilingReadDetails(new ClientReference(row.getString("reference")), Optional.ofNullable(row.getString("notes")))))));
                            }
                        }
                    }
                }
            };
            Optional<String> token = Optional.empty();
            int total = 0;
            do {
                var page = reads.collection(request, index, new CollectionOptions(70, 4224, () -> false, token));
                assertThat(page.records()).isNotEmpty().hasSizeLessThanOrEqualTo(70);
                if (staff) assertThat(page.exactCount()).hasValue(380);
                else {
                    assertThat(page.exactCount()).isEmpty();
                    assertThat(page.records().toString()).doesNotContain("OTHER", "STAFF-ONLY");
                    assertThat(page.records()).allSatisfy(view -> assertThat(view.get(ClientFilingRead.READ_DETAILS_WORKING_NOTES)).isEqualTo(ReadValue.hidden()));
                }
                total += page.records().size(); token = page.continuation();
            } while (token.isPresent());
            assertThat(total).isEqualTo(staff ? 380 : 180);
        }
    }

    @Test void staffSeesBothAssignedClientsAndAuthorisedNullIsNotHidden() {
        var request = request(records(), new Principal("Staff", "employee"));
        var views = reads.many(request, source(request));
        assertThat(views).hasSize(2);
        assertThat(views.getFirst().get(ClientFilingRead.READ_DETAILS_WORKING_NOTES)).isEqualTo(ReadValue.shown(null));
        assertThat(views.get(1).get(ClientFilingRead.READ_DETAILS_WORKING_NOTES)).isEqualTo(ReadValue.shown("STAFF-SECRET"));
        var json = new tools.jackson.databind.json.JsonMapper().readTree(new tools.jackson.databind.json.JsonMapper().writeValueAsString(views));
        assertThat(json.get(0).get("details").get("workingNotes").isNull()).isTrue();
        assertThat(json.get(1).get("details").get("workingNotes").asString()).isEqualTo("STAFF-SECRET");
        assertThatThrownBy(views::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void assignedClientCannotReadAnotherClientsRecordOrStaffNotes() {
        var request = request(records(), new Principal("Client", "client-one"));
        var views = reads.many(request, source(request));
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().get(ClientFilingRead.READ_DETAILS_REFERENCE)).isEqualTo(ReadValue.shown(new ClientReference("CLIENT-REF")));
        assertThat(views.getFirst().get(ClientFilingRead.READ_DETAILS_WORKING_NOTES)).isEqualTo(ReadValue.hidden());
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(views))
                .isEqualTo("[{\"details\":{\"reference\":\"CLIENT-REF\"}}]");
        assertThat(views.toString()).doesNotContain("SECRET", "OTHER", "FOREIGN", "notes", "assignedClient");
    }
}
