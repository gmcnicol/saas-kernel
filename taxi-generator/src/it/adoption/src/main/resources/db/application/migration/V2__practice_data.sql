CREATE TABLE client_file (
  tenant_id text NOT NULL, id text NOT NULL, practice text NOT NULL, assigned_to text NOT NULL, reference varchar(64) NOT NULL, notes varchar(512),
  PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, practice, reference)
);
-- Per-principal authorised-view revision, NOT a global hidden-row change counter.
-- Writers must atomically update affected views on visible value/assignment/policy changes.
CREATE TABLE practice_view (
  tenant_id text NOT NULL, practice text NOT NULL, principal_type text NOT NULL, principal_id text NOT NULL,
  revision text NOT NULL, policy_checksum text NOT NULL, authority_revision text NOT NULL,
  PRIMARY KEY (tenant_id, practice, principal_type, principal_id)
);
ALTER TABLE client_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON client_file USING (tenant_id = current_setting('kernel.tenant_id', true));
ALTER TABLE practice_view ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_view FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON practice_view USING (tenant_id = current_setting('kernel.tenant_id', true));
GRANT SELECT ON client_file, practice_view TO kernel_runtime;
