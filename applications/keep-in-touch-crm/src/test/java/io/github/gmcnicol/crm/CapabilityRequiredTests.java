package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.contract.WorkflowTestDatabase;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class CapabilityRequiredTests {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        WorkflowTestDatabase.register(properties::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }

    @Autowired Kernel kernel;
    @Autowired CrmContactQueries contacts;
    @TestBean(methodName = "requiredModel")
    TypedAuthorisationModel<FollowUpProjection> typedCrmAuthorisationModel;

    static TypedAuthorisationModel<FollowUpProjection> requiredModel() {
        return new TypedAuthorisationModel<>(FollowUpProjection.TYPE,
                Set.of(FollowUpProjection.NEXT_CONTACT_DUE_AT), Set.of(FollowUpDue.TYPE), Set.of(EngageContact.TYPE));
    }

    @Test
    void optedInDiscoveryDeniesWithoutResolver() {
        String tenant = "required-" + UUID.randomUUID();
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.update("""
                INSERT INTO crm_contact_engagement_projection
                    (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                VALUES (?, 'contact', 'contact', '2020-01-01', ?)
                """, tenant, UUID.randomUUID());
        var projection = contacts.projection(tenant, "contact");
        var snapshot = kernel.evaluate(new TypedProjectedState<>(tenant,
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), 1,
                FollowUpProjection.TYPE, projection), Instant.parse("2020-01-02T00:00:00Z"));
        assertThatThrownBy(() -> kernel.authorise(tenant, snapshot.id(), new Principal("Owner", "member"),
                Instant.parse("2020-01-02T00:00:00Z"), FollowUpProjection.TYPE))
                .isInstanceOf(AuthorisationDeniedException.class);
    }
}
