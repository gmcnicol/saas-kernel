package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.contract.EffectiveCapabilityContract;
import io.github.gmcnicol.kernel.contract.WorkflowTestDatabase;
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
        WorkflowTestDatabase.register(properties::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }
    @Autowired Kernel kernel;
    @Autowired CrmContactQueries contacts;
    @Autowired PlatformTransactionManager transactions;
    @TestBean(methodName = "requiredModel")
    TypedAuthorisationModel<FollowUpProjection> typedCrmAuthorisationModel;
    @TestBean(methodName = "applicationGrants") ApplicationGrants resolver;
    @TestBean(methodName = "currentClock") java.time.Clock clock;
    static final TestClock current = new TestClock();
    static java.time.Clock currentClock() { return current; }
    @Override protected TestClock clock() { return current; }
    @TestBean(methodName = "capabilityBundle") AuthorisationBundle crmAuthorisationBundle;

    static AuthorisationBundle capabilityBundle() { return () -> "capabilities/manifest.properties"; }

    static TypedAuthorisationModel<FollowUpProjection> requiredModel() {
        return new TypedAuthorisationModel<>(FollowUpProjection.TYPE,
                Set.of(FollowUpProjection.CONTACT_ID, FollowUpProjection.NEXT_CONTACT_DUE_AT),
                Set.of(FollowUpDue.TYPE), Set.of(EngageContact.TYPE));
    }
    static ApplicationGrants applicationGrants() {
        return new ApplicationGrants(new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(2))));
    }
    @Override protected ApplicationGrants grants() { return resolver; }
    @Override protected TypedCapability<?> unrelatedCapability() {
        return new TypedCapability<>(ExportContacts.TYPE, new ExportContacts());
    }
    @Override protected TypedCapability<?> capabilityWithRemaining(int remaining) {
        return new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(remaining)));
    }
    @Override protected void restrictResource(Flow flow) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                UPDATE crm_contact_engagement_projection SET next_contact_due_at = '2099-01-01'
                WHERE tenant_id = ?
                """, flow.tenant());
    }
    @Override protected void removeResource(Flow flow) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("DELETE FROM crm_contact_engagement_projection WHERE tenant_id = ?", flow.tenant());
    }
    @Override protected Flow flow(String id) {
        String tenant = "capability-" + id;
        String contactId = id.startsWith("restricted") ? "other" : "member";
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES (?, ?, 'contact', '2020-01-01', ?)
                """, tenant, contactId, UUID.randomUUID());
        var projection = contacts.projection(tenant, contactId);
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), Instant.parse("2020-01-02T00:00:00Z"));
        return new Flow(kernel, tenant, new Principal("Owner", "member"), snapshot.id(), FollowUpProjection.TYPE,
                TypedCrmActions.RECORD_INTERACTION.candidate(new RecordInteractionCandidateV1("capability")),
                work -> new TransactionTemplate(transactions).executeWithoutResult(status -> work.run()));
    }
}
