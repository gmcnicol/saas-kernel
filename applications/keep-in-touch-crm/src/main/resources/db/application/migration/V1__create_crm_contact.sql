DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM kernel.flyway_kernel_schema_history
        WHERE version = '1' AND success
    ) THEN
        RAISE EXCEPTION 'Kernel migrations must run first';
    END IF;
END $$;

CREATE TABLE crm_contact (
    tenant_id text NOT NULL,
    id uuid NOT NULL,
    display_name text NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

GRANT SELECT ON crm_contact TO kernel_runtime;
GRANT SELECT, INSERT ON crm_contact TO kernel_worker;

ALTER TABLE crm_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON crm_contact
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
