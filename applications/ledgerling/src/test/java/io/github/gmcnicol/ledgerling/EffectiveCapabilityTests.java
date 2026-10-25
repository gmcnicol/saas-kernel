package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class EffectiveCapabilityTests extends EffectiveCapabilityContract {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "kernel_test_login");
        properties.add("spring.datasource.password", () -> "kernel-test");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", postgres::getUsername);
        properties.add("spring.flyway.password", postgres::getPassword);
        properties.add("kernel.intent-worker.enabled", () -> "false");
    }
    @Autowired Kernel kernel;
    @Autowired LedgerlingFilingQueries filings;
    @Autowired PlatformTransactionManager transactions;
    @TestBean(methodName = "requiredModel")
    TypedAuthorisationModel<FilingProjection> ledgerlingAuthorisationModel;
    @TestBean(methodName = "applicationGrants") ApplicationGrants resolver;
    @TestBean(methodName = "capabilityBundle") AuthorisationBundle ledgerlingAuthorisationBundle;
    @TestBean(methodName = "currentClock") java.time.Clock clock;
    static final TestClock current = new TestClock();
    static java.time.Clock currentClock() { return current; }
    @Override protected TestClock clock() { return current; }

    static AuthorisationBundle capabilityBundle() { return () -> "capabilities/manifest.properties"; }
    static TypedAuthorisationModel<FilingProjection> requiredModel() {
        return new TypedAuthorisationModel<>(FilingProjection.TYPE, Set.of(FilingProjection.CLIENT_REFERENCE),
                Set.of(RecordsOutstanding.TYPE), Set.of(ManageFiling.TYPE));
    }
    static ApplicationGrants applicationGrants() {
        return new ApplicationGrants(new TypedCapability<>(ManageFiling.TYPE, new ManageFiling(Optional.of(2))));
    }
    @Override protected ApplicationGrants grants() { return resolver; }
    @Override protected TypedCapability<?> unrelatedCapability() {
        return new TypedCapability<>(ExportFilings.TYPE, new ExportFilings());
    }
    @Override protected Flow flow(String id) {
        String tenant = "capability-" + id;
        String reference = id.startsWith("restricted") ? "other-client" : "client-one";
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, request_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES (?, ?, ?, ?, '2020-01-01', true, false)
                """, tenant, id, "request-" + id, reference);
        var projection = filings.projection(tenant, id);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(FilingId.TYPE, projection.filingId()), 1,
                FilingProjection.TYPE, projection), Instant.parse("2020-01-02T00:00:00Z"));
        return new Flow(kernel, tenant, new Principal("Staff", "employee"), snapshot.id(), FilingProjection.TYPE,
                LedgerlingActions.RECORD_RECORDS_RECEIVED.candidate(
                        new RecordRecordsReceivedCandidateV1(Instant.parse("2031-01-01T00:00:00Z"))),
                work -> new TransactionTemplate(transactions).executeWithoutResult(status -> work.run()));
    }
}
