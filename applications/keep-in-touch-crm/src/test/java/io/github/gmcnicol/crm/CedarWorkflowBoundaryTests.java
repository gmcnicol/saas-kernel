package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.*;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.contract.WorkflowTestDatabase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class CedarWorkflowBoundaryTests {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("postgres-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        WorkflowTestDatabase.register(properties::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }

    @Autowired Kernel kernel;
    @Autowired CrmContactQueries contacts;
    @TestBean(methodName = "boundaryBundle") AuthorisationBundle crmAuthorisationBundle;
    static AuthorisationBundle boundaryBundle() { return () -> "cedar-boundary/manifest.properties"; }

    @ParameterizedTest @ValueSource(strings = {"errored", "blocked", "allowed"})
    void noFieldsFactsOrOffersEscapeAnErroredOrMatchingForbid(String contactId) {
        String tenant = "cedar-boundary-" + UUID.randomUUID();
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES (?, ?, 'contact', '2020-01-01', ?)
                """, tenant, contactId, UUID.randomUUID());
        var projection = contacts.projection(tenant, contactId);
        Instant now = Instant.parse("2020-01-02T00:00:00Z");
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), now);
        var envelope = kernel.authorise(tenant, snapshot.id(), new Principal("Owner", "member"),
                now, FollowUpProjection.TYPE);

        if (contactId.equals("allowed")) {
            assertThat(envelope.fields()).isNotEmpty();
            assertThat(envelope.facts()).isNotEmpty();
            assertThat(envelope.actionOffers()).isNotEmpty();
        } else {
            assertThat(envelope.fields()).isEmpty();
            assertThat(envelope.facts()).isEmpty();
            assertThat(envelope.actionOffers()).isEmpty();
            assertThat(admin.queryForObject("SELECT count(*) FROM kernel.typed_action_offer WHERE tenant_id = ?",
                    Integer.class, tenant)).isZero();
        }
    }
}
