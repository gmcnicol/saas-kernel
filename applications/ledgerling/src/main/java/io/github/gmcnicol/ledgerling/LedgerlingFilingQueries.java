package io.github.gmcnicol.ledgerling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hot Application read path over the Ledgerling-owned relational projection. */
@Component
final class LedgerlingFilingQueries {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    LedgerlingFilingQueries(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    List<FilingOutstanding> outstandingBy(
            String tenantId, Instant dueBy, Optional<FilingOutstanding> after, int limit) {
        if (tenantId == null || tenantId.isBlank() || dueBy == null || after == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Outstanding-filing query requires tenant, time, cursor, and limit 1..100");
        }
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE kernel_runtime");
            jdbc.queryForObject("SELECT set_config('kernel.tenant_id', ?, true)", String.class, tenantId);
            java.sql.Timestamp cursorTime = after.map(FilingOutstanding::dueAt)
                    .map(java.sql.Timestamp::from).orElse(null);
            String cursorId = after.map(FilingOutstanding::filingId).orElse(null);
            return jdbc.query("""
                    SELECT filing_id, client_reference, filing_due_at
                    FROM ledger_filing_projection
                    WHERE tenant_id = ? AND records_outstanding AND filing_due_at <= ?
                      AND (CAST(? AS timestamptz) IS NULL
                           OR (filing_due_at, filing_id) > (CAST(? AS timestamptz), ?))
                    ORDER BY filing_due_at, filing_id
                    LIMIT ?
                    """, (result, row) -> new FilingOutstanding(
                            result.getString("filing_id"), result.getString("client_reference"),
                            result.getTimestamp("filing_due_at").toInstant()),
                    tenantId, java.sql.Timestamp.from(dueBy), cursorTime, cursorTime, cursorId, limit);
        });
    }

    record FilingOutstanding(String filingId, String clientReference, Instant dueAt) { }
}
