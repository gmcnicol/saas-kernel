package example;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.kernel.application.*;
import example.bindings.example.directory.*;
import example.bindings.example.practice.*;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AdoptionIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");

    private org.springframework.context.ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(AdoptionApplication.class).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=adoption_login", "--spring.datasource.password=local-test",
                "--spring.flyway.url=" + postgres.getJdbcUrl(),
                "--spring.flyway.user=" + postgres.getUsername(), "--spring.flyway.password=" + postgres.getPassword());
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
    private void grant(String tenant, Principal principal, TypedSubject<?> scope, String source) {
        admin().update("INSERT INTO authority_revision VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, principal_type, principal_id, scope_type, scope_id) DO UPDATE SET revision = excluded.revision",
                tenant, principal.type(), principal.id(), scope.type().qualifiedName(), scope.externalId(), UUID.randomUUID().toString());
        admin().update("INSERT INTO access_grant VALUES (?, ?, ?, ?, ?, ?, ?)", tenant, principal.type(), principal.id(),
                scope.type().qualifiedName(), scope.externalId(), source, java.sql.Timestamp.from(Instant.now().plusSeconds(300)));
    }
    private void revoke(String tenant, String source) {
        var admin = admin();
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(admin.getDataSource())).executeWithoutResult(status -> {
            admin.update("DELETE FROM access_grant WHERE tenant_id = ? AND source = ?", tenant, source);
            admin.update("UPDATE authority_revision SET revision = ? WHERE tenant_id = ?", UUID.randomUUID().toString(), tenant);
        });
    }
    private void seedDirectory(String tenant) {
        admin().update("INSERT INTO source_revision VALUES (?, 'team', 'ada', ?)", tenant, UUID.randomUUID().toString());
        admin().update("INSERT INTO directory_entry (tenant_id, id, directory, owner, name, private_note) VALUES (?, 'entry', 'team', 'ada', 'Ada', 'PRIVATE')", tenant);
        admin().update("INSERT INTO directory_entry (tenant_id, id, directory, owner, name) VALUES (?, 'other', 'team', 'stranger', 'HIDDEN')", tenant);
    }

    @Test void packagedConsumerStartsWithOnlyStagedKernelArtifacts() throws Exception {
        try (var app = start()) {
            assertThat(app.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(Kernel.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                    .contains("local-repository/io/github/gmcnicol/saas-kernel/").endsWith(".jar");
            assertThat(AdoptionApplication.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                    .endsWith("/adoption-1.0.0.jar");
            assertThatThrownBy(() -> Class.forName("io.github.gmcnicol.kernel.contract.AuthorisationContract"))
                    .isInstanceOf(ClassNotFoundException.class);
            var index = new Properties();
            try (var in = AdoptionApplication.class.getResourceAsStream("/META-INF/saas-kernel/semantic-index.properties")) { index.load(in); }
            var runtime = new Properties();
            try (var in = Kernel.class.getResourceAsStream("/META-INF/saas-kernel/version.properties")) { runtime.load(in); }
            assertThat(index.getProperty("generator-version")).isEqualTo(runtime.getProperty("version"));
            assertThat(app.getBean(AuthorisedReads.class)).isNotNull();
        }
    }

    @Test void previewAdminAndPaidResolveIdenticalOutputAndAnOrdinaryActionExecutes() {
        try (var app = start()) {
            String tenant = "action-" + UUID.randomUUID(); seedDirectory(tenant);
            var queries = app.getBean(DirectoryQueries.class);
            var principal = new Principal("Member", "ada");
            var request = queries.request(tenant, principal, "team");
            var reads = app.getBean(AuthorisedReads.class);
            for (String source : List.of("preview", "admin", "paid")) {
                grant(tenant, principal, request.scope(), source);
                assertThat(reads.one(request, queries.source(request)).orElseThrow().get(Entry.READ_DETAILS_NAME))
                        .isEqualTo(ReadValue.shown("Ada"));
                revoke(tenant, source);
            }
            grant(tenant, principal, request.scope(), "preview");
            grant(tenant, principal, request.scope(), "paid");
            revoke(tenant, "preview"); // Independent overlapping paid grant survives.
            assertThat(reads.one(request, queries.source(request))).isPresent();
            var subject = new TypedSubject<>(EntryId.TYPE, new EntryId("entry"));
            // Workflow capability scope is the generated subject, not an ambient directory claim.
            grant(tenant, principal, subject, "admin");
            var state = new org.springframework.transaction.support.TransactionTemplate(
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status -> {
                var jdbc = app.getBean(JdbcTemplate.class);
                jdbc.execute("SET LOCAL ROLE kernel_runtime");
                jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenant);
                return app.getBean(ApplicationWorkflowData.class).readState(tenant, subject, Entry.TYPE).orElseThrow();
            });
            var kernel = app.getBean(Kernel.class);
            var snapshot = kernel.evaluate(state, Instant.now());
            var offer = kernel.authorise(tenant, snapshot.id(), principal, Instant.now(), Entry.TYPE).actionOffers().getFirst();
            var intent = kernel.accept(tenant, principal, offer.id(), UUID.randomUUID(), DirectoryActions.REVIEW.candidate(new Review("Reviewed once")));
            assertThat(kernel.processNext(Instant.now()).orElseThrow().status()).isEqualTo(IntentStatus.SUCCEEDED);
            assertThat(kernel.findIntentEvents(tenant, intent.id())).hasSize(1);
            assertThat(reads.one(request, queries.source(request)).orElseThrow().get(Entry.READ_REVIEWED)).isEqualTo(ReadValue.shown(true));
        }
    }

    @Test void assistantOutputCannotBeReboundAfterRevocationAndExportJobsReauthoriseReferences() {
        try (var app = start()) {
            String tenant = "disclosure-" + UUID.randomUUID(); seedDirectory(tenant);
            var principal = new Principal("Member", "ada");
            var queries = app.getBean(DirectoryQueries.class);
            var request = queries.request(tenant, principal, "team");
            grant(tenant, principal, request.scope(), "preview");
            var source = queries.source(request);
            var disclosures = app.getBean(AuthorisedDisclosures.class);
            AuthorisedDisclosures.Formatter<Entry> names = (views, out) -> {
                for (var view : views) {
                    var name = view.get(Entry.READ_DETAILS_NAME);
                    if (name.visible() && name.value() != null) out.write(name.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            };
            var sent = new ArrayList<String>();
            var prepared = disclosures.prepare(request, source, "assistant", java.time.Duration.ofSeconds(30));
            var answer = disclosures.derive(prepared, request, "assistant", source, names, (context, response) -> {
                assertThat(new String(context.copy(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Ada");
                response.write("Answer about Ada".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            disclosures.send(answer, request, "assistant", source, bytes -> sent.add(new String(bytes.copy(), java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(sent).containsExactly("Answer about Ada");
            revoke(tenant, "preview");
            assertThatThrownBy(() -> disclosures.send(answer, request, "assistant", source, bytes -> sent.add("UNSAFE")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
            grant(tenant, principal, request.scope(), "admin");
            assertThatThrownBy(() -> disclosures.send(answer, request, "assistant", source, bytes -> sent.add("REBOUND")))
                    .isExactlyInstanceOf(AuthorisationDeniedException.class); // New revision cannot bless old provider output.
            // Durable jobs store only these Application references/requester/purpose, never Kernel handles/payloads.
            record ExportJob(String tenant, Principal requester, String directory, String purpose) {}
            var job = new ExportJob(tenant, principal, "team", "export");
            var current = queries.request(job.tenant(), job.requester(), job.directory());
            var export = disclosures.prepare(current, queries.source(current), job.purpose(), java.time.Duration.ofSeconds(30));
            disclosures.send(export, current, job.purpose(), queries.source(current), names,
                    bytes -> sent.add(new String(bytes.copy(), java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(sent).containsExactly("Answer about Ada", "Ada");
        }
    }

    @Test void practiceStaffAndAssignedClientHaveIndependentVisibilityAndSafeSearchPages() {
        try (var app = start()) {
            String tenant = "practice-" + UUID.randomUUID();
            var queries = app.getBean(PracticeQueries.class);
            var reads = app.getBean(AuthorisedReads.class);
            var client = queries.request(tenant, new Principal("Client", "client-a"), "practice");
            var staff = queries.request(tenant, new Principal("Staff", "staff-a"), "practice");
            for (var request : List.of(client, staff)) {
                grant(tenant, request.principal(), request.scope(), "admin");
                admin().update("""
                        INSERT INTO practice_view SELECT tenant_id, scope_id, principal_type, principal_id, ?, ?, revision
                        FROM authority_revision WHERE tenant_id = ? AND principal_type = ?
                        """, UUID.randomUUID().toString(), request.bundleChecksum(), tenant, request.principal().type());
            }
            admin().update("INSERT INTO client_file VALUES (?, 'a', 'practice', 'client-a', 'REF-A', 'STAFF-A'), (?, 'b', 'practice', 'client-b', 'REF-B', 'STAFF-B')", tenant, tenant);
            var clientRecord = reads.one(client, queries.source(client)).orElseThrow();
            assertThat(clientRecord.get(ClientFile.READ_REFERENCE)).isEqualTo(ReadValue.shown("REF-A"));
            assertThat(clientRecord.get(ClientFile.READ_NOTES)).isEqualTo(ReadValue.hidden());
            var page = reads.collection(staff, queries.search("REF-"), CollectionOptions.page(1));
            assertThat(page.exactCount()).hasValue(2);
            assertThat(page.records().getFirst().get(ClientFile.READ_NOTES)).isEqualTo(ReadValue.shown("STAFF-A"));
            var next = reads.collection(staff, queries.search("REF-"), new CollectionOptions(1, 100, () -> false, page.continuation()));
            assertThat(next.records().getFirst().get(ClientFile.READ_REFERENCE)).isEqualTo(ReadValue.shown("REF-B"));
            assertThat(next.continuation()).isEmpty();
            var clientPage = reads.collection(client, queries.search("REF-"), CollectionOptions.page(1));
            assertThat(clientPage.exactCount()).hasValue(1);
            assertThat(clientPage.continuation()).isEmpty();
            // Application writer changes the visible value AND its affected view revision atomically.
            var admin = admin();
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(admin.getDataSource())).executeWithoutResult(status -> {
                admin.update("UPDATE client_file SET reference = 'REF-C' WHERE tenant_id = ? AND id = 'b'", tenant);
                admin.update("UPDATE practice_view SET revision = ? WHERE tenant_id = ? AND principal_type = 'Staff'", UUID.randomUUID().toString(), tenant);
            });
            assertThatThrownBy(() -> reads.collection(staff, queries.search("REF-"),
                    new CollectionOptions(1, 100, () -> false, page.continuation())))
                    .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE));
            // An index marked for a different policy must not serve even a fresh first page.
            admin.update("UPDATE practice_view SET policy_checksum = ? WHERE tenant_id = ?", "0".repeat(64), tenant);
            assertThatThrownBy(() -> reads.collection(staff, queries.search("REF-"), CollectionOptions.page(1)))
                    .isInstanceOfSatisfying(CollectionReadException.class, e -> assertThat(e.reason()).isEqualTo(CollectionReadException.Reason.STALE));
        }
    }

    @Test void directoryWrongSqlIsRlsContainedAndPrivateFieldsDefaultDeny() {
        try (var app = start()) {
            String tenant = "directory-" + UUID.randomUUID();
            seedDirectory(tenant); seedDirectory("foreign-" + UUID.randomUUID());
            var principal = new Principal("Member", "ada");
            var queries = app.getBean(DirectoryQueries.class);
            var request = queries.request(tenant, principal, "team");
            grant(tenant, principal, request.scope(), "preview");
            var source = queries.source(request);
            var reads = app.getBean(AuthorisedReads.class);
            var result = reads.one(request, new ReadSource<Entry>() {
                public String revision(java.sql.Connection c) throws Exception { return source.revision(c); }
                public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<Entry>> sink) throws Exception {
                    // Deliberately wrong tenant predicate must see zero rows BEFORE mapping/Cedar.
                    try (var q = c.prepareStatement("SELECT count(*) FROM directory_entry WHERE tenant_id <> ?")) {
                        q.setString(1, tenant);
                        try (var row = q.executeQuery()) { row.next(); assertThat(row.getInt(1)).isZero(); }
                    }
                    source.select(c, sink); // Intentionally no tenant/owner predicates in this source.
                }
            }).orElseThrow();
            assertThat(result.get(Entry.READ_DETAILS_NAME)).isEqualTo(ReadValue.shown("Ada"));
            assertThat(result.get(Entry.READ_DETAILS_PRIVATE_NOTE)).isEqualTo(ReadValue.hidden());
            assertThat(result.toString()).doesNotContain("PRIVATE", "HIDDEN");
            revoke(tenant, "preview");
            assertThatThrownBy(() -> reads.one(request, source)).isExactlyInstanceOf(AuthorisationDeniedException.class).hasNoCause();
        }
    }
}
