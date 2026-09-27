package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.*;
import io.github.gmcnicol.kernel.application.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class LedgerlingWorkflowData implements ApplicationWorkflowData {
    private final JdbcTemplate jdbc;

    LedgerlingWorkflowData(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public <I, P> Optional<TypedProjectedState<I, P>> readState(
            String tenant, TypedSubject<I> subject, ProjectionType<I, P> type) {
        if (type != FilingProjection.TYPE) throw new IllegalArgumentException("Unknown Filing Projection");
        return jdbc.query("""
                SELECT * FROM ledger_filing_projection WHERE tenant_id = ? AND filing_id = ?
                """, (row, n) -> new TypedProjectedState<>(tenant, subject, row.getLong("state_version"), type,
                        type.javaType().cast(new FilingProjection(new FilingId(subject.externalId()),
                                new DocumentRequestId(row.getString("request_id")),
                                new ClientReference(row.getString("client_reference")),
                                row.getTimestamp("filing_due_at").toInstant(), row.getBoolean("records_outstanding"),
                                row.getBoolean("preparation_started")))), tenant, subject.externalId()).stream().findFirst();
    }

    @Override
    public <C> void saveCandidate(String tenant, UUID intent, Subject subject, TypedCandidatePayload<C> payload) {
        String receivedAt = null;
        Boolean confirmed = null;
        String kind;
        if (payload.actionType() == LedgerlingActions.RECORD_RECORDS_RECEIVED) {
            receivedAt = RecordRecordsReceivedCandidateV1.class.cast(payload.value()).receivedAt().toString();
            kind = "RECORDS_RECEIVED";
        } else if (payload.actionType() == LedgerlingActions.START_PREPARATION) {
            confirmed = StartPreparationCandidateV1.class.cast(payload.value()).confirmed();
            kind = "PREPARATION_STARTED";
        } else throw new IllegalArgumentException("Unknown Filing Action");
        jdbc.update("""
                INSERT INTO ledger_filing_change (tenant_id, intent_id, filing_id, change_kind, received_at, confirmed)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenant, intent, subject.id(), kind, receivedAt, confirmed);
    }

    @Override
    public <C> C readCandidate(String tenant, UUID intent, ActionType<?, C, ?> action) {
        return jdbc.queryForObject("""
                SELECT change_kind, received_at, confirmed FROM ledger_filing_change WHERE tenant_id = ? AND intent_id = ? AND NOT applied
                """, (row, n) -> {
                    Object value;
                    if (action == LedgerlingActions.RECORD_RECORDS_RECEIVED && row.getString("change_kind").equals("RECORDS_RECEIVED")) {
                        value = new RecordRecordsReceivedCandidateV1(java.time.Instant.parse(row.getString("received_at")));
                    } else if (action == LedgerlingActions.START_PREPARATION && row.getString("change_kind").equals("PREPARATION_STARTED")) {
                        value = new StartPreparationCandidateV1(row.getBoolean("confirmed"));
                    } else throw new IllegalArgumentException("Filing Action does not match pending change");
                    return action.candidateType().javaType().cast(value);
                }, tenant, intent);
    }

    @Override
    public void discardCandidate(String tenant, UUID intent) {
        jdbc.update("DELETE FROM ledger_filing_change WHERE tenant_id = ? AND intent_id = ? AND NOT applied", tenant, intent);
    }
}
