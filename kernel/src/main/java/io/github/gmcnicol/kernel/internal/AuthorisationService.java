package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ActionOffer;
import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.AuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.Principal;
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

    AuthorisationService(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            CedarAuthoriser cedar,
            EvaluationStore evaluations) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cedar = cedar;
        this.evaluations = evaluations;
    }

    AuthorisationEnvelope authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt) {
        if (snapshotId == null || principal == null || authorisedAt == null) {
            throw new AuthorisationDeniedException();
        }
        try {
            return transactions.execute(status -> authoriseInTransaction(tenantId, snapshotId, principal, authorisedAt));
        } catch (AuthorisationDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthorisationDeniedException();
        }
    }

    private AuthorisationEnvelope authoriseInTransaction(
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
        return new AuthorisationEnvelope(snapshotId, fields, facts, offers);
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
        return new ActionOffer(id, action.actionId());
    }

}
