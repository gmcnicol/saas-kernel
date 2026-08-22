package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.ledgerling.bindings.GeneratedSemanticBindings;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingDueSoon;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.LedgerlingActions;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.PreparationStartedEventV1;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordRecordsReceivedCandidateV1;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordsOutstanding;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordsReceivedEventV1;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.StartPreparationCandidateV1;
import io.github.gmcnicol.kernel.application.TypedAuthorisationModel;
import io.github.gmcnicol.kernel.application.TypedStateTransition;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import io.github.gmcnicol.kernel.semanticpack.TypedEventProjector;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class TypedLedgerlingSemanticConfiguration {

    @Bean
    SemanticBindings ledgerlingBindings() {
        return GeneratedSemanticBindings.INSTANCE;
    }

    @Bean
    TypedAuthorisationModel<FilingProjection> ledgerlingAuthorisationModel() {
        return new TypedAuthorisationModel<>(
                FilingProjection.TYPE,
                Set.of(FilingProjection.CLIENT_REFERENCE, FilingProjection.FILING_DUE_AT),
                Set.of(FilingDueSoon.TYPE, RecordsOutstanding.TYPE));
    }

    @Bean
    TypedFactDerivation<FilingProjection, FilingDueSoon> filingDueSoonDerivation() {
        return FilingDueSoon.DERIVATION.bind((projection, evaluatedAt) -> {
            var startsAt = projection.filingDueAt().minus(Duration.ofDays(7));
            return evaluatedAt.isBefore(startsAt)
                    ? TypedFactDerivation.Result.later(startsAt)
                    : TypedFactDerivation.Result.fact(new FilingDueSoon(projection.filingDueAt()));
        });
    }

    @Bean
    TypedFactDerivation<FilingProjection, RecordsOutstanding> recordsOutstandingDerivation() {
        return RecordsOutstanding.DERIVATION.bind((projection, evaluatedAt) -> projection.recordsOutstanding()
                ? TypedFactDerivation.Result.fact(new RecordsOutstanding(projection.requestId()))
                : TypedFactDerivation.Result.none());
    }

    @Bean
    TypedApplicabilityPolicy<FilingProjection> recordRecordsReceivedApplicability() {
        return LedgerlingActions.RECORD_RECORDS_RECEIVED.bindApplicability(
                (projection, facts) -> facts.find(RecordsOutstanding.TYPE).isPresent());
    }

    @Bean
    TypedApplicabilityPolicy<FilingProjection> startPreparationApplicability() {
        return LedgerlingActions.START_PREPARATION.bindApplicability(
                (projection, facts) -> !projection.recordsOutstanding() && !projection.preparationStarted());
    }

    @Bean
    TypedIntentHandler<FilingProjection, RecordRecordsReceivedCandidateV1, RecordsReceivedEventV1>
    recordRecordsReceivedHandler() {
        return LedgerlingActions.RECORD_RECORDS_RECEIVED.bindHandler((intent, payload, projection) -> List.of(
                new TypedStateTransition<>(
                        new RecordsReceivedEventV1(projection.requestId()),
                        new FilingProjection(
                                projection.filingId(), projection.requestId(), projection.clientReference(),
                                projection.filingDueAt(), false, projection.preparationStarted()))));
    }

    @Bean
    TypedIntentHandler<FilingProjection, StartPreparationCandidateV1, PreparationStartedEventV1>
    startPreparationHandler() {
        return LedgerlingActions.START_PREPARATION.bindHandler((intent, payload, projection) -> List.of(
                new TypedStateTransition<>(
                        new PreparationStartedEventV1(projection.requestId()),
                        new FilingProjection(
                                projection.filingId(), projection.requestId(), projection.clientReference(),
                                projection.filingDueAt(), projection.recordsOutstanding(), true))));
    }

    @Bean
    TypedEventProjector<FilingProjection, RecordsReceivedEventV1> recordsReceivedProjector(JdbcTemplate jdbc) {
        return LedgerlingActions.RECORD_RECORDS_RECEIVED.bindProjector(
                RecordsReceivedEventV1.TYPE, transition -> project(jdbc, transition.tenantId(),
                        transition.resultingProjection()));
    }

    @Bean
    TypedEventProjector<FilingProjection, PreparationStartedEventV1> preparationStartedProjector(JdbcTemplate jdbc) {
        return LedgerlingActions.START_PREPARATION.bindProjector(
                PreparationStartedEventV1.TYPE, transition -> project(jdbc, transition.tenantId(),
                        transition.resultingProjection()));
    }

    private static void project(JdbcTemplate jdbc, String tenantId, FilingProjection projection) {
        jdbc.update("""
                UPDATE ledger_filing_projection
                SET records_outstanding = ?, preparation_started = ?
                WHERE tenant_id = ? AND filing_id = ?
                """, projection.recordsOutstanding(), projection.preparationStarted(), tenantId,
                projection.filingId().value());
    }
}
