CREATE TABLE ledger_filing_projection (
    tenant_id text NOT NULL,
    filing_id text NOT NULL,
    request_id text NOT NULL,
    client_reference text NOT NULL,
    filing_due_at timestamptz NOT NULL,
    records_outstanding boolean NOT NULL,
    preparation_started boolean NOT NULL,
    PRIMARY KEY (tenant_id, filing_id)
);

CREATE INDEX ledger_filing_outstanding
    ON ledger_filing_projection (tenant_id, filing_due_at, filing_id)
    WHERE records_outstanding;

GRANT SELECT, INSERT, UPDATE, DELETE ON ledger_filing_projection TO kernel_runtime;

ALTER TABLE ledger_filing_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_filing_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_filing_projection
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
