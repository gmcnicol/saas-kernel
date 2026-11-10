SET LOCAL row_security = off;
ALTER TABLE ledger_filing_projection ADD COLUMN state_version bigint NOT NULL DEFAULT 1 CHECK (state_version > 0);
CREATE TABLE ledger_filing_change (
    tenant_id text NOT NULL,
    intent_id uuid NOT NULL,
    filing_id text NOT NULL,
    change_kind text NOT NULL CHECK (change_kind IN ('RECORDS_RECEIVED', 'PREPARATION_STARTED')),
    received_at text,
    confirmed boolean,
    CHECK ((change_kind = 'RECORDS_RECEIVED' AND received_at IS NOT NULL AND confirmed IS NULL)
        OR (change_kind = 'PREPARATION_STARTED' AND received_at IS NULL AND confirmed IS NOT NULL)),
    applied boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, intent_id),
    FOREIGN KEY (tenant_id, filing_id) REFERENCES ledger_filing_projection(tenant_id, filing_id) ON DELETE CASCADE
);
INSERT INTO ledger_filing_change (tenant_id, intent_id, filing_id, change_kind, received_at, confirmed, applied)
SELECT intent.tenant_id, intent.id, intent.subject_id,
       CASE WHEN intent.action_id = 'io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived'
            THEN 'RECORDS_RECEIVED' ELSE 'PREPARATION_STARTED' END,
       intent.payload_content::jsonb ->> 'receivedAt',
       (intent.payload_content::jsonb ->> 'confirmed')::boolean, true
FROM kernel.typed_intent intent JOIN ledger_filing_projection filing
  ON filing.tenant_id = intent.tenant_id AND filing.filing_id = intent.subject_id
WHERE intent.status = 'SUCCEEDED' AND intent.action_id IN
    ('io.github.gmcnicol.ledgerling.LedgerlingActions.recordRecordsReceived',
     'io.github.gmcnicol.ledgerling.LedgerlingActions.startPreparation');
UPDATE ledger_filing_projection filing SET state_version = coalesce(
    (SELECT max(state.state_version) FROM kernel.typed_projected_state state
     WHERE state.tenant_id = filing.tenant_id AND state.subject_id = filing.filing_id
       AND state.projection_type = 'io.github.gmcnicol.ledgerling.FilingProjection'), 1);
GRANT SELECT, INSERT ON ledger_filing_change TO kernel_runtime;
GRANT SELECT, UPDATE, DELETE ON ledger_filing_change TO kernel_worker;
ALTER TABLE ledger_filing_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_filing_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_filing_change
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
