package io.github.gmcnicol.crm;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hot Application read path over the CRM-owned relational projection. */
@Component
final class CrmContactQueries {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    CrmContactQueries(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    List<ContactDue> dueBy(String tenantId, Instant dueBy, Optional<ContactDue> after, int limit) {
        if (tenantId == null || tenantId.isBlank() || dueBy == null || after == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Due-contact query requires tenant, time, cursor, and limit 1..100");
        }
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
            java.sql.Timestamp cursorTime = after.map(ContactDue::dueAt).map(java.sql.Timestamp::from).orElse(null);
            String cursorId = after.map(ContactDue::contactId).orElse(null);
            return jdbc.query("""
                    SELECT contact_id, display_name, next_contact_due_at
                    FROM crm_contact_engagement_projection
                    WHERE tenant_id = ? AND open_follow_up_id IS NOT NULL AND next_contact_due_at <= ?
                      AND (CAST(? AS timestamptz) IS NULL
                           OR (next_contact_due_at, contact_id) > (CAST(? AS timestamptz), ?))
                    ORDER BY next_contact_due_at, contact_id
                    LIMIT ?
                    """, (result, row) -> new ContactDue(
                            result.getString("contact_id"), result.getString("display_name"),
                            result.getTimestamp("next_contact_due_at").toInstant()),
                    tenantId, java.sql.Timestamp.from(dueBy), cursorTime, cursorTime, cursorId, limit);
        });
    }

    record ContactDue(String contactId, String displayName, Instant dueAt) { }
}
