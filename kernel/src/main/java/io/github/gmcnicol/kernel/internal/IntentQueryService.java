package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.IntentAuditEntry;
import io.github.gmcnicol.kernel.application.IntentAuditQuery;
import io.github.gmcnicol.kernel.application.IntentFailureReason;
import io.github.gmcnicol.kernel.application.IntentQuery;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.IntentView;
import io.github.gmcnicol.kernel.application.Subject;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

final class IntentQueryService {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    IntentQueryService(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<IntentView> intents(IntentQuery query) {
        return transactions.execute(status -> {
            TenantContext.assumeRuntimeRole(jdbc);
            TenantContext.useAfterRole(jdbc, query.tenantId());
            return jdbc.query("""
                    SELECT id, tenant_id, subject_type, subject_id, action_id, status, accepted_at,
                           attempt_count, failure_reason, prior_intent_id
                    FROM kernel.intent
                    WHERE tenant_id = ?
                      AND (?::text IS NULL OR status = ?::text)
                      AND (?::text IS NULL OR subject_type = ?::text)
                      AND (?::text IS NULL OR subject_id = ?::text)
                      AND (?::uuid IS NULL OR id = ?::uuid)
                      AND (?::timestamptz IS NULL OR accepted_at < ?::timestamptz)
                      AND (?::timestamptz IS NULL
                           OR (accepted_at, id) > (?::timestamptz, ?::uuid))
                    ORDER BY accepted_at, id
                    LIMIT ?
                    """, (result, row) -> new IntentView(
                            result.getObject("id", java.util.UUID.class),
                            result.getString("tenant_id"),
                            new Subject(result.getString("subject_type"), result.getString("subject_id")),
                            result.getString("action_id"),
                            IntentStatus.valueOf(result.getString("status")),
                            result.getTimestamp("accepted_at").toInstant(),
                            result.getInt("attempt_count"),
                            Optional.ofNullable(result.getString("failure_reason"))
                                    .map(IntentFailureReason::valueOf),
                            Optional.ofNullable(result.getObject("prior_intent_id", java.util.UUID.class))),
                    parameters(query));
        });
    }

    List<IntentAuditEntry> audit(IntentAuditQuery query) {
        return transactions.execute(status -> {
            TenantContext.assumeRuntimeRole(jdbc);
            TenantContext.useAfterRole(jdbc, query.tenantId());
            return jdbc.query("""
                    SELECT audit.id, audit.intent_id, audit.sequence, audit.from_status, audit.to_status,
                           audit.occurred_at, audit.reason, audit.failure_reason, audit.correlation
                    FROM kernel.intent_audit audit
                    JOIN kernel.intent intent ON intent.id = audit.intent_id AND intent.tenant_id = audit.tenant_id
                    WHERE intent.tenant_id = ? AND intent.id = ?
                      AND (?::integer IS NULL OR audit.sequence > ?::integer)
                    ORDER BY audit.sequence
                    LIMIT ?
                    """, (result, row) -> new IntentAuditEntry(
                            result.getObject("id", java.util.UUID.class),
                            result.getObject("intent_id", java.util.UUID.class),
                            result.getInt("sequence"),
                            Optional.ofNullable(result.getString("from_status")).map(IntentStatus::valueOf),
                            IntentStatus.valueOf(result.getString("to_status")),
                            result.getTimestamp("occurred_at").toInstant(),
                            result.getString("reason"),
                            Optional.ofNullable(result.getString("failure_reason"))
                                    .map(IntentFailureReason::valueOf),
                            result.getObject("correlation", java.util.UUID.class)),
                    query.tenantId(), query.intentId(),
                    query.afterSequence().isPresent() ? query.afterSequence().getAsInt() : null,
                    query.afterSequence().isPresent() ? query.afterSequence().getAsInt() : null,
                    query.limit());
        });
    }

    private static Object[] parameters(IntentQuery query) {
        String status = query.status().map(IntentStatus::name).orElse(null);
        String subjectType = query.subject().map(Subject::type).orElse(null);
        String subjectId = query.subject().map(Subject::id).orElse(null);
        java.util.UUID intentId = query.intentId().orElse(null);
        Timestamp acceptedBefore = query.acceptedBefore().map(Timestamp::from).orElse(null);
        Timestamp cursorTime = query.after().map(IntentQuery.Cursor::acceptedAt).map(Timestamp::from).orElse(null);
        java.util.UUID cursorId = query.after().map(IntentQuery.Cursor::intentId).orElse(null);
        return new Object[] { query.tenantId(), status, status, subjectType, subjectType, subjectId, subjectId,
                intentId, intentId, acceptedBefore, acceptedBefore,
                cursorTime, cursorTime, cursorId, query.limit() };
    }

}
