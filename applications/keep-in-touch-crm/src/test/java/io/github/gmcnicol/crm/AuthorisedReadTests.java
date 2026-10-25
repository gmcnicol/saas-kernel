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
    @Autowired CurrentAuthority authority;

    static class CurrentAuthority implements EffectiveCapabilityResolver {
        final Map<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();
        public Resolution resolve(Request request) {
            String state = states.getOrDefault(request.scope().tenantId(), "current");
            if (state.equals("error")) throw new IllegalStateException("PRIVATE-GRANT-DATA");
            var scope = state.equals("foreign-tenant") ? new Scope("foreign", request.scope().principal(), request.scope().resource()) : request.scope();
            return new Resolution(state.equals("changed") ? "2" : "1", new Context(1, scope, request.evaluatedAt(),
                    request.evaluatedAt().plusSeconds(state.equals("expired") ? -1 : 60),
                    state.equals("stale") ? "old" : state.equals("changed") ? "2" : "1", Set.of("revoked", "absent").contains(state) ? List.of()
                    : List.of(new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(
                            state.equals("cedar-record-error") ? 3 : state.equals("cedar-field-error") ? 4 : 2))))));
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

    @Test void erroredCedarRecordDecisionCannotUseAnotherPermit() {
        var request = request("cedar-record-error");
        authority.states.put(request.tenantId(), "cedar-record-error");
        assertThat(reads.one(request, single(request, "SELECT 'PRIVATE'"))).isEmpty();
    }

    @Test void erroredNestedFieldDecisionCannotUseItsPermit() {
        var request = request("cedar-field-error");
        authority.states.put(request.tenantId(), "cedar-field-error");
        var result = reads.one(request, single(request, "SELECT 'PRIVATE'")).orElseThrow();
        assertThat(result.get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.hidden());
        assertThat(new tools.jackson.databind.json.JsonMapper().writeValueAsString(result)).isEqualTo("{}");
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
                    Set.of(NetworkContactRead.NETWORK, NetworkContactRead.OWNER), Set.of(), Set.of(EngageContact.TYPE));
        }
        @Bean CurrentAuthority readAuthority() { return new CurrentAuthority(); }
    }
    static String checksum() {
        try (var input = AuthorisedReadTests.class.getResourceAsStream("/reads/checksum.sha256")) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test void wrongTenantSqlIsContainedAndSameHumanCannotBridgeNetworks() {
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
        var result = reads.many(request, new ReadSource<NetworkContactRead>() {
            @Override public String revision(java.sql.Connection connection) { return "1"; }
            @Override public void select(java.sql.Connection connection, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) throws Exception {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM read_contact")) {
                    while (rows.next()) {
                        var id = new NetworkContactId(rows.getString("id"));
                        var value = new NetworkContactRead(id, rows.getString("network"), rows.getString("owner"),
                                Optional.of(new ContactReadDetails(rows.getString("display_name"), Optional.ofNullable(rows.getString("private_note")))), List.of("SECRET-TAG"));
                        sink.accept(new ReadCandidate<>(rows.getString("tenant_id"), new TypedSubject<>(NetworkId.TYPE, new NetworkId(rows.getString("network"))), new TypedSubject<>(NetworkContactId.TYPE, id), value));
                    }
                }
            }
        });
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME)).isEqualTo(ReadValue.shown("Allowed"));
        assertThat(result.getFirst().get(NetworkContactRead.READ_DETAILS_PRIVATE_NOTE)).isEqualTo(ReadValue.hidden());
        assertThat(result.toString()).doesNotContain("HIDDEN", "OTHER", "SECRET");
    }
}
