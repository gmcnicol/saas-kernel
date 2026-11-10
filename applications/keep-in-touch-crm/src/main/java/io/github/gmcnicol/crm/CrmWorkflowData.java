package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Interaction notes live once, in the interaction row, before and after execution. */
@Component
final class CrmWorkflowData implements ApplicationWorkflowData {
    private final JdbcTemplate jdbc;

    CrmWorkflowData(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public <I, P> Optional<TypedProjectedState<I, P>> readState(
            String tenant, TypedSubject<I> subject, ProjectionType<I, P> type) {
        if (type != FollowUpProjection.TYPE) throw new IllegalArgumentException("Unknown CRM Projection");
        return jdbc.query("""
                SELECT state_version, next_contact_due_at, open_follow_up_id IS NULL AS complete
                FROM crm_contact_engagement_projection WHERE tenant_id = ? AND contact_id = ?
                """, (row, n) -> new TypedProjectedState<>(tenant, subject, row.getLong("state_version"), type,
                        type.javaType().cast(new FollowUpProjection(new ContactId(subject.externalId()),
                                row.getTimestamp("next_contact_due_at").toInstant(), row.getBoolean("complete")))),
                tenant, subject.externalId()).stream().findFirst();
    }

    @Override
    public <C> void saveCandidate(String tenant, UUID intent, Subject subject, TypedCandidatePayload<C> payload) {
        if (payload.actionType() != TypedCrmActions.RECORD_INTERACTION) throw new IllegalArgumentException("Unknown CRM Action");
        var candidate = RecordInteractionCandidateV1.class.cast(payload.value());
        jdbc.update("INSERT INTO crm_interaction (tenant_id, intent_id, contact_id, notes) VALUES (?, ?, ?, ?)",
                tenant, intent, subject.id(), candidate.note());
    }

    @Override
    public <C> C readCandidate(String tenant, UUID intent, ActionType<?, C, ?> action) {
        if (action != TypedCrmActions.RECORD_INTERACTION) throw new IllegalArgumentException("Unknown CRM Action");
        return jdbc.queryForObject("SELECT notes FROM crm_interaction WHERE tenant_id = ? AND intent_id = ? AND recorded_at IS NULL",
                (row, n) -> action.candidateType().javaType().cast(new RecordInteractionCandidateV1(row.getString(1))), tenant, intent);
    }

    @Override
    public void discardCandidate(String tenant, UUID intent) {
        jdbc.update("DELETE FROM crm_interaction WHERE tenant_id = ? AND intent_id = ? AND recorded_at IS NULL", tenant, intent);
    }
}
