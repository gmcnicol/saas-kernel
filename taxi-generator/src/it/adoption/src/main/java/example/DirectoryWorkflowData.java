package example;

import example.bindings.example.directory.*;
import io.github.gmcnicol.kernel.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** The review note lives once in an Application row, pending and applied. */
final class DirectoryWorkflowData implements ApplicationWorkflowData {
    private final JdbcTemplate jdbc;
    DirectoryWorkflowData(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public <I, P> Optional<TypedProjectedState<I, P>> readState(String tenant, TypedSubject<I> subject, ProjectionType<I, P> type) {
        if (type != Entry.TYPE) throw new IllegalArgumentException("Unknown workflow");
        return jdbc.query("SELECT * FROM directory_entry WHERE tenant_id = ? AND id = ?", (row, n) ->
                new TypedProjectedState<>(tenant, subject, row.getLong("version"), type,
                        type.javaType().cast(new Entry(new EntryId(row.getString("id")), row.getString("directory"),
                                row.getString("owner"), new Details(row.getString("name"), Optional.ofNullable(row.getString("private_note"))),
                                row.getBoolean("reviewed")))), tenant, subject.externalId()).stream().findFirst();
    }
    public <C> void saveCandidate(String tenant, UUID intent, Subject subject, TypedCandidatePayload<C> payload) {
        if (payload.actionType() != DirectoryActions.REVIEW) throw new IllegalArgumentException("Unknown action");
        jdbc.update("INSERT INTO review_note VALUES (?, ?, ?, ?, false)", tenant, intent, subject.id(), ((Review) payload.value()).note());
    }
    public <C> C readCandidate(String tenant, UUID intent, ActionType<?, C, ?> action) {
        if (action != DirectoryActions.REVIEW) throw new IllegalArgumentException("Unknown action");
        return jdbc.queryForObject("SELECT note FROM review_note WHERE tenant_id = ? AND intent_id = ? AND NOT applied",
                (row, n) -> action.candidateType().javaType().cast(new Review(row.getString(1))), tenant, intent);
    }
    public void discardCandidate(String tenant, UUID intent) {
        jdbc.update("DELETE FROM review_note WHERE tenant_id = ? AND intent_id = ? AND NOT applied", tenant, intent);
    }
}
