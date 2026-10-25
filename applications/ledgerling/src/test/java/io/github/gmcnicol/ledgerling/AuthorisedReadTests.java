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
class AuthorisedReadTests implements io.github.gmcnicol.kernel.contract.AuthorisationContract<ClientFilingRead> {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        io.github.gmcnicol.kernel.contract.WorkflowTestDatabase.register(p::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }
    @Autowired AuthorisedReads reads;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    public io.micrometer.core.instrument.MeterRegistry meters() { return meters; }
    @Autowired AuthorisedDisclosures disclosures;
    @TestBean(methodName = "disclosureClock") java.time.Clock clock;
    static final io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.TestClock disclosureTime = new io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.TestClock();
    static java.time.Clock disclosureClock() { return disclosureTime; }
    public void advanceDisclosureClock(long seconds) { disclosureTime.advanceSeconds(seconds); }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    public void inDisclosureTransaction(Runnable work) { new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> work.run()); }
    @Autowired CurrentAuthority authority;
    public AuthorisedDisclosures disclosures() { return disclosures; }
    public AuthorisedReads reads() { return reads; }
    public TypedCapability<?> unrelatedCapability() { return new TypedCapability<>(ExportFilings.TYPE, new ExportFilings()); }
    public TypedCapability<?> capabilityWithRemaining(int remaining) { return new TypedCapability<>(ManageFiling.TYPE, new ManageFiling(Optional.of(remaining))); }
    public CollectionFixture<ClientFilingRead> collectionFixture() {
        var r = request(records(), new Principal("Client", "client-one"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', '4', 'client-one', 'CLIENT-FOUR', 'PRIVATE')", r.tenantId());
        admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', '5', 'client-one', 'CLIENT-FIVE', 'PRIVATE')", r.tenantId());
        var index = new AuthorisedIndex<ClientFilingRead>() {
            public Set<ReadPath<ClientFilingRead, ?>> queryFields() { return Set.of(ClientFilingRead.READ_FILING_ID); }
            public State current(java.sql.Connection c, Binding<ClientFilingRead> b) throws Exception {
                try (var q = c.prepareStatement("SELECT count(*) FROM read_filing WHERE practice = ? AND assigned_client = ?")) {
                    q.setString(1, b.request().scope().externalId()); q.setString(2, b.request().principal().id());
                    try (var row = q.executeQuery()) {
                        row.next();
                        return new State("assigned-source-1", "assigned-index-1", checksum(), "1", OptionalLong.of(row.getLong(1)));
                    }
                }
            }
            public void select(java.sql.Connection c, Binding<ClientFilingRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<ClientFilingRead>> sink) throws Exception {
                try (var q = c.prepareStatement("SELECT * FROM read_filing WHERE practice = ? AND assigned_client = ? ORDER BY id LIMIT ? OFFSET ?")) {
                    q.setString(1, b.request().scope().externalId()); q.setString(2, b.request().principal().id());
                    q.setInt(3, limit); q.setLong(4, offset); q.setFetchSize(1);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            sink.accept(filingCandidate(row, new TypedSubject<>(PracticeId.TYPE, new PracticeId(row.getString("practice")))));
                        }
                    }
                }
            }
        };
        return new CollectionFixture<>(r, index, () -> {
            admin.update("INSERT INTO read_filing SELECT ?, 'practice-one', 'hidden-' || n, 'client-two', 'HIDDEN', 'PRIVATE' FROM generate_series(1, 1000) n", r.tenantId());
            admin.update("UPDATE read_filing SET reference = 'CHANGED-HIDDEN' WHERE tenant_id = ? AND assigned_client = 'client-two'", r.tenantId());
        }, List.of("[{\"details\":{\"reference\":\"CLIENT-REF\"}}]", "[{\"details\":{\"reference\":\"CLIENT-FOUR\"}}]",
                "[{\"details\":{\"reference\":\"CLIENT-FIVE\"}}]"), ClientFilingRead.READ_DETAILS_WORKING_NOTES);
    }
    public void addOtherScopeProvenance(ReadFixture<ClientFilingRead> f, int copies) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("INSERT INTO read_filing SELECT ?, 'other-practice-' || n, '1', 'client-one', 'OTHER-PRACTICE', 'PRIVATE' FROM generate_series(1, ?) n", f.request().tenantId(), copies);
    }
    public ReadFixture<ClientFilingRead> readFixture() {
        var f = disclosureFixture();
        var grants = new io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.ApplicationGrants(
                new TypedCapability<>(ManageFiling.TYPE, new ManageFiling(Optional.of(2))));
        authority.contracts.put(f.request().tenantId(), grants);
        ReadSource<ClientFilingRead> source = new ReadSource<>() {
            public String revision(java.sql.Connection c) throws Exception { return f.source().revision(c); }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<ClientFilingRead>> sink) throws Exception {
                io.github.gmcnicol.kernel.contract.WorkflowTestDatabase.assertRuntimeContainment(c, "read_filing", "tenant_id");
                f.source().select(c, sink);
            }
        };
        return new ReadFixture<>(f.request(), source, new TypedSubject<>(ClientFilingId.TYPE, new ClientFilingId("1")), capabilityWithRemaining(2), grants, disclosureTime, f.json(),
                List.of(new ExpectedField<>(ClientFilingRead.READ_DETAILS_REFERENCE, ReadValue.shown(new ClientReference("CLIENT-REF"))),
                        new ExpectedField<>(ClientFilingRead.READ_DETAILS_WORKING_NOTES, ReadValue.hidden())));
    }
    public Fixture<ClientFilingRead> disclosureFixture() {
        var request = request(records(), new Principal("Client", "client-one"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE TABLE IF NOT EXISTS disclosure_revision (tenant text PRIMARY KEY, revision text)");
        admin.execute("ALTER TABLE disclosure_revision ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE disclosure_revision FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS disclosure_tenant ON disclosure_revision");
        admin.execute("CREATE POLICY disclosure_tenant ON disclosure_revision USING (tenant = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON disclosure_revision TO kernel_runtime");
        admin.update("INSERT INTO disclosure_revision VALUES (?, '1')", request.tenantId());
        var delegate = source(request);
        ReadSource<ClientFilingRead> source = new ReadSource<>() {
            public String revision(java.sql.Connection c) throws Exception {
                try (var q = c.prepareStatement("SELECT revision FROM disclosure_revision"); var row = q.executeQuery()) {
                    return row.next() ? row.getString(1) : null;
                }
            }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<ClientFilingRead>> sink) throws Exception { delegate.select(c, sink); }
        };
        return new Fixture<>(request, source, change -> {
            switch (change) {
                case ADD_CONTRIBUTOR -> admin.update("INSERT INTO read_filing VALUES (?, 'practice-one', 'SECOND-IDENTITY', 'client-one', 'CLIENT-REF', 'PRIVATE-SECOND-NOTE')", request.tenantId());
                case SECOND_IDENTITY -> admin.update("UPDATE read_filing SET id = 'REPLACEMENT-IDENTITY' WHERE tenant_id = ? AND id = 'SECOND-IDENTITY'", request.tenantId());
                case SECOND_VALUE -> admin.update("UPDATE read_filing SET reference = 'Second changed' WHERE tenant_id = ? AND id = 'SECOND-IDENTITY'", request.tenantId());
                case EXPIRED_AUTHORITY -> authority.states.put(request.tenantId(), "expired");
                case MALFORMED_AUTHORITY -> authority.states.put(request.tenantId(), "stale");
                case MISSING_AUTHORITY -> authority.states.put(request.tenantId(), "revoked");
                case REVOKE -> authority.states.put(request.tenantId(), "revoked");
                case AUTHORITY -> authority.states.put(request.tenantId(), "changed");
                case AUTHORITY_VALUES -> authority.states.put(request.tenantId(), "values");
                case DEPENDENCY -> authority.states.put(request.tenantId(), "error");
                case VALUE -> admin.update("UPDATE read_filing SET reference = 'Changed' WHERE tenant_id = ? AND id = '1'", request.tenantId());
                case IDENTITY -> admin.update("UPDATE read_filing SET id = 'UNRELATED-IDENTITY' WHERE tenant_id = ? AND id = '1'", request.tenantId());
                case WITHDRAW -> admin.update("DELETE FROM read_filing WHERE tenant_id = ? AND id = '1'", request.tenantId());
                case REVISION -> admin.update("UPDATE disclosure_revision SET revision = '2' WHERE tenant = ?", request.tenantId());
            }
        }, "[{\"details\":{\"reference\":\"CLIENT-REF\"}}]");
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void sanctionedDisclosuresHaveNoPrivatePayloadLogsOrErrors(org.springframework.boot.test.system.CapturedOutput output) {
        checkDisclosureLogBoundary(output::getAll);
    }

    static class CurrentAuthority implements EffectiveCapabilityResolver {
        final Map<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, EffectiveCapabilityResolver> contracts = new java.util.concurrent.ConcurrentHashMap<>();
        public Resolution resolve(Request request) {
            var contract = contracts.get(request.scope().tenantId());
            if (contract != null) return contract.resolve(request);
            String state = states.getOrDefault(request.scope().tenantId(), "current");
            if (state.equals("error")) throw new IllegalStateException("PRIVATE-GRANT");
            String revision = state.equals("changed") ? "2" : "1";
            return new Resolution(revision, new Context(1, request.scope(), request.evaluatedAt(), request.evaluatedAt().plusSeconds(state.equals("expired") ? -1 : 60), state.equals("stale") ? "old" : revision,
                    state.equals("revoked") ? List.of() : List.of(new TypedCapability<>(ManageFiling.TYPE, new ManageFiling(Optional.of(state.equals("values") ? 1 : 2))))));
        }
    }
    @TestBean(methodName = "bundle") AuthorisationBundle ledgerlingAuthorisationBundle;
    static AuthorisationBundle bundle() { return () -> "reads/manifest.properties"; }
    @TestConfiguration static class Configuration {
        @Bean TypedAuthorisationModel<ClientFilingRead> readModel() {
            return new TypedAuthorisationModel<>(ClientFilingRead.TYPE,
                    Set.of(ClientFilingRead.PRACTICE, ClientFilingRead.ASSIGNED_CLIENT), Set.of(), Set.of(ManageFiling.TYPE));
        }
        @Bean CurrentAuthority authority() { return new CurrentAuthority(); }
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
                            sink.accept(filingCandidate(row, new TypedSubject<>(PracticeId.TYPE, new PracticeId(row.getString("practice")))));
                        }
                    }
                }
            }
        };
    }
    private static ReadCandidate<ClientFilingRead> filingCandidate(
            java.sql.ResultSet row, TypedSubject<?> scope) throws java.sql.SQLException {
        var id = new ClientFilingId(row.getString("id"));
        return new ReadCandidate<>(row.getString("tenant_id"), scope, new TypedSubject<>(ClientFilingId.TYPE, id),
                new ClientFilingRead(id, row.getString("practice"), row.getString("assigned_client"),
                        Optional.of(new FilingReadDetails(new ClientReference(row.getString("reference")),
                                Optional.ofNullable(row.getString("notes"))))));
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
                                sink.accept(filingCandidate(row, request.scope()));
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
