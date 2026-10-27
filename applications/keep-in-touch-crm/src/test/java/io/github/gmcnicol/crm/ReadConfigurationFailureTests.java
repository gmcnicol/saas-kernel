package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.*;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.*;
import io.github.gmcnicol.kernel.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
abstract class ReadConfigurationFailureFixture {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")).withInitScript("postgres-init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        io.github.gmcnicol.kernel.contract.WorkflowTestDatabase.register(p::add,
                postgres::getJdbcUrl, postgres::getUsername, postgres::getPassword);
    }
    @Autowired AuthorisedReads reads;
    @TestBean(methodName = "bundle") AuthorisationBundle crmAuthorisationBundle;
    static AuthorisationBundle bundle() { return () -> "reads/manifest.properties"; }
    @Test void missingConfigurationDeniesBeforeCandidateSelection() {
        var request = new ReadRequest<>("unconfigured", new Principal("Owner", "member"),
                new TypedSubject<>(NetworkId.TYPE, new NetworkId("network-a")), NetworkContactRead.TYPE,
                Set.of(NetworkContactRead.READ_DETAILS_DISPLAY_NAME), "io.github.gmcnicol.crm.reads",
                AuthorisedReadTests.checksum(), ReadLimits.DEFAULT);
        assertThatThrownBy(() -> reads.one(request, new ReadSource<NetworkContactRead>() {
            public String revision(java.sql.Connection c) { return "1"; }
            public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<NetworkContactRead>> sink) {
                throw new AssertionError("Must not select candidates without model and authority");
            }
        })).isExactlyInstanceOf(AuthorisationDeniedException.class).hasMessage("Authorisation denied").hasNoCause();
    }
}

@Import(ReadAuthorityMissingTests.Model.class)
class ReadAuthorityMissingTests extends ReadConfigurationFailureFixture {
    @TestConfiguration static class Model {
        @Bean TypedAuthorisationModel<NetworkContactRead> readModel() {
            return new TypedAuthorisationModel<>(NetworkContactRead.TYPE, Set.of(NetworkContactRead.NETWORK, NetworkContactRead.OWNER),
                    Set.of(), Set.of(EngageContact.TYPE));
        }
    }
}

@Import(ReadModelMissingTests.Authority.class)
class ReadModelMissingTests extends ReadConfigurationFailureFixture {
    @TestConfiguration static class Authority {
        @Bean EffectiveCapabilityResolver authority() {
            return request -> new EffectiveCapabilityResolver.Resolution("1", new EffectiveCapabilityResolver.Context(
                    1, request.scope(), request.evaluatedAt(), request.evaluatedAt().plusSeconds(60), "1",
                    List.of(new TypedCapability<>(EngageContact.TYPE, new EngageContact(Optional.of(2))))));
        }
    }
}
