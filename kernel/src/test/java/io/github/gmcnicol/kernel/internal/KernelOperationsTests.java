package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.UserCredentialsDataSourceAdapter;
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
    @Order(5)
    void beanGuardLeavesTheSoleRequestConnectionAvailableAndPreservesConnectionProperties() throws Exception {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var properties = new Properties();
        properties.setProperty("ApplicationName", "kernel-guard-isolation");
        properties.setProperty("currentSchema", "kernel,public");
        dataSource.setConnectionProperties(properties);
        migrateGuardDatabase(dataSource);
        var requestPool = new SingleConnectionPool(dataSource);
        var changed = new SemanticDeploymentGuard(dataSource, "test.PoolIsolation", "f".repeat(64));
        var dedicated = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dedicated.setConnectionProperties(properties);
        guardContext(requestPool, "test.PoolIsolation")
                .withBean("semanticDeploymentGuardDataSource", DataSource.class, () -> dedicated,
                        definition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                                .setDefaultCandidate(false))
                .run(context -> {
            assertThat(context).hasNotFailed();
            var guard = context.getBean(SemanticDeploymentGuard.class);
            assertThat(guard.isRunning()).isTrue();
            try (var request = requestPool.getConnection(); var statement = request.createStatement()) {
                int requestPid;
                try (var result = statement.executeQuery("SELECT 1, pg_backend_pid()")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                    requestPid = result.getInt(2);
                }
                assertThatThrownBy(requestPool::getConnection).hasMessage("Request pool exhausted");
                var session = (Connection) ReflectionTestUtils.getField(guard, "connection");
                try (var guardStatement = session.createStatement(); var result = guardStatement.executeQuery(
                        "SELECT current_setting('application_name'), current_schema(), current_user, pg_backend_pid(), session_user")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("kernel-guard-isolation");
                    assertThat(result.getString(2)).isEqualTo("kernel");
                    assertThat(result.getString(3)).isEqualTo("kernel_runtime");
                    assertThat(result.getInt(4)).isNotEqualTo(requestPid);
                    assertThat(result.getString(5)).isEqualTo(postgres.getUsername());
                }
                assertThat(guard.isRunning()).isTrue();
                assertThatThrownBy(changed::start).hasStackTraceContaining("Semantic Pack changed");
                assertThat(new JdbcTemplate(dataSource).queryForObject("""
                        SELECT count(*) FROM pg_locks
                        WHERE locktype = 'advisory' AND granted AND mode = 'ShareLock'
                          AND pid IN (SELECT pid FROM pg_stat_activity WHERE application_name = 'kernel-guard-isolation')
                        """, Integer.class)).isEqualTo(1);
            }
        });
        changed.start();
        changed.stop();
        assertThat(requestPool.permit.availablePermits()).isEqualTo(1);
    }

    @Test
    @Order(6)
    void beanGuardRefusesToCloneOpaqueSourcesOrReuseTheRequestSource() {
        var opaque = new org.postgresql.ds.PGSimpleDataSource();
        guardContext(opaque, "test.Opaque").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Cannot safely create an independent Semantic Pack deployment guard connection. "
                    + "Define @Bean(defaultCandidate = false) named 'semanticDeploymentGuardDataSource': "
                    + "a dedicated non-pooled DataSource using the same runtime login, URL, driver and SSL/options "
                    + "as the request DataSource; account for one additional PostgreSQL connection.");
        });
        guardContext(opaque, "test.SharedSource").withUserConfiguration(SharedGuardDataSource.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Cannot safely create an independent");
        });
    }

    @Test
    @Order(7)
    void dedicatedOverrideDoesNotDisableBootsMainDataSource() {
        var admin = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        migrateGuardDatabase(admin);
        guardContext("test.DedicatedOverride")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.type=" + DriverManagerDataSource.class.getName(),
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword())
                .withUserConfiguration(DedicatedGuardDataSource.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys(
                            "dataSource", "semanticDeploymentGuardDataSource");
                    var main = context.getBean("dataSource", DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isSameAs(main);
                    assertThat(main).isExactlyInstanceOf(DriverManagerDataSource.class);
                    assertThat(context.getBean(SemanticDeploymentGuard.class).isRunning()).isTrue();
                    assertThat(new JdbcTemplate(main).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                });
    }

    @Test
    @Order(8)
    void beanGuardPreservesSimpleSourceAndReleasesExclusionOnSessionLoss() {
        var dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var properties = new Properties();
        properties.setProperty("ApplicationName", "kernel-simple-guard");
        properties.setProperty("currentSchema", "kernel,public");
        dataSource.setConnectionProperties(properties);
        migrateGuardDatabase(dataSource);
        var requestPool = new SingleConnectionPool(dataSource);
        var closed = new CountDownLatch(1);
        var refusingTraffic = new AtomicBoolean();
        guardContext(dataSource, "test.BeanSessionLoss").withInitializer(context -> context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) closed.countDown();
            if (event instanceof AvailabilityChangeEvent<?> availability
                    && availability.getState() == ReadinessState.REFUSING_TRAFFIC) refusingTraffic.set(true);
        })).run(context -> {
            assertThat(context).hasNotFailed();
            var guard = context.getBean(SemanticDeploymentGuard.class);
            var session = (Connection) ReflectionTestUtils.getField(guard, "connection");
            int pid;
            try (var statement = session.createStatement(); var result = statement.executeQuery(
                    "SELECT current_setting('application_name'), current_schema(), pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("kernel-simple-guard");
                assertThat(result.getString(2)).isEqualTo("kernel");
                pid = result.getInt(3);
            }
            try (var request = requestPool.getConnection(); var statement = request.prepareStatement("SELECT pg_terminate_backend(?)")) {
                statement.setInt(1, pid);
                statement.execute();
                assertThat(guard.isRunning()).isFalse();
                assertThat(refusingTraffic).isTrue();
                assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
                var changed = new SemanticDeploymentGuard(dataSource, "test.BeanSessionLoss", "b".repeat(64));
                try {
                    changed.start();
                    assertThat(changed.isRunning()).isTrue();
                } finally {
                    changed.stop();
                }
            }
        });
        assertThat(requestPool.permit.availablePermits()).isEqualTo(1);
    }

    @Test
    void beanGuardRejectsCredentialAdaptersWithoutConnectingWithTargetCredentials() {
        var driver = new GuardTestDriver();
        var target = new SimpleDriverDataSource(
                driver, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var adapter = new UserCredentialsDataSourceAdapter();
        adapter.setTargetDataSource(target);
        adapter.setUsername("synthetic-runtime-login");
        adapter.setPassword("synthetic-runtime-password");
        guardContext(adapter, "test.CredentialAdapter").run(context -> {
            assertThat(driver.connections).hasValue(0);
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("semanticDeploymentGuardDataSource");
        });
    }

    @Test
    void beanGuardRejectsArbitraryDelegatesAndAopAdvice() {
        var target = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
        for (var opaque : java.util.List.of(new DelegatingDataSource(target) { }, (DataSource) proxy.getProxy())) {
            guardContext(opaque, "test.OpaqueWrapper").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("semanticDeploymentGuardDataSource");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void derivedGuardDoesNotLogPasswordBearingUrlsAtDebug(boolean simple) throws Exception {
        var url = GuardTestDriver.URL;
        var driver = new GuardTestDriver();
        var source = simple
                ? new SimpleDriverDataSource(driver, url, "synthetic-user", "synthetic-password")
                : new DriverManagerDataSource(url, "synthetic-user", "synthetic-password");
        var defaults = new Properties();
        defaults.setProperty("default-option", "preserved-default");
        var properties = new Properties(defaults);
        properties.setProperty("ApplicationName", "kernel-log-guard");
        source.setConnectionProperties(properties);
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var jdbcLogger = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.datasource");
        var guardLogger = (Logger) LoggerFactory.getLogger("io.github.gmcnicol.kernel.internal");
        var jdbcLevel = jdbcLogger.getLevel();
        var guardLevel = guardLogger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        root.addAppender(capture);
        jdbcLogger.setLevel(Level.DEBUG);
        guardLogger.setLevel(Level.DEBUG);
        DriverManager.registerDriver(driver);
        try {
            guardLogger.debug("guard-log-capture-active");
            guardContext(source, "test.SafeUrlLogging" + simple).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(SemanticDeploymentGuard.class).isRunning()).isTrue();
                var derived = (org.springframework.jdbc.datasource.AbstractDriverBasedDataSource)
                        ReflectionTestUtils.getField(context.getBean(SemanticDeploymentGuard.class), "dataSource");
                assertThat(derived.getConnectionProperties()).isNotSameAs(properties);
                assertThat(derived.getConnectionProperties().getProperty("default-option"))
                        .isEqualTo("preserved-default");
            });
            assertThat(driver.connections).hasValue(1);
            assertThat(capture.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("guard-log-capture-active")
                    .noneMatch(message -> message.contains(url) || message.contains("synthetic-url-password"));
        } finally {
            DriverManager.deregisterDriver(driver);
            jdbcLogger.setLevel(jdbcLevel);
            guardLogger.setLevel(guardLevel);
            root.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void derivedGuardRejectsANullDriverConnectionWithoutLoggingTheUrl() {
        var source = new SimpleDriverDataSource(
                new NullConnectionDriver(), "jdbc:guard-null:test?password=synthetic-null-password");
        guardContext(source, "test.NullDriverConnection").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(SQLException.class)
                    .hasStackTraceContaining("Driver did not accept the deployment guard JDBC URL");
        });
    }

    private static final class GuardTestDriver extends org.postgresql.Driver {
        private static final String URL = "jdbc:guard-logging:test?password=synthetic-url-password";
        private final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();

        @Override public boolean acceptsURL(String url) {
            return URL.equals(url) || super.acceptsURL(url);
        }

        @Override public Connection connect(String url, Properties info) throws SQLException {
            connections.incrementAndGet();
            if (!URL.equals(url)) return super.connect(url, info);
            assertThat(info.getProperty("user")).isEqualTo("synthetic-user");
            assertThat(info.getProperty("password")).isEqualTo("synthetic-password");
            assertThat(info.getProperty("ApplicationName")).isEqualTo("kernel-log-guard");
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .getConnection();
        }
    }

    private static final class NullConnectionDriver extends org.postgresql.Driver {
        @Override public Connection connect(String url, Properties info) { return null; }
    }

    @Configuration(proxyBeanMethods = false)
    static class DedicatedGuardDataSource {
        @Bean(defaultCandidate = false)
        DataSource semanticDeploymentGuardDataSource() {
            return new SimpleDriverDataSource(
                    new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedGuardDataSource {
        @Bean(defaultCandidate = false)
        DataSource semanticDeploymentGuardDataSource(DataSource dataSource) {
            return dataSource;
        }
    }

    private void migrateGuardDatabase(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/kernel/migration")
                .schemas("kernel").defaultSchema("kernel").table("flyway_kernel_schema_history").load().migrate();
    }

    private ApplicationContextRunner guardContext(DataSource dataSource, String applicationId) {
        return guardContext(applicationId).withBean("dataSource", DataSource.class, () -> dataSource);
    }

    private ApplicationContextRunner guardContext(String applicationId) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.application.name=" + applicationId)
                .withBean(SemanticPackVersion.class, () -> new SemanticPackVersion("pack", "a".repeat(64)))
                .withInitializer(context -> {
                    // Register the actual factory method without starting unrelated Kernel services.
                    context.getBeanFactory().registerSingleton("evaluationConfiguration", new EvaluationAutoConfiguration());
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory())
                            .registerBeanDefinition("semanticDeploymentGuard", BeanDefinitionBuilder
                            .rootBeanDefinition(SemanticDeploymentGuard.class)
                            .setFactoryMethodOnBean("semanticDeploymentGuard", "evaluationConfiguration")
                            .setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
                            .getBeanDefinition());
                });
    }

    private static final class SingleConnectionPool extends DelegatingDataSource {
        private final Semaphore permit = new Semaphore(1);

        private SingleConnectionPool(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (!permit.tryAcquire()) throw new SQLException("Request pool exhausted");
            Connection connection;
            try {
                connection = super.getConnection();
            } catch (SQLException | RuntimeException failure) {
                permit.release();
                throw failure;
            }
            var closed = new AtomicBoolean();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("close")) {
                            if (closed.compareAndSet(false, true)) {
                                try { connection.close(); }
                                finally { permit.release(); }
                            }
                            return null;
                        }
                        try { return method.invoke(connection, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
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
