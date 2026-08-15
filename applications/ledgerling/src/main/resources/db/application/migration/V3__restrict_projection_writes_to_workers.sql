REVOKE INSERT, UPDATE, DELETE ON ledger_filing_projection FROM kernel_runtime;
GRANT SELECT, INSERT, UPDATE ON ledger_filing_projection TO kernel_worker;
