CREATE TABLE kernel.action_offer (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    evaluation_snapshot_id uuid NOT NULL,
    principal_type text NOT NULL,
    principal_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    action_id text NOT NULL,
    state_version bigint NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    authorisation_bundle_id text NOT NULL,
    authorisation_bundle_checksum char(64) NOT NULL,
    authorised_at timestamptz NOT NULL,
    decision_correlation uuid NOT NULL,
    UNIQUE (tenant_id, evaluation_snapshot_id, principal_type, principal_id, action_id,
            authorisation_bundle_id, authorisation_bundle_checksum, authorised_at),
    FOREIGN KEY (evaluation_snapshot_id, tenant_id)
        REFERENCES kernel.evaluation_snapshot(id, tenant_id)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_runtime') THEN
        CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_worker') THEN
        CREATE ROLE kernel_worker NOLOGIN NOBYPASSRLS;
    END IF;
END $$;

GRANT USAGE ON SCHEMA kernel TO kernel_runtime, kernel_worker;
GRANT SELECT, INSERT ON kernel.projected_state_version, kernel.projected_state_value,
    kernel.evaluation_snapshot, kernel.evaluation_fact, kernel.evaluation_fact_value,
    kernel.evaluation_applicable_action, kernel.action_offer TO kernel_runtime, kernel_worker;

ALTER TABLE kernel.projected_state_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.projected_state_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.projected_state_version
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.projected_state_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.projected_state_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.projected_state_value
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.evaluation_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.evaluation_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.evaluation_snapshot
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.evaluation_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.evaluation_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.evaluation_fact
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.evaluation_fact_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.evaluation_fact_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.evaluation_fact_value
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.evaluation_applicable_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.evaluation_applicable_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.evaluation_applicable_action
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.action_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.action_offer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.action_offer
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
