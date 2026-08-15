package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicableAction;
import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.Fact;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.Subject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class EvaluationStore {

    private final JdbcTemplate jdbc;

    EvaluationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    StoredEvaluation load(String tenantId, UUID snapshotId) {
        List<StoredEvaluation> matches = jdbc.query("""
                SELECT subject_type, subject_id, state_version, state_checksum, evaluated_at,
                       application_id, application_version, kernel_version,
                       semantic_pack_id, semantic_pack_checksum
                FROM kernel.evaluation_snapshot WHERE tenant_id = ? AND id = ?
                """, (result, row) -> new StoredEvaluation(
                        snapshotId,
                        new Subject(result.getString("subject_type"), result.getString("subject_id")),
                        result.getLong("state_version"),
                        result.getString("state_checksum"),
                        result.getTimestamp("evaluated_at").toInstant(),
                        result.getString("application_id"),
                        result.getString("application_version"),
                        result.getString("kernel_version"),
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

    private record StoredFact(int position, String type, String derivationId) {}
}
