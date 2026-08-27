package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    FollowUpProjection projection(String tenantId, String contactId) {
        return storedProjection(tenantId, contactId).projection();
    }

    StoredProjection storedProjection(String tenantId, String contactId) {
        if (tenantId == null || tenantId.isBlank() || contactId == null || contactId.isBlank()) {
            throw new IllegalArgumentException("Contact projection requires tenant and contact");
        }
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
            return jdbc.queryForObject("""
                    SELECT contact_id, next_contact_due_at, open_follow_up_id IS NULL AS follow_up_completed,
                           state_version
                    FROM crm_contact_engagement_projection
                    WHERE tenant_id = ? AND contact_id = ?
                    """, (result, row) -> new StoredProjection(result.getLong("state_version"),
                            new FollowUpProjection(new ContactId(result.getString("contact_id")),
                                    result.getTimestamp("next_contact_due_at").toInstant(),
                                    result.getBoolean("follow_up_completed"))), tenantId, contactId);
        });
    }

    String create(String tenantId, String displayName, Instant nextContactDueAt) {
        if (tenantId == null || tenantId.isBlank() || displayName == null || displayName.isBlank()
                || displayName.length() > 200 || nextContactDueAt == null) {
            throw new IllegalArgumentException("Contact requires tenant, name, and follow-up time");
        }
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_worker");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO crm_contact (tenant_id, id, display_name) VALUES (?, ?, ?)",
                    tenantId, id, displayName.strip());
            jdbc.update("""
                    INSERT INTO crm_contact_engagement_projection
                        (tenant_id, contact_id, display_name, next_contact_due_at, open_follow_up_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, id.toString(), displayName.strip(),
                    java.sql.Timestamp.from(nextContactDueAt), UUID.randomUUID());
            return id.toString();
        });
    }

    List<ContactSummary> all(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Tenant is required");
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
            return jdbc.query("""
                    SELECT contact_id, display_name, next_contact_due_at, open_follow_up_id IS NULL AS complete
                    FROM crm_contact_engagement_projection
                    WHERE tenant_id = ?
                    ORDER BY display_name, contact_id
                    """, (result, row) -> new ContactSummary(
                            result.getString("contact_id"), result.getString("display_name"),
                            result.getTimestamp("next_contact_due_at").toInstant(), result.getBoolean("complete")),
                    tenantId);
        });
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

    record ContactSummary(String contactId, String displayName, Instant dueAt, boolean complete) { }

    record StoredProjection(long version, FollowUpProjection projection) { }
}
