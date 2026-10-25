package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.*;
import io.github.gmcnicol.kernel.application.*;
import java.util.*;
import javax.sql.DataSource;
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
class AuthorisedReadTests implements io.github.gmcnicol.kernel.contract.AuthorisationContract<NetworkContactRead> {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        io.github.gmcnicol.kernel.contract.WorkflowTestDatabase.register(p::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }
    @Autowired AuthorisedReads reads;
    @Autowired AuthorisedDisclosures disclosures;
    @TestBean(methodName = "disclosureClock") java.time.Clock clock;
    static final io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.TestClock disclosureTime = new io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.TestClock();
    static java.time.Clock disclosureClock() { return disclosureTime; }
    public void advanceDisclosureClock(long seconds) { disclosureTime.advanceSeconds(seconds); }
    public void inDisclosureTransaction(Runnable work) { new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> work.run()); }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void sanctionedDisclosuresHaveNoPrivatePayloadLogsOrErrors(org.springframework.boot.test.system.CapturedOutput output) {
        checkDisclosureLogBoundary(output::getAll);
    }

    @Test void minimalAssistantContextAndSeparateExportReachOnlyRecordingSinks() {
        var request = request("disclosure-safe");
        var source = single(request, "SELECT 'Ada'");
        var prepared = disclosures.prepare(request, source, "assistant", java.time.Duration.ofSeconds(30));
        var sent = new ArrayList<String>();
        var response = disclosures.derive(prepared, request, "assistant", source,
                (views, out) -> out.write(new tools.jackson.databind.json.JsonMapper().writeValueAsBytes(views)),
                (bytes, out) -> {
                    sent.add(new String(bytes.copy(), java.nio.charset.StandardCharsets.UTF_8));
                    out.write("Answer about Ada".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
        assertThat(sent).containsExactly("[{\"details\":{\"displayName\":\"Ada\"}}]");
        disclosures.send(response, request, "assistant", source, bytes -> sent.add(new String(bytes.copy(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(sent).containsExactly("[{\"details\":{\"displayName\":\"Ada\"}}]", "Answer about Ada");
        var export = disclosures.prepare(request, source, "export", java.time.Duration.ofSeconds(30));
        disclosures.send(export, request, "export", source,
                (views, out) -> out.write(views.getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME).value().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                bytes -> sent.add(new String(bytes.copy(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(sent.getLast()).isEqualTo("Ada");
    }
    @Autowired CurrentAuthority authority;
    public AuthorisedDisclosures disclosures() { return disclosures; }
    public AuthorisedReads reads() { return reads; }
    public io.micrometer.core.instrument.MeterRegistry meters() { return meters; }
    public TypedCapability<?> unrelatedCapability() { return new TypedCapability<>(ExportContacts.TYPE, new ExportContacts()); }
    public TypedCapability<?> capabilityWithRemaining(int remaining) { return new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(remaining))); }
    public CollectionFixture<NetworkContactRead> collectionFixture() {
        var r = request("contract-index-" + UUID.randomUUID());
        var index = networkIndex(r, 3, 0, true);
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new CollectionFixture<>(r, index, () -> {
            admin.update("INSERT INTO collection_contact SELECT ?, n + 10000, 'stranger', 'HIDDEN' FROM generate_series(1, 1000) n", r.tenantId());
            admin.update("UPDATE collection_contact SET name = 'CHANGED-HIDDEN' WHERE tenant_id = ? AND owner = 'stranger'", r.tenantId());
        }, List.of("[{\"details\":{\"displayName\":\"Name-1\"}}]", "[{\"details\":{\"displayName\":\"Name-2\"}}]",
                "[{\"details\":{\"displayName\":\"Name-3\"}}]"), NetworkContactRead.READ_DETAILS_PRIVATE_NOTE);
    }
    public ReadFixture<NetworkContactRead> readFixture() { return ordinaryReadFixture(); }
    public void addOtherScopeProvenance(ReadFixture<NetworkContactRead> f, int copies) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("INSERT INTO read_contact SELECT ?, 'other-network-' || n, 'same-human', 'member', 'OTHER-NETWORK', 'PRIVATE' FROM generate_series(1, ?) n", f.request().tenantId(), copies);
    }
    public Fixture<NetworkContactRead> disclosureFixture() {
        var request = request("disclosure-" + UUID.randomUUID());
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE TABLE IF NOT EXISTS disclosure_contact (tenant text, id text, name text, private_note text, revision text, withdrawn boolean, PRIMARY KEY(tenant, id))");
        admin.execute("ALTER TABLE disclosure_contact ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE disclosure_contact FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS disclosure_tenant ON disclosure_contact");
        admin.execute("CREATE POLICY disclosure_tenant ON disclosure_contact USING (tenant = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON disclosure_contact TO kernel_runtime");
        admin.update("INSERT INTO disclosure_contact VALUES (?, 'PRIVATE-IDENTITY', 'Ada', 'PRIVATE-NOTE', '1', false)", request.tenantId());
        ReadSource<NetworkContactRead> source = new ReadSource<>() {
            public String revision(java.sql.Connection c) throws Exception {
                try (var q = c.prepareStatement("SELECT min(revision) FROM disclosure_contact" ); var row = q.executeQuery()) {
                    return row.next() ? row.getString(1) : null;
                }
            }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                try (var q = c.prepareStatement("SELECT * FROM disclosure_contact WHERE NOT withdrawn")) {
                    q.setFetchSize(1);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            var id = new NetworkContactId(row.getString("id"));
                            sink.accept(new ReadCandidate<>(row.getString("tenant"), request.scope(), new TypedSubject<>(NetworkContactId.TYPE, id),
                                    new NetworkContactRead(id, "network-a", "member", Optional.of(new ContactReadDetails(row.getString("name"), Optional.of(row.getString("private_note")))), List.of())));
                        }
                    }
                }
            }
        };
        return new Fixture<>(request, source, change -> {
            switch (change) {
                case ADD_CONTRIBUTOR -> admin.update("INSERT INTO disclosure_contact VALUES (?, 'SECOND-IDENTITY', 'Ada', 'PRIVATE-SECOND-NOTE', '1', false)", request.tenantId());
                case SECOND_IDENTITY -> admin.update("UPDATE disclosure_contact SET id = 'REPLACEMENT-IDENTITY' WHERE tenant = ? AND id = 'SECOND-IDENTITY'", request.tenantId());
                case SECOND_VALUE -> admin.update("UPDATE disclosure_contact SET name = 'Second changed' WHERE tenant = ? AND id = 'SECOND-IDENTITY'", request.tenantId());
                case EXPIRED_AUTHORITY -> authority.states.put(request.tenantId(), "expired");
                case MALFORMED_AUTHORITY -> authority.states.put(request.tenantId(), "stale");
                case MISSING_AUTHORITY -> authority.states.put(request.tenantId(), "absent");
                case REVOKE -> authority.states.put(request.tenantId(), "revoked");
                case AUTHORITY -> authority.states.put(request.tenantId(), "changed");
                case AUTHORITY_VALUES -> authority.states.put(request.tenantId(), "values");
                case DEPENDENCY -> authority.states.put(request.tenantId(), "error");
                case VALUE -> admin.update("UPDATE disclosure_contact SET name = 'Changed' WHERE tenant = ?", request.tenantId());
                case IDENTITY -> admin.update("UPDATE disclosure_contact SET id = 'UNRELATED-IDENTITY' WHERE tenant = ?", request.tenantId());
                case WITHDRAW -> admin.update("UPDATE disclosure_contact SET withdrawn = true WHERE tenant = ?", request.tenantId());
                case REVISION -> admin.update("UPDATE disclosure_contact SET revision = '2' WHERE tenant = ?", request.tenantId());
            }
        }, "[{\"details\":{\"displayName\":\"Ada\"}}]");
    }

    @Test void membershipRevokedDuringFormattingPreventsTheFinalSend() {
        var request = request("disclosure-revoke-format");
        var source = single(request, "SELECT 'Ada'");
        var prepared = disclosures.prepare(request, source, "export", java.time.Duration.ofSeconds(30));
        var sent = new ArrayList<DisclosureBytes>();
        assertThatThrownBy(() -> disclosures.send(prepared, request, "export", source, (views, out) -> {
            out.write("Ada".getBytes());
            authority.states.put(request.tenantId(), "revoked");
        }, sent::add)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(sent).isEmpty();
    }

    static class CurrentAuthority implements EffectiveCapabilityResolver {
        final Map<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, EffectiveCapabilityResolver> contracts = new java.util.concurrent.ConcurrentHashMap<>();
        public Resolution resolve(Request request) {
            var contract = contracts.get(request.scope().tenantId());
            if (contract != null) return contract.resolve(request);
            String state = states.getOrDefault(request.scope().tenantId(), "current");
            if (state.equals("error")) throw new IllegalStateException("PRIVATE-GRANT-DATA");
            var scope = state.equals("foreign-tenant") ? new Scope("foreign", request.scope().principal(), request.scope().resource()) : request.scope();
            return new Resolution(state.equals("changed") ? "2" : "1", new Context(1, scope, request.evaluatedAt(),
                    request.evaluatedAt().plusSeconds(state.equals("expired") ? -1 : 60),
                    state.equals("stale") ? "old" : state.equals("changed") ? "2" : "1", Set.of("revoked", "absent").contains(state) ? List.of()
                    : List.of(new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(
                            state.equals("cedar-record-error") ? 3 : state.equals("cedar-field-error") ? 4 : state.equals("values") ? 1 : 2))))));
        }
    }

    @org.junit.jupiter.api.TestFactory java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> absentRevokedOrInvalidCurrentAuthorityDenies() {
        return java.util.stream.Stream.of("absent", "revoked", "stale", "error", "expired", "foreign-tenant")
                .map(mode -> org.junit.jupiter.api.DynamicTest.dynamicTest(mode, () -> {
                    var request = request("authority-" + mode);
                    authority.states.put(request.tenantId(), mode);
                    assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'SECRET'")))
                            .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
                }));
    }

    @Test void erroredCedarRecordDecisionFailsTheEntireReadDespiteAnotherPermit() {
        var request = request("cedar-record-error");
        authority.states.put(request.tenantId(), "cedar-record-error");
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'PRIVATE'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
    }

    @Test void erroredNestedFieldDecisionFailsTheEntireReadDespiteItsPermit() {
        var request = request("cedar-field-error");
        authority.states.put(request.tenantId(), "cedar-field-error");
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'PRIVATE'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
    }

    @Test void schemaInvalidCedarPrincipalFailsInsteadOfLookingLikeAMissingRecord() {
        var base = request("invalid-cedar-principal");
        var request = new ReadRequest<>(base.tenantId(), new Principal("NotInCedarSchema", "PRIVATE-PRINCIPAL"),
                base.scope(), base.type(), base.fields(), base.bundleId(), base.bundleChecksum(), base.limits());
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'PRIVATE'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause();
    }

    @org.junit.jupiter.api.TestFactory java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> swallowedCedarFailureDiscardsEarlierRecordsAndFields() {
        return java.util.stream.Stream.of("record-error", "field-error")
                .map(mode -> org.junit.jupiter.api.DynamicTest.dynamicTest(mode, () -> {
                    var request = request("mixed-cedar-" + mode);
                    var earlierCandidateAccepted = new java.util.concurrent.atomic.AtomicBoolean();
                    var failureCaughtBySource = new java.util.concurrent.atomic.AtomicBoolean();
                    var source = new ReadSource<NetworkContactRead>() {
                        public String revision(java.sql.Connection c) { return "1"; }
                        public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                            single(request, "SELECT 'earlier permitted name'").select(c, sink);
                            earlierCandidateAccepted.set(true);
                            try {
                                single(request, "SELECT 'PRIVATE-LATER-NAME'").select(c, row -> {
                                    var id = new NetworkContactId(mode);
                                    sink.accept(new ReadCandidate<>(row.tenantId(), row.scope(), new TypedSubject<>(NetworkContactId.TYPE, id),
                                            new NetworkContactRead(id, row.value().network(), row.value().owner(), row.value().details(), row.value().tags())));
                                });
                            } catch (AuthorisationDeniedException ignored) {
                                failureCaughtBySource.set(true); // Swallowing cannot release the earlier buffer.
                            }
                        }
                    };
                    assertThatThrownBy(() -> reads.many(request, source))
                            .isExactlyInstanceOf(AuthorisationDeniedException.class)
                            .hasMessage("Authorisation denied").hasNoCause();
                    assertThat(earlierCandidateAccepted).isTrue();
                    assertThat(failureCaughtBySource).isTrue();
                }));
    }

    @Test void swallowedCandidateFailureCannotReleaseAnEarlierPartialResult() {
        var base = request("swallowed");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(3, 2, 64, 5000));
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                single(request, "SELECT 'public'").select(c, sink);
                try { single(request, "SELECT repeat('x', 100)").select(c, sink); }
                catch (AuthorisationDeniedException ignored) { /* A source must not bypass the sink's failure. */ }
            }
        };
        assertThatThrownBy(() -> reads.many(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void sourceErrorAfterMappingNeverReleasesPartialOutput() {
        var request = request("source-error");
        var delegate = single(request, "SELECT 'safe once checked'");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, sink);
                throw new IllegalStateException("TOP-SECRET-PAYLOAD");
            }
        };
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied").hasNoCause().hasToString("io.github.gmcnicol.kernel.application.AuthorisationDeniedException: Authorisation denied");
    }

    @Test void rawCandidateCollectionMetadataIsExplicitlyUnsupportedEvenWhenItFits() {
        var request = request("unsafe-raw-metadata");
        assertThatThrownBy(() -> reads.collection(request, single(request, "SELECT 'visible'"), CollectionOptions.page(127)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.UNSUPPORTED)).hasNoCause();
    }

    @Test void currentIndexCountsAreExactIncludingZeroAndResultsAreImmutable() {
        var request = request("complete-collection-" + UUID.randomUUID());
        var index = networkIndex(request, 2, 10, true);
        var page = reads.collection(request, index, CollectionOptions.page(2));
        assertThat(page.records()).hasSize(2);
        assertThat(page.exactCount()).hasValue(2);
        assertThat(page.continuation()).isEmpty();
        assertThatThrownBy(page.records()::clear).isInstanceOf(UnsupportedOperationException.class);
        var emptyRequest = request("empty-index-" + UUID.randomUUID());
        assertThat(reads.collection(emptyRequest, networkIndex(emptyRequest, 0, 10, true), CollectionOptions.page(2)).exactCount()).hasValue(0);
    }

    @Test void collectionCancellationAndDecisionBudgetsNeverReleasePartialOutput() {
        var request = request("collection-work");
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var delegate = single(request, "SELECT 'visible' FROM generate_series(1, 2)");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, candidate -> { sink.accept(candidate); cancelled.set(true); });
            }
        };
        assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, source),
                new CollectionOptions(2, 4224, cancelled::get, Optional.empty())))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.CANCELLED)).hasNoCause();
        assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, delegate),
                new CollectionOptions(2, 2, () -> false, Optional.empty())))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.BUDGET)).hasNoCause();
    }

    @Test void currentAuthorisedIndexPagesLargeNetworksWithoutCandidateMetadata() {
        var request = request("indexed-" + UUID.randomUUID());
        var index = networkIndex(request, 300, 50, false);
        var first = reads.collection(request, index, CollectionOptions.page(40));
        assertThat(first.records()).hasSize(40);
        assertThat(first.exactCount()).isEmpty();
        assertThat(first.continuation()).isPresent();
        assertThat(first.continuation().orElseThrow()).doesNotContain(request.tenantId(), "network-a", "SECRET");
        var next = reads.collection(request, index, new CollectionOptions(40, 4224, () -> false, first.continuation()));
        assertThat(next.records().getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.shown("Name-41"));
        assertThat(next.exactCount()).isEmpty();
        assertThat(first.toString()).isEqualTo("ReadPage[redacted]");
        int total = first.records().size() + next.records().size();
        while (next.continuation().isPresent()) {
            next = reads.collection(request, index, new CollectionOptions(40, 4224, () -> false, next.continuation()));
            assertThat(next.records()).isNotEmpty();
            total += next.records().size();
        }
        assertThat(total).isEqualTo(300);
    }

    AuthorisedIndex<NetworkContactRead> networkIndex(ReadRequest<NetworkContactRead> request, int visible, int hidden, boolean exact) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE TABLE IF NOT EXISTS collection_contact (tenant_id text, id int, owner text, name text)");
        admin.execute("ALTER TABLE collection_contact ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE collection_contact FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS collection_tenant ON collection_contact");
        admin.execute("CREATE POLICY collection_tenant ON collection_contact USING (tenant_id = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON collection_contact TO kernel_runtime");
        admin.execute("CREATE INDEX IF NOT EXISTS collection_owner_order ON collection_contact (tenant_id, owner, id)");
        admin.execute("CREATE TABLE IF NOT EXISTS collection_revision (tenant_id text PRIMARY KEY, source_revision text, index_revision text, authorised_count bigint)");
        admin.execute("ALTER TABLE collection_revision ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE collection_revision FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS collection_revision_tenant ON collection_revision");
        admin.execute("CREATE POLICY collection_revision_tenant ON collection_revision USING (tenant_id = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON collection_revision TO kernel_runtime");
        admin.update("INSERT INTO collection_revision VALUES (?, 'source-1', 'index-1', ?)", request.tenantId(), visible);
        admin.update("INSERT INTO collection_contact SELECT ?, n, 'member', 'Name-' || n FROM generate_series(1, ?) n", request.tenantId(), visible);
        admin.update("INSERT INTO collection_contact SELECT ?, n + 10000, 'stranger', 'SECRET' FROM generate_series(1, ?) n", request.tenantId(), hidden);
        return new AuthorisedIndex<>() {
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return Set.of(NetworkContactRead.READ_CONTACT_ID); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> binding) throws Exception {
                // Application-owned authorised relation, keyed by current scope/principal/policy/authority.
                if (!binding.authorityRevision().equals("1")) throw new CollectionReadException(CollectionReadException.Reason.STALE);
                try (var q = c.prepareStatement("SELECT * FROM collection_revision")) {
                    try (var row = q.executeQuery()) {
                        if (!row.next()) throw new CollectionReadException(CollectionReadException.Reason.STALE);
                        return new State(row.getString("source_revision"), row.getString("index_revision"), checksum(), "1", exact ? OptionalLong.of(row.getLong("authorised_count")) : OptionalLong.empty());
                    }
                }
            }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> binding, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                try (var q = c.prepareStatement("SELECT * FROM collection_contact WHERE owner = ? ORDER BY id LIMIT ? OFFSET ?")) {
                    q.setString(1, binding.request().principal().id()); q.setInt(2, limit); q.setLong(3, offset); q.setFetchSize(1);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            var id = new NetworkContactId(Integer.toString(row.getInt("id")));
                            sink.accept(new ReadCandidate<>(row.getString("tenant_id"), request.scope(), new TypedSubject<>(NetworkContactId.TYPE, id),
                                    new NetworkContactRead(id, "network-a", row.getString("owner"), Optional.of(new ContactReadDetails(row.getString("name"), Optional.empty())), List.of())));
                        }
                    }
                }
            }
        };
    }

    @Test void privateFieldPredicatesRejectBeforeQueryEvenWhenNoRecordsOrOutputFieldsExist() {
        var request = request("private-query-" + UUID.randomUUID());
        var index = networkIndex(request, 0, 20, false);
        var selected = new java.util.concurrent.atomic.AtomicBoolean();
        var privateQuery = new AuthorisedIndex<NetworkContactRead>() {
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return Set.of(NetworkContactRead.READ_DETAILS_PRIVATE_NOTE); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception { return index.current(c, b); }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) { selected.set(true); }
        };
        assertThatThrownBy(() -> reads.collection(request, privateQuery, CollectionOptions.page(10)))
                .isInstanceOf(CollectionReadException.class).hasNoCause();
        assertThat(selected).isFalse();
    }

    @Test void permittedGeneratedQueryPathIsCheckedEvenWhenNotSelectedForOutput() {
        var base = request("permitted-query-" + UUID.randomUUID());
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), Set.<ReadPath<NetworkContactRead, ?>>of(),
                base.bundleId(), base.bundleChecksum(), base.limits());
        var index = networkIndex(request, 3, 20, false);
        var query = new AuthorisedIndex<NetworkContactRead>() {
            public String queryIdentity() { return "name-search-v1:bound-parameter-digest"; }
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME, NetworkContactRead.READ_CONTACT_ID); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception { return index.current(c, b); }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception { index.select(c, b, offset, limit, sink); }
        };
        var result = reads.collection(request, query, CollectionOptions.page(2));
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.hidden());
        assertThat(result.continuation()).isPresent();
    }

    @Test void collectionAuthorityChangeIsStaleNotAnEmptyResultOrDependencyFailure() {
        var request = request("collection-revision");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                single(request, "SELECT 'once-authorised'").select(c, sink);
                authority.states.put(request.tenantId(), "changed");
            }
        };
        assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, source), CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE)).hasNoCause();
    }

    @Test void swallowedIndexWindowOverrunCannotReleaseAPage() {
        var request = request("index-overrun-" + UUID.randomUUID());
        var index = networkIndex(request, 10, 0, false);
        var oversized = new AuthorisedIndex<NetworkContactRead>() {
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return index.queryFields(); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception { return index.current(c, b); }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                try { index.select(c, b, offset, 10, sink); }
                catch (CollectionReadException ignored) { /* Trusted callback cannot override a failed sink. */ }
            }
        };
        assertThatThrownBy(() -> reads.collection(request, oversized, CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.BUDGET)).hasNoCause();
    }

    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @Test void hiddenRowsDoNotChangeIndexedPageShapeOrMeasuredDecisionWork() {
        var request = request("metamorphic-" + UUID.randomUUID());
        var index = networkIndex(request, 12, 0, true);
        var before = measuredDecisions();
        var first = reads.collection(request, index, CollectionOptions.page(5));
        double work = measuredDecisions() - before;
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("INSERT INTO collection_contact SELECT ?, n + 10000, 'stranger', 'HIDDEN' FROM generate_series(1, 10000) n", request.tenantId());
        before = measuredDecisions();
        var again = reads.collection(request, index, CollectionOptions.page(5));
        assertThat(measuredDecisions() - before).isEqualTo(work).isEqualTo(25);
        assertThat(again.records().toString()).isEqualTo(first.records().toString());
        assertThat(again.exactCount()).hasValue(12);
        assertThat(again.continuation().isPresent()).isEqualTo(first.continuation().isPresent());
        assertThat(meters.find("kernel.read.collection.duration").tag("mode", "index").timer()).isNotNull();
    }
    double measuredDecisions() {
        var summary = meters.find("kernel.read.collection.decisions").tag("mode", "index").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    @Test void continuationCannotBeReplayedAcrossCurrentBindingsOrAfterRevocation() {
        var request = request("replay-" + UUID.randomUUID());
        var index = networkIndex(request, 5, 4, false);
        var token = reads.collection(request, index, CollectionOptions.page(2)).continuation();
        var resume = new CollectionOptions(2, 4224, () -> false, token);
        List<ReadRequest<NetworkContactRead>> foreign = List.of(
                new ReadRequest<>(request.tenantId() + "-other", request.principal(), request.scope(), request.type(), request.fields(), request.bundleId(), request.bundleChecksum(), request.limits()),
                new ReadRequest<>(request.tenantId(), new Principal("Owner", "other"), request.scope(), request.type(), request.fields(), request.bundleId(), request.bundleChecksum(), request.limits()),
                new ReadRequest<>(request.tenantId(), request.principal(), new TypedSubject<>(NetworkId.TYPE, new NetworkId("network-b")), request.type(), request.fields(), request.bundleId(), request.bundleChecksum(), request.limits()),
                new ReadRequest<>(request.tenantId(), request.principal(), request.scope(), request.type(), Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME), request.bundleId(), request.bundleChecksum(), request.limits()),
                new ReadRequest<>(request.tenantId(), request.principal(), request.scope(), request.type(), request.fields(), request.bundleId(), "stale-policy", request.limits()));
        for (var other : foreign) assertThatThrownBy(() -> reads.collection(other, index, resume))
                .isInstanceOf(CollectionReadException.class).hasNoCause();
        assertThatThrownBy(() -> reads.collection(request, index, new CollectionOptions(3, 4224, () -> false, token)))
                .isInstanceOf(CollectionReadException.class).hasNoCause();
        assertThatThrownBy(() -> reads.collection(request, index, new CollectionOptions(2, 4224, () -> false, Optional.of(token.orElseThrow() + "x"))))
                .isInstanceOf(CollectionReadException.class).hasNoCause();
        authority.states.put(request.tenantId(), "changed");
        assertThatThrownBy(() -> reads.collection(request, index, resume)).isInstanceOf(CollectionReadException.class).hasNoCause();
        authority.states.put(request.tenantId(), "revoked");
        assertThatThrownBy(() -> reads.collection(request, index, resume)).isInstanceOf(CollectionReadException.class).hasNoCause();
    }

    @Test void indexSourceAndQueryRevisionsInvalidateResumeAndNeverFallBackToRawRows() {
        var request = request("index-fences-" + UUID.randomUUID());
        var index = networkIndex(request, 6, 4, false);
        var token = reads.collection(request, index, CollectionOptions.page(2)).continuation();
        for (String change : List.of("source", "index", "query", "mid-query", "mid-policy", "mid-authority", "denied")) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var changed = new AuthorisedIndex<NetworkContactRead>() {
                public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return index.queryFields(); }
                public String queryIdentity() { return change.equals("query") ? "changed-template-or-parameters" : "all-v1"; }
                public State current(java.sql.Connection c, Binding<NetworkContactRead> b) {
                    boolean mid = change.startsWith("mid-") && calls.incrementAndGet() > 1;
                    return new State(change.equals("source") || (mid && change.equals("mid-query")) ? "source-2" : "source-1",
                            change.equals("index") ? "index-2" : "index-1", mid && change.equals("mid-policy") ? "new-policy" : checksum(),
                            mid && change.equals("mid-authority") ? "2" : "1", OptionalLong.empty());
                }
                public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                        java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                    if (change.equals("denied")) single(request, "SELECT 'SECRET'").select(c, candidate -> {
                        var v = candidate.value();
                        sink.accept(new ReadCandidate<>(candidate.tenantId(), candidate.scope(), candidate.record(),
                                new NetworkContactRead(v.contactId(), v.network(), "stranger", v.details(), v.tags())));
                    });
                    else index.select(c, b, offset, limit, sink);
                }
            };
            assertThatThrownBy(() -> reads.collection(request, changed, new CollectionOptions(2, 4224, () -> false, token)))
                    .as(change).isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE)).hasNoCause();
        }
    }

    @Test void legacyMixedReadsStillFilterButCannotAcquireCollectionMetadataOrRawPagination() {
        var request = request("mixed-count");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                single(request, "SELECT 'visible' FROM generate_series(1, 2)").select(c, sink);
                single(request, "SELECT 'SECRET'").select(c, candidate -> {
                    var v = candidate.value();
                    sink.accept(new ReadCandidate<>(candidate.tenantId(), candidate.scope(), candidate.record(),
                            new NetworkContactRead(v.contactId(), v.network(), "stranger", v.details(), v.tags())));
                });
            }
        };
        var records = reads.many(request, source);
        assertThat(records).hasSize(2);
        assertThat(records.toString()).doesNotContain("SECRET");
        assertThatThrownBy(() -> reads.collection(request, source, CollectionOptions.page(127)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> reads.collection(request, source, CollectionOptions.page(1)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.UNSUPPORTED));
    }

    @Test void collectionDependencyFailureAndCedarErrorsAreNotNoResults() {
        var request = request("collection-dependency");
        assertThat(reads.collection(request, authorisedFixture(request, single(request, "SELECT 'none' WHERE false")), CollectionOptions.page(2)).records()).isEmpty();
        assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, single(request, "SELECT 'PRIVATE' FROM deliberately_missing")), CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.DEPENDENCY))
                .hasMessage("Collection read failed: DEPENDENCY").hasNoCause();
        authority.states.put(request.tenantId(), "cedar-record-error");
        assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, single(request, "SELECT 'PRIVATE'")), CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.DEPENDENCY)).hasNoCause();
    }

    @Test void collectionByteAndSqlDeadlineOverrunsAreBudgetFailuresNotDependencies() {
        var base = request("collection-limit-reasons");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(3, 2, 64, 50));
        for (String sql : List.of("SELECT repeat('x', 100)", "SELECT 'late', pg_sleep(0.2)")) {
            assertThatThrownBy(() -> reads.collection(request, authorisedFixture(request, single(request, sql)), CollectionOptions.page(1)))
                    .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.BUDGET)).hasNoCause();
        }
    }

    @Test void authoritativeDatabaseIndexWithdrawalInvalidatesResume() {
        var request = request("withdraw-index-" + UUID.randomUUID());
        var index = networkIndex(request, 10, 0, true);
        var token = reads.collection(request, index, CollectionOptions.page(2)).continuation();
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("UPDATE collection_revision SET source_revision = 'withdrawn-2', index_revision = 'rebuilt-2' WHERE tenant_id = ?", request.tenantId());
        assertThatThrownBy(() -> reads.collection(request, index, new CollectionOptions(2, 4224, () -> false, token)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE)).hasNoCause();
    }

    @Test void applicationNameSearchAndOrderUsePermittedGeneratedPathAndAuthorisedOffsets() {
        var request = request("search-" + UUID.randomUUID());
        var base = networkIndex(request, 35, 100, false);
        var query = new AuthorisedIndex<NetworkContactRead>() {
            public String queryIdentity() { return "name-prefix-v1:parameter-sha256-for-name-2"; }
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME, NetworkContactRead.READ_CONTACT_ID); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception { return base.current(c, b); }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                // Authored template. No field strings/query fragments come from the caller or Kernel.
                try (var q = c.prepareStatement("SELECT * FROM collection_contact WHERE owner = ? AND name LIKE ? ORDER BY name, id LIMIT ? OFFSET ?")) {
                    q.setString(1, b.request().principal().id()); q.setString(2, "Name-2%"); q.setInt(3, limit); q.setLong(4, offset); q.setFetchSize(1);
                    try (var row = q.executeQuery()) {
                        while (row.next()) {
                            var id = new NetworkContactId(Integer.toString(row.getInt("id")));
                            sink.accept(new ReadCandidate<>(row.getString("tenant_id"), request.scope(), new TypedSubject<>(NetworkContactId.TYPE, id),
                                    new NetworkContactRead(id, "network-a", row.getString("owner"), Optional.of(new ContactReadDetails(row.getString("name"), Optional.empty())), List.of())));
                        }
                    }
                }
            }
        };
        var first = reads.collection(request, query, CollectionOptions.page(4));
        assertThat(first.records()).extracting(view -> view.get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME))
                .containsExactly(ReadValue.shown("Name-2"), ReadValue.shown("Name-20"), ReadValue.shown("Name-21"), ReadValue.shown("Name-22"));
        var next = reads.collection(request, query, new CollectionOptions(4, 4224, () -> false, first.continuation()));
        assertThat(next.records().getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.shown("Name-23"));
        assertThat(next.exactCount()).isEmpty();
    }

    @Test void indexBuiltForOldPolicyOrAuthorityCannotSupplyCountsEvenIfItsRowsStillPassCedar() {
        var request = request("stale-index-metadata-" + UUID.randomUUID());
        var index = networkIndex(request, 10, 0, true);
        for (boolean oldPolicy : List.of(true, false)) {
            var stale = new AuthorisedIndex<NetworkContactRead>() {
                public State current(java.sql.Connection c, Binding<NetworkContactRead> b) {
                    return new State("source-1", "index-1", oldPolicy ? "old-policy" : request.bundleChecksum(), oldPolicy ? "1" : "old-authority", OptionalLong.of(10));
                }
                public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                        java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception { index.select(c, b, offset, limit, sink); }
            };
            assertThatThrownBy(() -> reads.collection(request, stale, CollectionOptions.page(5)))
                    .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE)).hasNoCause();
        }
    }

    @Test void inconsistentExactIndexCountRejectsInsteadOfInventingAnExhaustionPage() {
        var request = request("bad-index-count-" + UUID.randomUUID());
        var index = networkIndex(request, 2, 0, true);
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("UPDATE collection_revision SET authorised_count = 10 WHERE tenant_id = ?", request.tenantId());
        assertThatThrownBy(() -> reads.collection(request, index, CollectionOptions.page(5)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE)).hasNoCause();
    }

    @Test void missingEffectiveAuthorityIsDeniedButResolverOutageIsADependencyFailure() {
        var request = request("authority-failure-kinds");
        var index = authorisedFixture(request, single(request, "SELECT 'private'"));
        authority.states.put(request.tenantId(), "revoked");
        assertThatThrownBy(() -> reads.collection(request, index, CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.DENIED)).hasNoCause();
        authority.states.put(request.tenantId(), "error");
        assertThatThrownBy(() -> reads.collection(request, index, CollectionOptions.page(2)))
                .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.DEPENDENCY)).hasNoCause();
    }

    @Test void indexOrderingKeyNeedsCurrentQueryAndLeafPermissionWithoutBeingReturned() {
        var request = request("index-key-permission-" + UUID.randomUUID());
        var index = networkIndex(request, 4, 2, false);
        var ordered = new AuthorisedIndex<NetworkContactRead>() {
            public Set<ReadPath<NetworkContactRead, ?>> queryFields() { return Set.of(NetworkContactRead.READ_CONTACT_ID); }
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception { return index.current(c, b); }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception { index.select(c, b, offset, limit, sink); }
        };
        var page = reads.collection(request, ordered, CollectionOptions.page(2));
        assertThat(page.records()).hasSize(2);
        assertThat(page.records().getFirst().get(NetworkContactRead.READ_CONTACT_ID)).isEqualTo(ReadValue.hidden());
    }

    /** Trusted immutable authorised relation for failure injection at the public JDBC source boundary. */
    AuthorisedIndex<NetworkContactRead> authorisedFixture(ReadRequest<NetworkContactRead> request, ReadSource<NetworkContactRead> source) {
        return new AuthorisedIndex<>() {
            public State current(java.sql.Connection c, Binding<NetworkContactRead> b) throws Exception {
                return new State(source.revision(c), "fixture-index-1", checksum(), "1", OptionalLong.empty());
            }
            public void select(java.sql.Connection c, Binding<NetworkContactRead> b, long offset, int limit,
                    java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception { source.select(c, sink); }
        };
    }

    @Test void deniedAndMissingRecordsAreBothEmpty() {
        var base = request("missing-record");
        var denied = new ReadRequest<>(base.tenantId(), new Principal("Viewer", "outsider"), base.scope(), base.type(),
                base.fields(), base.bundleId(), base.bundleChecksum(), base.limits());
        assertThat(reads.one(denied, single(denied, "SELECT 'SECRET'"))).isEmpty();
        assertThat(reads.one(base, single(base, "SELECT 'missing' WHERE false"))).isEmpty();
    }

    @Test void authorityRevisionChangeEvenWithSameCapabilityInvalidatesOutput() {
        var request = request("change-revision");
        var delegate = single(request, "SELECT 'checked at revision 1'");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, sink);
                authority.states.put(request.tenantId(), "changed");
            }
        };
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void malformedTenantCannotBecomeTrustedReadScope() {
        var request = request("invalid-tenant\nPRIVATE");
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'must not leave'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
    }

    @Test void missingOrForeignModelsAndBundlesFailClosed() {
        var base = request("descriptors");
        for (var pair : List.of(List.of("foreign.bundle", base.bundleChecksum()), List.of(base.bundleId(), "old-policy"))) {
            var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(), pair.get(0), pair.get(1), base.limits());
            assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'SECRET'")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
        var missingBundle = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(), null, null, base.limits());
        assertThatThrownBy(() -> reads.one(missingBundle, single(missingBundle, "SELECT 'SECRET'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        var forged = new ProjectionType<>(base.type().qualifiedName(), 99, base.type().subjectType(), base.type().javaType(), base.type().fields(), base.type().readPaths());
        var wrongType = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), forged, base.fields(), base.bundleId(), base.bundleChecksum(), base.limits());
        assertThatThrownBy(() -> reads.one(wrongType, single(wrongType, "SELECT 'SECRET'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        var noModel = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), FollowUpProjection.TYPE,
                Set.of(FollowUpProjection.READ_CONTACT_ID), base.bundleId(), base.bundleChecksum(), base.limits());
        assertThatThrownBy(() -> reads.one(noModel, new ReadSource<FollowUpProjection>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<FollowUpProjection>> sink) {}
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void unsafeParentAndListSelectionsRejectClosed() {
        var base = request("unsafe");
        List<ReadPath<NetworkContactRead, ?>> unsafe = List.of(
                new ReadPath<>("io.github.gmcnicol.crm.NetworkContactRead.details", "details", v -> ReadValue.shown(v.details()), v -> v),
                new ReadPath<>("io.github.gmcnicol.crm.NetworkContactRead.tags", "tags", v -> ReadValue.shown(v.tags()), v -> v));
        for (var path : unsafe) {
            var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), Set.of(path), base.bundleId(), base.bundleChecksum(), base.limits());
            assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'SECRET'")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
    }

    @Test void hiddenMissingAndAbsentParentHaveIdenticalExternalShape() {
        var base = request("hidden-missing");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(),
                Set.of(NetworkContactRead.READ_DETAILS_PRIVATE_NOTE), base.bundleId(), base.bundleChecksum(), base.limits());
        var mapper = new tools.jackson.databind.json.JsonMapper();
        for (int mode = 0; mode < 3; mode++) {
            int shape = mode;
            var delegate = single(request, "SELECT 'public'");
            var source = new ReadSource<NetworkContactRead>() {
                public String revision(java.sql.Connection c) { return "1"; }
                public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                    delegate.select(c, row -> sink.accept(new ReadCandidate<>(row.tenantId(), row.scope(), row.record(),
                            new NetworkContactRead(row.value().contactId(), "network-a", "member",
                                    shape == 0 ? Optional.empty() : Optional.of(new ContactReadDetails("public", shape == 1 ? Optional.empty() : Optional.of("PRIVATE"))), List.of("RAW")))));
                }
            };
            var result = reads.one(request, source).orElseThrow();
            assertThat(result.get(NetworkContactRead.READ_DETAILS_PRIVATE_NOTE)).isEqualTo(ReadValue.hidden());
            assertThat(mapper.writeValueAsString(result)).isEqualTo("{}");
            assertThat(result.toString()).isEqualTo("{}");
        }
    }

    @Test void repeatableReadCannotMasqueradeAsACurrentSourceRevision() {
        var request = request("repeatable");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status -> assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'stale snapshot'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause());
    }

    @Test void unassembledScopeDescriptorCannotAssertCanonicalAuthority() {
        var base = request("forged-scope");
        var forged = new SubjectType<>(NetworkId.TYPE.qualifiedName(), NetworkId.class, NetworkId::value);
        var request = new ReadRequest<>(base.tenantId(), base.principal(), new TypedSubject<>(forged, new NetworkId("network-a")),
                base.type(), base.fields(), base.bundleId(), base.bundleChecksum(), base.limits());
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'must not leave'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void sourceWithdrawnDuringSelectionDiscardsAllOutput() {
        var request = request("withdraw-" + UUID.randomUUID());
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE TABLE IF NOT EXISTS read_revision (tenant text PRIMARY KEY, revision text NOT NULL)");
        admin.execute("GRANT SELECT ON read_revision TO kernel_runtime");
        admin.update("INSERT INTO read_revision VALUES (?, '1')", request.tenantId());
        var delegate = single(request, "SELECT 'withdrawn value'");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) throws Exception {
                try (var q = c.prepareStatement("SELECT revision FROM read_revision WHERE tenant = ?")) {
                    q.setString(1, request.tenantId());
                    try (var row = q.executeQuery()) { return row.next() ? row.getString(1) : null; }
                }
            }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, sink);
                admin.update("DELETE FROM read_revision WHERE tenant = ?", request.tenantId());
            }
        };
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void authorityRevokedDuringSelectionCannotLeaveAsAuthorisedOutput() {
        var request = request("revoke-during-" + UUID.randomUUID());
        var delegate = single(request, "SELECT 'once allowed'");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, sink);
                authority.states.put(request.tenantId(), "revoked");
            }
        };
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    ReadRequest<NetworkContactRead> request(String tenant) {
        return new ReadRequest<>(tenant, new Principal("Owner", "member"),
                new TypedSubject<>(NetworkId.TYPE, new NetworkId("network-a")), NetworkContactRead.TYPE,
                Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME, NetworkContactRead.READ_DETAILS_PRIVATE_NOTE),
                "io.github.gmcnicol.crm.reads", checksum(), ReadLimits.DEFAULT);
    }
    ReadSource<NetworkContactRead> single(ReadRequest<NetworkContactRead> request, String sql) {
        return new ReadSource<>() {
            public String revision(java.sql.Connection connection) { return "1"; }
            public void select(java.sql.Connection connection, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setFetchSize(1);
                    try (var row = statement.executeQuery()) {
                        while (row.next()) {
                            var id = new NetworkContactId("record");
                            sink.accept(new ReadCandidate<>(request.tenantId(), request.scope(), new TypedSubject<>(NetworkContactId.TYPE, id),
                                    new NetworkContactRead(id, "network-a", "member", Optional.of(new ContactReadDetails(row.getString(1), Optional.empty())), List.of())));
                        }
                    }
                }
            }
        };
    }

    @Test void restoresExactCallerRoleSettingIncludingNone() {
        var request = request("role-setting");
        var jdbc = new JdbcTemplate(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            String before = jdbc.queryForObject("SHOW role", String.class);
            assertThat(reads.one(request, single(request, "SELECT current_user")))
                    .get().extracting(view -> view.get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.shown("kernel_runtime"));
            assertThat(jdbc.queryForObject("SHOW role", String.class)).isEqualTo(before);
        });
    }

    @Test void joinsCallerTransactionWithoutCommittingOrLosingWork() {
        var request = request("joined-" + UUID.randomUUID());
        var jdbc = new JdbcTemplate(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_worker");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', 'prior-tenant', true)", String.class);
            jdbc.execute("CREATE TEMP TABLE caller_work(value text) ON COMMIT DROP");
            jdbc.update("INSERT INTO caller_work VALUES ('preserved')");
            jdbc.execute("GRANT SELECT ON caller_work TO kernel_runtime");
            // The source must use the SAME transaction/connection and be able to see prior work.
            var result = reads.one(request, single(request, "SELECT value FROM caller_work")).orElseThrow();
            assertThat(result.get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.shown("preserved"));
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("kernel_worker");
            assertThat(jdbc.queryForObject("SELECT current_setting('kernel.tenant_id')", String.class)).isEqualTo("prior-tenant");
            assertThat(jdbc.queryForObject("SELECT value FROM caller_work", String.class)).isEqualTo("preserved");
            status.setRollbackOnly();
        });
    }
    @Test void nonFatalApplicationErrorHasNoRawDiagnosticCause() {
        var request = request("nonfatal-error");
        assertThatThrownBy(() -> reads.one(request, new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) {
                throw new Error("PRIVATE-DTO");
            }
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
    }

    @Test void applicationAssertionErrorIsRedactedAndRestoresCallerScope() {
        var request = request("assertion-error");
        var jdbc = new JdbcTemplate(dataSource);
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) {
                throw new AssertionError("PRIVATE-DTO");
            }
        };
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_worker");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', 'prior', true)", String.class);
            assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class)
                    .hasMessage("Authorisation denied").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("kernel_worker");
            assertThat(jdbc.queryForObject("SELECT current_setting('kernel.tenant_id')", String.class)).isEqualTo("prior");
        });
    }

    @Test void sqlErrorRestoresRoleAndTenantAndKeepsCallerWorkUsable() {
        var request = request("error-" + UUID.randomUUID());
        var jdbc = new JdbcTemplate(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_worker");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', 'previous', true)", String.class);
            jdbc.execute("CREATE TEMP TABLE before_read(value text) ON COMMIT DROP");
            jdbc.update("INSERT INTO before_read VALUES ('caller-owned')");
            assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'SECRET' FROM deliberately_missing_table")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("kernel_worker");
            assertThat(jdbc.queryForObject("SELECT current_setting('kernel.tenant_id')", String.class)).isEqualTo("previous");
            assertThat(jdbc.queryForObject("SELECT value FROM before_read", String.class)).isEqualTo("caller-owned");
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
    }

    @Test void sqlDeadlineDeniesAndRestoresCallerStatementTimeout() {
        var base = request("deadline");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(3, 2, 1024, 50));
        var jdbc = new JdbcTemplate(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '12345ms'");
            assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'after', pg_sleep(0.2)")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
            assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("12345ms");
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        });
    }

    @Test void selectedFieldBudgetIsEnforcedBeforeReading() {
        var base = request("field-budget");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(1, 1, 1024, 5000));
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'public'")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void oneDoesNotChooseAnArbitraryAuthorisedRecord() {
        var request = request("one-not-many");
        var source = single(request, "SELECT 'permitted' FROM generate_series(1, 2)");
        assertThat(reads.many(request, source)).hasSize(2);
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void aCallbackThatReturnsAfterItsDeadlineCannotReleaseOutput() {
        var base = request("callback-deadline");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(1, 2, 1024, 50));
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                single(request, "SELECT 'permitted'").select(c, sink);
                Thread.sleep(80);
            }
        };
        assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void outputByteBudgetRejectsOversizedAuthorisedValues() {
        var base = request("bytes");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(3, 2, 64, 5000));
        assertThatThrownBy(() -> reads.one(request, single(request, "SELECT repeat('é', 30)")))
                .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
    }

    @Test void byteBudgetIncludesUtf8AndCompleteArrayShape() {
        var base = request("utf8");
        int exact = "[{\"details\":{\"displayName\":\"é\"}}]".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (int budget : List.of(exact - 1, exact)) {
            var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                    base.bundleId(), base.bundleChecksum(), new ReadLimits(1, 2, budget, 5000));
            if (budget == exact) assertThat(reads.one(request, single(request, "SELECT 'é'"))).isPresent();
            else assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'é'"))).isExactlyInstanceOf(AuthorisationDeniedException.class);
        }
    }

    @Test void candidateBudgetStopsStreamingBeforeUnboundedAccumulation() {
        var base = request("bounded");
        var request = new ReadRequest<>(base.tenantId(), base.principal(), base.scope(), base.type(), base.fields(),
                base.bundleId(), base.bundleChecksum(), new ReadLimits(3, 2, 1024, 5000));
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = single(request, "SELECT 'name-' || n FROM generate_series(1, 10000) n");
        var source = new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                delegate.select(c, candidate -> { consumed.incrementAndGet(); sink.accept(candidate); });
            }
        };
        assertThatThrownBy(() -> reads.many(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        assertThat(consumed.get()).isEqualTo(4);
    }

    @Test void rejectsAnActiveTransactionOnAnotherDatasource() {
        var foreign = new DriverManagerDataSource(postgres.getJdbcUrl(), "kernel_test_login", "kernel-test");
        var request = request("incompatible");
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(foreign))
                .executeWithoutResult(status -> assertThatThrownBy(() -> reads.one(request, single(request, "SELECT 'not released'")))
                        .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause());
    }

    @TestBean(methodName = "bundle") AuthorisationBundle crmAuthorisationBundle;
    static AuthorisationBundle bundle() { return () -> "reads/manifest.properties"; }
    @TestConfiguration static class Configuration {
        @Bean TypedAuthorisationModel<NetworkContactRead> readModel() {
            return new TypedAuthorisationModel<>(NetworkContactRead.TYPE,
                    Set.of(NetworkContactRead.CONTACT_ID, NetworkContactRead.NETWORK, NetworkContactRead.OWNER),
                    Set.of(), Set.of(EngageContact.TYPE));
        }
        @Bean CurrentAuthority readAuthority() { return new CurrentAuthority(); }
    }
    static String checksum() {
        try (var input = AuthorisedReadTests.class.getResourceAsStream("/reads/checksum.sha256")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private ReadFixture<NetworkContactRead> ordinaryReadFixture() {
        String tenant = "read-" + UUID.randomUUID();
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("""
                CREATE TABLE IF NOT EXISTS read_contact (
                    tenant_id text NOT NULL, network text NOT NULL, id text NOT NULL, owner text NOT NULL,
                    display_name text NOT NULL, private_note text)
                """);
        admin.execute("ALTER TABLE read_contact ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE read_contact FORCE ROW LEVEL SECURITY");
        admin.execute("DROP POLICY IF EXISTS read_tenant ON read_contact");
        admin.execute("CREATE POLICY read_tenant ON read_contact USING (tenant_id = current_setting('kernel.tenant_id', true))");
        admin.execute("GRANT SELECT ON read_contact TO kernel_runtime");
        admin.update("INSERT INTO read_contact VALUES (?, 'network-a', 'same-human', 'member', 'Allowed', 'HIDDEN')", tenant);
        admin.update("INSERT INTO read_contact VALUES (?, 'network-a', 'denied-record', 'stranger', 'DENIED-RECORD', 'DENIED-SECRET')", tenant);
        admin.update("INSERT INTO read_contact VALUES (?, 'network-b', 'same-human', 'member', 'OTHER-NETWORK', 'OTHER-SECRET')", tenant);
        admin.update("INSERT INTO read_contact VALUES (?, 'network-a', 'same-human', 'member', 'OTHER-TENANT', 'FOREIGN')", tenant + "-other");
        var request = new ReadRequest<>(tenant, new Principal("Owner", "member"),
                new TypedSubject<>(NetworkId.TYPE, new NetworkId("network-a")), NetworkContactRead.TYPE,
                Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME, NetworkContactRead.READ_DETAILS_PRIVATE_NOTE),
                "io.github.gmcnicol.crm.reads", checksum(), ReadLimits.DEFAULT);
        var source = new ReadSource<NetworkContactRead>() {
            @Override public String revision(java.sql.Connection connection) { return "1"; }
            @Override public void select(java.sql.Connection connection, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                io.github.gmcnicol.kernel.contract.WorkflowTestDatabase.assertRuntimeContainment(connection, "read_contact", "tenant_id");
                try (var statement = connection.prepareStatement("SELECT * FROM read_contact")) {
                    statement.setFetchSize(1);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var id = new NetworkContactId(rows.getString("id"));
                            var value = new NetworkContactRead(id, rows.getString("network"), rows.getString("owner"),
                                    Optional.of(new ContactReadDetails(rows.getString("display_name"), Optional.ofNullable(rows.getString("private_note")))), List.of("SECRET-TAG"));
                            sink.accept(new ReadCandidate<>(rows.getString("tenant_id"), new TypedSubject<>(NetworkId.TYPE, new NetworkId(rows.getString("network"))), new TypedSubject<>(NetworkContactId.TYPE, id), value));
                        }
                    }
                }
            }
        };
        var grants = new io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract.ApplicationGrants(
                new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(2))));
        authority.contracts.put(tenant, grants);
        return new ReadFixture<>(request, source, new TypedSubject<>(NetworkContactId.TYPE, new NetworkContactId("same-human")), capabilityWithRemaining(2), grants, disclosureTime,
                "[{\"details\":{\"displayName\":\"Allowed\"}}]", List.of(
                        new ExpectedField<>(NetworkContactRead.READ_DETAILS_DISPLAY_NAME, ReadValue.shown("Allowed")),
                        new ExpectedField<>(NetworkContactRead.READ_DETAILS_PRIVATE_NOTE, ReadValue.hidden())));
    }
}
