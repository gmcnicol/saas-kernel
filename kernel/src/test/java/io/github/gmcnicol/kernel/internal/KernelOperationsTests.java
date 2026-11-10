package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KernelOperationsTests {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Test
    @Order(1)
    void upgradesMigrationsForwardAndAllowsOnlyEquivalentSemanticOverlap() throws Exception {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .target("2")
                .load()
                .migrate();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                        "SELECT max(version) FROM kernel.flyway_kernel_schema_history WHERE success", String.class))
                .isEqualTo("2");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/kernel/migration")
                .schemas("kernel").defaultSchema("kernel").table("flyway_kernel_schema_history")
                .target("5").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                DO $$
                DECLARE snapshot uuid := gen_random_uuid(); offer uuid := gen_random_uuid(); intent uuid := gen_random_uuid();
                BEGIN
                    INSERT INTO kernel.typed_projected_state VALUES
                        ('legacy', 'Contact', '1', 1, 'Projection', 1, 1, '{"name":"legacy-private"}', repeat('a', 64));
                    INSERT INTO kernel.typed_evaluation_snapshot
                        (id, tenant_id, subject_type, subject_id, state_version, projection_type, projection_contract_version,
                         state_checksum, evaluated_at, application_id, application_version, kernel_version, semantic_pack_id, semantic_pack_checksum)
                    VALUES (snapshot, 'legacy', 'Contact', '1', 1, 'Projection', 1, repeat('a', 64), now(), 'app', '1', '1', 'pack', repeat('a', 64));
                    INSERT INTO kernel.typed_evaluation_fact VALUES
                        (snapshot, 'legacy', 0, 'Fact', 1, 1, 'derive', '{"name":"legacy-private"}', repeat('a', 64));
                    INSERT INTO kernel.typed_action_offer VALUES
                        (offer, 'legacy', snapshot, 'Owner', 'owner', 'Contact', '1', 'action', 'policy', 1, repeat('a', 64),
                         'Input', 1, 'pack', repeat('a', 64), 'bundle', repeat('a', 64), now(), gen_random_uuid());
                    INSERT INTO kernel.typed_intent
                        (id, tenant_id, action_offer_id, subject_type, subject_id, action_id, policy_id, expected_state_version,
                         expected_state_checksum, projection_type, projection_contract_version, payload_type, payload_contract_version,
                         payload_format_version, payload_content, payload_checksum, request_checksum, accepted_at, status)
                    VALUES (intent, 'legacy', offer, 'Contact', '1', 'action', 'policy', 1, repeat('a', 64), 'Projection', 1,
                            'Input', 1, 1, '{"name":"legacy-private"}', repeat('a', 64), repeat('a', 64), now(), 'PENDING');
                END $$
                """);
        var upgrade = Flyway.configure().dataSource(dataSource).locations("classpath:db/kernel/migration")
                .schemas("kernel").defaultSchema("kernel").table("flyway_kernel_schema_history").load();
        var application = Flyway.configure().dataSource(dataSource).locations("classpath:db/domain-upgrade")
                .table("flyway_application_schema_history").load();
        var oldProcess = new SemanticDeploymentGuard(dataSource, "test.Cutover", "e".repeat(64));
        oldProcess.start();
        try {
            assertThatThrownBy(() -> KernelAutoConfiguration.migrateDomainData(application, upgrade, "test.Cutover"))
                    .hasMessageContaining("Stop all old Application processes");
        } finally {
            oldProcess.stop();
        }
        migrateWithReusedConnection(application, upgrade, true);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.recovered_domain_record') IS NULL", Boolean.class)).isTrue();
        jdbc.execute("CREATE ROLE restricted_migrator LOGIN NOINHERIT NOBYPASSRLS PASSWORD 'migration-test'");
        jdbc.execute("GRANT USAGE ON SCHEMA kernel TO restricted_migrator");
        jdbc.execute("GRANT SELECT ON kernel.typed_intent TO restricted_migrator");
        var restricted = Flyway.configure().dataSource(postgres.getJdbcUrl(), "restricted_migrator", "migration-test").load();
        assertThatThrownBy(() -> KernelAutoConfiguration.migrateDomainData(application, restricted, "test.Cutover"))
                .hasStackTraceContaining("row-level security");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.recovered_domain_record') IS NULL", Boolean.class)).isTrue();
        assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("Drain pending Intents");
        assertThat(jdbc.queryForObject("SELECT payload_content FROM kernel.typed_intent WHERE tenant_id = 'legacy'", String.class))
                .contains("legacy-private");
        jdbc.update("UPDATE kernel.typed_intent SET status = 'SUCCEEDED' WHERE tenant_id = 'legacy'");
        jdbc.execute("""
                INSERT INTO kernel.typed_event
                    (id, intent_id, tenant_id, sequence, event_type, event_contract_version, event_format_version,
                     event_content, event_checksum, resulting_state_version, projection_type, projection_contract_version,
                     projection_format_version, projection_content, projection_checksum, occurred_at)
                SELECT gen_random_uuid(), id, tenant_id, 1, 'Changed', 1, 1, '{"name":"legacy-private"}', repeat('a', 64),
                       2, 'Projection', 1, 1, '{"name":"legacy-private"}', repeat('a', 64), now()
                FROM kernel.typed_intent WHERE tenant_id = 'legacy'
                """);
        migrateWithReusedConnection(application, upgrade, false);
        assertThat(jdbc.queryForList("SELECT name FROM recovered_domain_record", String.class)).containsExactly("legacy-private");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kernel.typed_intent WHERE tenant_id = 'legacy'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kernel.typed_event WHERE tenant_id = 'legacy'", Integer.class)).isEqualTo(1);

        var first = new SemanticDeploymentGuard(dataSource, "test.Application", "a".repeat(64));
        var equivalent = new SemanticDeploymentGuard(dataSource, "test.Application", "a".repeat(64));
        var changed = new SemanticDeploymentGuard(dataSource, "test.Application", "b".repeat(64));
        try {
            first.start();
            equivalent.start();
            assertThatThrownBy(changed::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deployment guard");
        } finally {
            equivalent.stop();
            first.stop();
        }
        changed.start();
        changed.stop();
    }

    private void migrateWithReusedConnection(Flyway application, Flyway upgrade, boolean pending) throws Exception {
        var delegate = upgrade.getConfiguration().getDataSource();
        try (var physical = delegate.getConnection()) {
            var first = new AtomicBoolean(true);
            // Model a pool returning its backend without closing the PostgreSQL session.
            var pooled = new DelegatingDataSource(delegate) {
                @Override public Connection getConnection() throws SQLException {
                    if (!first.getAndSet(false)) return super.getConnection();
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                                if (method.getName().equals("close")) return null;
                                try { return method.invoke(physical, arguments); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                }
            };
            var kernel = Flyway.configure().configuration(upgrade.getConfiguration()).dataSource(pooled).load();
            if (pending) {
                assertThatThrownBy(() -> KernelAutoConfiguration.migrateDomainData(application, kernel, "test.Cutover"))
                        .hasMessageContaining("Drain pending Intents");
            } else {
                KernelAutoConfiguration.migrateDomainData(application, kernel, "test.Cutover");
            }
            assertThat(physical.getAutoCommit()).isTrue();
            try (var statement = physical.createStatement()) {
                try (var result = statement.executeQuery("SHOW row_security")) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("on");
                }
                try (var result = statement.executeQuery("SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory'")) {
                    result.next();
                    assertThat(result.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    @Order(2)
    void guardCommitsItsChecksumAndFailsReadinessWhenItsSessionIsLost() throws Exception {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .load()
                .migrate();
        var nonAutoCommit = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setAutoCommit(false);
                return connection;
            }
        };
        var stopped = new AtomicBoolean();
        var guard = new SemanticDeploymentGuard(
                nonAutoCommit, "test.Session", "c".repeat(64), () -> stopped.set(true));
        guard.start();
        assertThat(new JdbcTemplate(dataSource).queryForObject("""
                SELECT semantic_pack_checksum FROM kernel.application_semantic_deployment
                WHERE application_id = 'test.Session'
                """, String.class)).isEqualTo("c".repeat(64));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET ROLE kernel_runtime");
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE kernel.application_semantic_deployment
                    SET semantic_pack_checksum = repeat('f', 64)
                    WHERE application_id = 'test.Session'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            statement.execute("SELECT pg_advisory_lock(42)");
            assertThatThrownBy(() -> statement.execute("""
                    SELECT kernel.set_application_semantic_deployment(
                        'test.Session', repeat('f', 64))
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("requires exclusive lock");
            statement.execute("SELECT pg_advisory_unlock(42)");
        }

        new JdbcTemplate(dataSource).queryForObject("""
                SELECT pg_terminate_backend(locking.pid)
                FROM pg_locks locking
                WHERE locking.locktype = 'advisory' AND locking.granted
                LIMIT 1
                """, Boolean.class);
        assertThat(guard.isRunning()).isFalse();
        assertThat(stopped).isTrue();

        var changed = new SemanticDeploymentGuard(dataSource, "test.Session", "d".repeat(64));
        changed.start();
        changed.stop();
        guard.stop();
    }

    @Test
    @Order(3)
    void installsBoundedWorkQueueAndHistoryIndexes() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        assertThat(Set.copyOf(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'kernel' AND indexname IN (
                    'typed_intent_pending_due', 'typed_intent_retry_due', 'typed_intent_claimed_expiry',
                    'typed_intent_tenant_history', 'typed_reevaluation_due')
                """, String.class))).containsExactlyInAnyOrder(
                "typed_intent_pending_due", "typed_intent_retry_due", "typed_intent_claimed_expiry",
                "typed_intent_tenant_history", "typed_reevaluation_due");
    }

    @Test
    @Order(4)
    void kernelSchemaCannotStoreDomainPayloadCopies() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'kernel'
                  AND column_name IN ('content', 'payload_content', 'event_content', 'projection_content')
                """, String.class)).isEmpty();
    }

    @Test
    void workerStopsClaimingAndWaitsForCurrentPoll() throws Exception {
        var policy = new IntentWorkerProperties();
        policy.setPollingInterval(Duration.ofMillis(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var claimedAfterStop = new AtomicBoolean();
        var worker = new FixedDelayWorker(policy) {
            @Override String threadName() { return "test-worker"; }

            @Override void poll() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                claimedAfterStop.set(isAcceptingWork());
            }
        };

        worker.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        worker.stop(stopped::countDown);
        assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isFalse();
        assertThat(claimedAfterStop).isFalse();
    }
}
