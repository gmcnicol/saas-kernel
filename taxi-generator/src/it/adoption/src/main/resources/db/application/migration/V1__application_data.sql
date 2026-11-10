CREATE TABLE directory_entry (
  tenant_id text NOT NULL, id text NOT NULL, directory text NOT NULL, owner text NOT NULL,
  name varchar(256) NOT NULL, private_note varchar(512), reviewed boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id, id)
);
CREATE TABLE review_note (
  tenant_id text NOT NULL, intent_id uuid NOT NULL, entry_id text NOT NULL, note varchar(1024) NOT NULL, applied boolean NOT NULL,
  PRIMARY KEY (tenant_id, intent_id), FOREIGN KEY (tenant_id, entry_id) REFERENCES directory_entry(tenant_id, id)
);
CREATE TABLE source_revision (tenant_id text NOT NULL, scope_id text NOT NULL, principal_id text NOT NULL, revision text NOT NULL, PRIMARY KEY (tenant_id, scope_id, principal_id));
CREATE TABLE authority_revision (
  tenant_id text NOT NULL, principal_type text NOT NULL, principal_id text NOT NULL,
  scope_type text NOT NULL, scope_id text NOT NULL, revision text NOT NULL,
  PRIMARY KEY (tenant_id, principal_type, principal_id, scope_type, scope_id)
);
CREATE TABLE access_grant (
  tenant_id text NOT NULL, principal_type text NOT NULL, principal_id text NOT NULL,
  scope_type text NOT NULL, scope_id text NOT NULL, source text NOT NULL, valid_until timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, principal_type, principal_id, scope_type, scope_id, source),
  FOREIGN KEY (tenant_id, principal_type, principal_id, scope_type, scope_id)
    REFERENCES authority_revision(tenant_id, principal_type, principal_id, scope_type, scope_id)
);
ALTER TABLE directory_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE directory_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON directory_entry USING (tenant_id = current_setting('kernel.tenant_id', true));
ALTER TABLE review_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_note FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON review_note USING (tenant_id = current_setting('kernel.tenant_id', true));
ALTER TABLE source_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON source_revision USING (tenant_id = current_setting('kernel.tenant_id', true));
ALTER TABLE authority_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE authority_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON authority_revision USING (tenant_id = current_setting('kernel.tenant_id', true));
ALTER TABLE access_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant ON access_grant USING (tenant_id = current_setting('kernel.tenant_id', true));
GRANT SELECT ON directory_entry, source_revision, authority_revision, access_grant TO kernel_runtime, kernel_worker;
GRANT INSERT, SELECT ON review_note TO kernel_runtime;
GRANT SELECT, UPDATE, DELETE ON review_note TO kernel_worker;
GRANT UPDATE ON directory_entry, source_revision TO kernel_worker;
