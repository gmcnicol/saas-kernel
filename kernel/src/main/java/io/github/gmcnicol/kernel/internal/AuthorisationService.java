package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ActionOffer;
import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.AuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.PresentationActionOffer;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.application.PresentationFact;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class AuthorisationService {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final CedarAuthoriser cedar;
    private final EvaluationStore evaluations;
    private final TaxiPayloadValidator payloads;
    private final KernelTelemetry telemetry;

    AuthorisationService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            CedarAuthoriser cedar,
            EvaluationStore evaluations,
            TaxiPayloadValidator payloads,
            KernelTelemetry telemetry) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cedar = cedar;
        this.evaluations = evaluations;
        this.payloads = payloads;
        this.telemetry = telemetry;
    }

    AuthorisationEnvelope authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt) {
        if (snapshotId == null || principal == null || authorisedAt == null) {
            throw new AuthorisationDeniedException();
        }
        try {
            return transactions.execute(
                    status -> authoriseInTransaction(tenantId, snapshotId, principal, authorisedAt).envelope());
        } catch (AuthorisationDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthorisationDeniedException();
        }
    }

    PresentationEnvelope present(String tenantId, UUID snapshotId, Principal principal, Instant presentedAt) {
        if (snapshotId == null || principal == null || presentedAt == null) {
            throw new AuthorisationDeniedException();
        }
        try {
            return transactions.execute(status -> {
                Authorised authorised = authoriseInTransaction(tenantId, snapshotId, principal, presentedAt);
                StoredEvaluation evaluation = authorised.evaluation();
                AuthorisationEnvelope envelope = authorised.envelope();
                return new PresentationEnvelope(
                        1,
                        evaluation.subject(),
                        evaluation.id(),
                        evaluation.evaluatedAt(),
                        evaluation.semanticPack().id(),
                        envelope.fields(),
                        envelope.facts().stream()
                                .map(fact -> new PresentationFact(fact.type(), fact.values()))
                                .toList(),
                        envelope.actionOffers().stream()
                                .map(offer -> new PresentationActionOffer(
                                        offer.id(), offer.actionId(), payloads.inputType(offer.actionId())))
                                .toList());
            });
        } catch (AuthorisationDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthorisationDeniedException();
        }
    }

    private Authorised authoriseInTransaction(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt) {
        try {
            TenantContext.use(jdbc, tenantId);
        } catch (IllegalArgumentException exception) {
            throw new AuthorisationDeniedException();
        }
        Map<String, String> fieldMappings = cedar.fields();
        StoredEvaluation evaluation = evaluations.load(tenantId, snapshotId);
        Map<String, String> fields = new LinkedHashMap<>();
        fieldMappings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String value = evaluation.state().get(entry.getValue());
            if (value != null && cedar.allows(principal, evaluation.subject(), entry.getKey())) {
                fields.put(entry.getKey(), value);
            }
        });
        List<Fact> facts = evaluation.facts().stream()
                .filter(fact -> cedar.allows(principal, evaluation.subject(), fact.type()))
                .toList();
        UUID correlation = UUID.randomUUID();
        List<ActionOffer> offers = evaluation.actions().stream()
                .filter(action -> cedar.allows(principal, evaluation.subject(), action.actionId()))
                .map(action -> persistOffer(tenantId, evaluation, principal, action, authorisedAt, correlation))
                .toList();
        return new Authorised(evaluation, new AuthorisationEnvelope(snapshotId, fields, facts, offers));
    }

    private ActionOffer persistOffer(
            String tenantId,
            StoredEvaluation evaluation,
            Principal principal,
            ApplicableAction action,
            Instant authorisedAt,
            UUID correlation) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO kernel.action_offer
                    (id, tenant_id, evaluation_snapshot_id, principal_type, principal_id,
                     subject_type, subject_id, action_id, state_version,
                     applicability_policy_id,
                     semantic_pack_id, semantic_pack_checksum,
                     authorisation_bundle_id, authorisation_bundle_checksum,
                     authorised_at, decision_correlation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, tenantId, evaluation.id(), principal.type(), principal.id(),
                evaluation.subject().type(), evaluation.subject().id(), action.actionId(), evaluation.stateVersion(),
                action.policyId(),
                evaluation.semanticPack().id(), evaluation.semanticPack().checksum(),
                cedar.bundleId(), cedar.bundleChecksum(), Timestamp.from(authorisedAt), correlation);
        if (inserted == 0) {
            id = jdbc.queryForObject("""
                    SELECT id FROM kernel.action_offer
                    WHERE tenant_id = ? AND evaluation_snapshot_id = ? AND principal_type = ?
                      AND principal_id = ? AND action_id = ?
                      AND authorisation_bundle_id = ? AND authorisation_bundle_checksum = ?
                      AND authorised_at = ?
                    """, UUID.class, tenantId, evaluation.id(), principal.type(), principal.id(), action.actionId(),
                    cedar.bundleId(), cedar.bundleChecksum(), Timestamp.from(authorisedAt));
        }
        telemetry.actionOffer(tenantId, evaluation.subject(), evaluation.id(), id, correlation);
        return new ActionOffer(id, action.actionId());
    }

    private record Authorised(StoredEvaluation evaluation, AuthorisationEnvelope envelope) {}

}
