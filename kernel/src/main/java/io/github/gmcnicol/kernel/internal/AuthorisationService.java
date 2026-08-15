package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ActionOffer;
import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.AuthorisationEnvelope;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

    AuthorisationService(JdbcTemplate jdbc, TransactionOperations transactions, CedarAuthoriser cedar) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cedar = cedar;
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
        StoredEvaluation evaluation = load(tenantId, snapshotId);
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

    private StoredEvaluation load(String tenantId, UUID snapshotId) {
        List<StoredEvaluation> matches = jdbc.query("""
                SELECT subject_type, subject_id, state_version, semantic_pack_id, semantic_pack_checksum
                FROM kernel.evaluation_snapshot WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new StoredEvaluation(
                        snapshotId,
                        new Subject(result.getString("subject_type"), result.getString("subject_id")),
                        result.getLong("state_version"),
                        new SemanticPackVersion(
                                result.getString("semantic_pack_id"), result.getString("semantic_pack_checksum")),
                        new LinkedHashMap<>(),
                        new ArrayList<>(),
                        new ArrayList<>()), tenantId, snapshotId);
        if (matches.isEmpty()) {
            throw new AuthorisationDeniedException();
        }
        StoredEvaluation evaluation = matches.getFirst();
        jdbc.query("""
                SELECT name, value FROM kernel.projected_state_value
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND version = ?
                ORDER BY name
                """, (result, row) -> Map.entry(result.getString("name"), result.getString("value")),
                tenantId, evaluation.subject().type(), evaluation.subject().id(), evaluation.stateVersion())
                .forEach(entry -> evaluation.state().put(entry.getKey(), entry.getValue()));
        List<StoredFact> storedFacts = jdbc.query("""
                SELECT position, fact_type, derivation_id FROM kernel.evaluation_fact
                WHERE tenant_id = ? AND snapshot_id = ? ORDER BY position
                """, (result, row) -> new StoredFact(
                        result.getInt("position"),
                        result.getString("fact_type"),
                        result.getString("derivation_id")), tenantId, snapshotId);
        storedFacts.forEach(stored -> {
            Map<String, String> values = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT name, value FROM kernel.evaluation_fact_value
                    WHERE tenant_id = ? AND snapshot_id = ? AND fact_position = ? ORDER BY name
                    """, (result, row) -> Map.entry(result.getString("name"), result.getString("value")),
                    tenantId, snapshotId, stored.position())
                    .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            evaluation.facts().add(new Fact(stored.type(), stored.derivationId(), values));
        });
        evaluation.actions().addAll(jdbc.query("""
                SELECT action_id, policy_id FROM kernel.evaluation_applicable_action
                WHERE tenant_id = ? AND snapshot_id = ? ORDER BY position
                """, (result, row) -> new ApplicableAction(
                        result.getString("action_id"), result.getString("policy_id")), tenantId, snapshotId));
        return evaluation;
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
                     semantic_pack_id, semantic_pack_checksum,
                     authorisation_bundle_id, authorisation_bundle_checksum,
                     authorised_at, decision_correlation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, tenantId, evaluation.id(), principal.type(), principal.id(),
                evaluation.subject().type(), evaluation.subject().id(), action.actionId(), evaluation.stateVersion(),
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

    private record StoredEvaluation(
            UUID id,
            Subject subject,
            long stateVersion,
            SemanticPackVersion semanticPack,
            Map<String, String> state,
            List<Fact> facts,
            List<ApplicableAction> actions) {}

    private record StoredFact(int position, String type, String derivationId) {}
}
