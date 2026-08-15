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
    void upgradesMigrationsForwardAndAllowsOnlyEquivalentSemanticOverlap() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .target("8")
                .load()
                .migrate();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                        "SELECT max(version) FROM kernel.flyway_kernel_schema_history WHERE success", String.class))
                .isEqualTo("8");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kernel/migration")
                .schemas("kernel")
                .defaultSchema("kernel")
                .table("flyway_kernel_schema_history")
                .load()
                .migrate();

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
                    'intent_pending_due', 'intent_retry_due', 'intent_claimed_expiry',
                    'intent_tenant_history', 'reevaluation_unleased_due', 'reevaluation_lease_expiry')
                """, String.class))).containsExactlyInAnyOrder(
                "intent_pending_due", "intent_retry_due", "intent_claimed_expiry",
                "intent_tenant_history", "reevaluation_unleased_due", "reevaluation_lease_expiry");
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
