package example;

import example.bindings.*;
import example.bindings.example.directory.*;
import example.bindings.example.practice.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.semanticpack.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@SpringBootApplication
public class AdoptionApplication {
    public static void main(String[] args) { SpringApplication.run(AdoptionApplication.class, args); }
    @Bean SemanticPack semanticPack() { return () -> "semantic-pack/manifest.properties"; }
    @Bean AuthorisationBundle bundle() { return () -> "authorisation/manifest.properties"; }
    @Bean SemanticBindings bindings() { return GeneratedSemanticBindings.INSTANCE; }
    @Bean SemanticRegistry registry() { return GeneratedSemanticRegistry.INSTANCE; }
    @Bean TypedAuthorisationModel<Entry> directoryModel() {
        return new TypedAuthorisationModel<>(Entry.TYPE, Set.of(Entry.DIRECTORY, Entry.OWNER), Set.of(), Set.of(Access.TYPE));
    }
    @Bean TypedAuthorisationModel<ClientFile> practiceModel() {
        return new TypedAuthorisationModel<>(ClientFile.TYPE, Set.of(ClientFile.PRACTICE, ClientFile.ASSIGNED_TO), Set.of(), Set.of(PracticeAccess.TYPE));
    }
    private String currentPolicy() throws java.io.IOException {
        return new org.springframework.core.io.ClassPathResource("authorisation/checksum.sha256")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    @Bean DirectoryQueries directoryQueries() throws java.io.IOException { return new DirectoryQueries(currentPolicy()); }
    @Bean PracticeQueries practiceQueries() throws java.io.IOException { return new PracticeQueries(currentPolicy()); }
    @Bean EffectiveCapabilityResolver capabilities(DataSource data) { return new CurrentCapabilities(data); }
    @Bean ApplicationWorkflowData workflowData(JdbcTemplate jdbc) { return new DirectoryWorkflowData(jdbc); }
    @Bean TypedApplicabilityPolicy<Entry> reviewApplicable() {
        return DirectoryActions.REVIEW.bindApplicability((entry, facts) -> !entry.reviewed());
    }
    @Bean TypedIntentHandler<Entry, Review, Reviewed> reviewHandler() {
        return DirectoryActions.REVIEW.bindHandler((intent, input, entry) -> List.of(new TypedStateTransition<>(
                new Reviewed(entry.id()), new Entry(entry.id(), entry.directory(), entry.owner(), entry.details(), true))));
    }
    @Bean TypedEventProjector<Entry, Reviewed> reviewProjector(JdbcTemplate jdbc) {
        return DirectoryActions.REVIEW.bindProjector(Reviewed.TYPE, transition -> {
            jdbc.update("UPDATE directory_entry SET reviewed = true, version = version + 1 WHERE tenant_id = ? AND id = ?",
                    transition.tenantId(), transition.event().id().value());
            jdbc.update("UPDATE review_note SET applied = true WHERE tenant_id = ? AND intent_id = ?",
                    transition.tenantId(), transition.intentId());
            jdbc.update("""
                    UPDATE source_revision v SET revision = ? FROM directory_entry e
                    WHERE v.tenant_id = e.tenant_id AND v.scope_id = e.directory AND v.principal_id = e.owner
                      AND e.tenant_id = ? AND e.id = ?
                    """, UUID.randomUUID().toString(), transition.tenantId(), transition.event().id().value());
        });
    }
}
