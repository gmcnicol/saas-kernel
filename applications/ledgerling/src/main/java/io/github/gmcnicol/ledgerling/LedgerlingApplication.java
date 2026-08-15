package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.ProjectedState;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.ApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.EventProjector;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.observation.ObservationRegistry;

@SpringBootApplication
public class LedgerlingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlingApplication.class, args);
    }

    @Bean
    SemanticPack ledgerlingSemanticPack() {
        return () -> "semantic-pack/manifest.properties";
    }

    @Bean
    AuthorisationBundle ledgerlingAuthorisationBundle() {
        return () -> "authorisation/manifest.properties";
    }

    @Bean
    AuthorisationModel ledgerlingAuthorisationModel() {
        return new AuthorisationModel() {
            @Override public String subjectType() { return "ledgerling.Filing"; }
            @Override public String resourceType() { return "Filing"; }
            @Override public Map<String, String> fields() {
                return Map.of(
                        "io.github.gmcnicol.ledgerling.Filing.status", "status",
                        "io.github.gmcnicol.ledgerling.Filing.staffNote", "staffNote");
            }
        };
    }

    @Bean
    FactDerivation filingDueSoonDerivation() {
        return FactDerivation.of(
                "io.github.gmcnicol.ledgerling.FilingDueSoon",
                "io.github.gmcnicol.ledgerling.deriveFilingDueSoon",
                (state, evaluatedAt) -> {
                    Instant dueAt = Instant.parse(state.values().get("filingDueAt"));
                    Instant startsAt = dueAt.minus(Duration.ofDays(7));
                    return evaluatedAt.isBefore(startsAt)
                            ? FactDerivation.Derivation.later(startsAt)
                            : FactDerivation.Derivation.fact(Map.of("filingDueAt", dueAt.toString()));
                });
    }

    @Bean
    FactDerivation recordsOutstandingDerivation() {
        return FactDerivation.of(
                "io.github.gmcnicol.ledgerling.RecordsOutstanding",
                "io.github.gmcnicol.ledgerling.deriveRecordsOutstanding",
                (state, evaluatedAt) -> Boolean.parseBoolean(state.values().getOrDefault("recordsOutstanding", "false"))
                        ? FactDerivation.Derivation.fact(Map.of(
                                "requestId", state.values().get("documentRequestId")))
                        : FactDerivation.Derivation.none());
    }

    @Bean
    ApplicabilityPolicy recordRecordsReceivedApplicability() {
        return ApplicabilityPolicy.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived",
                "io.github.gmcnicol.ledgerling.recordsOutstanding",
                (state, facts) -> facts.stream().anyMatch(fact ->
                        fact.type().equals("io.github.gmcnicol.ledgerling.RecordsOutstanding")));
    }

    @Bean
    ApplicabilityPolicy startPreparationApplicability() {
        return ApplicabilityPolicy.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation",
                "io.github.gmcnicol.ledgerling.preparationReady",
                (state, facts) -> facts.stream().noneMatch(fact ->
                                fact.type().equals("io.github.gmcnicol.ledgerling.RecordsOutstanding"))
                        && !Boolean.parseBoolean(state.values().getOrDefault("preparationStarted", "false")));
    }

    @Bean
    IntentHandler recordRecordsReceivedHandler() {
        return IntentHandler.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived", (intent, payload, state) -> {
                    var resultingState = new HashMap<>(state.values());
                    resultingState.put("recordsOutstanding", "false");
                    resultingState.put("recordsReceivedAt", payload.values().get("receivedAt"));
                    return List.of(new Event("io.github.gmcnicol.ledgerling.RecordsReceived", 1,
                            Map.of("requestId", state.values().get("documentRequestId")), resultingState));
                });
    }

    @Bean
    IntentHandler startPreparationHandler() {
        return IntentHandler.of(
                "io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation", (intent, payload, state) -> {
                    var resultingState = new HashMap<>(state.values());
                    resultingState.put("preparationStarted", "true");
                    return List.of(new Event("io.github.gmcnicol.ledgerling.PreparationStarted", 1,
                            Map.of("requestId", state.values().get("documentRequestId")), resultingState));
                });
    }

    @Bean
    SemanticVersionAdapter recordsReceivedPayloadV1() {
        return adapter(SemanticVersionAdapter.Contract.PAYLOAD,
                "io.github.gmcnicol.ledgerling.RecordRecordsReceivedInput");
    }

    @Bean
    SemanticVersionAdapter startPreparationPayloadV1() {
        return adapter(SemanticVersionAdapter.Contract.PAYLOAD,
                "io.github.gmcnicol.ledgerling.StartPreparationInput");
    }

    @Bean
    SemanticVersionAdapter recordsReceivedEventV1() {
        return adapter(SemanticVersionAdapter.Contract.EVENT,
                "io.github.gmcnicol.ledgerling.RecordsReceived");
    }

    @Bean
    SemanticVersionAdapter preparationStartedEventV1() {
        return adapter(SemanticVersionAdapter.Contract.EVENT,
                "io.github.gmcnicol.ledgerling.PreparationStarted");
    }

    @Bean
    EventProjector recordsReceivedProjector(JdbcTemplate jdbc) {
        return EventProjector.of("io.github.gmcnicol.ledgerling.RecordsReceived",
                (state, event) -> projectFiling(jdbc, state, event.resultingState()));
    }

    @Bean
    EventProjector preparationStartedProjector(JdbcTemplate jdbc) {
        return EventProjector.of("io.github.gmcnicol.ledgerling.PreparationStarted",
                (state, event) -> projectFiling(jdbc, state, event.resultingState()));
    }

    @Bean
    PresentationPack ledgerlingPresentationPack(ObservationRegistry observations) {
        return LedgerlingPresentation.defaultPack().observed(observations);
    }

    private static SemanticVersionAdapter adapter(SemanticVersionAdapter.Contract contract, String type) {
        return SemanticVersionAdapter.identity(contract, type, 1, 2);
    }

    private static void projectFiling(JdbcTemplate jdbc, ProjectedState state, Map<String, String> values) {
        jdbc.update("""
                INSERT INTO ledger_filing_projection
                    (tenant_id, filing_id, client_reference, filing_due_at,
                     records_outstanding, preparation_started)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, filing_id) DO UPDATE SET
                    client_reference = EXCLUDED.client_reference,
                    filing_due_at = EXCLUDED.filing_due_at,
                    records_outstanding = EXCLUDED.records_outstanding,
                    preparation_started = EXCLUDED.preparation_started
                """, state.tenantId(), state.subject().id(),
                values.getOrDefault("clientReference", state.subject().id()),
                java.sql.Timestamp.from(Instant.parse(values.get("filingDueAt"))),
                Boolean.parseBoolean(values.getOrDefault("recordsOutstanding", "false")),
                Boolean.parseBoolean(values.getOrDefault("preparationStarted", "false")));
    }
}
