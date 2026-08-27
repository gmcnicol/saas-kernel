CREATE TABLE crm_contact_engagement_projection (
    tenant_id text NOT NULL,
    contact_id text NOT NULL,
    display_name text NOT NULL,
    last_interaction_at timestamptz,
    next_contact_due_at timestamptz NOT NULL,
    open_follow_up_id uuid,
    state_version bigint NOT NULL DEFAULT 1 CHECK (state_version > 0),
    PRIMARY KEY (tenant_id, contact_id)
);

CREATE INDEX crm_contact_engagement_due
    ON crm_contact_engagement_projection (tenant_id, next_contact_due_at, contact_id)
    WHERE open_follow_up_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON crm_contact_engagement_projection TO kernel_runtime;

ALTER TABLE crm_contact_engagement_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_contact_engagement_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON crm_contact_engagement_projection
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
