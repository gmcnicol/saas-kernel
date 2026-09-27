SET LOCAL row_security = off;
CREATE TABLE crm_interaction (
    tenant_id text NOT NULL,
    intent_id uuid NOT NULL,
    contact_id text NOT NULL,
    notes text NOT NULL,
    recorded_at timestamptz,
    PRIMARY KEY (tenant_id, intent_id),
    FOREIGN KEY (tenant_id, contact_id)
        REFERENCES crm_contact_engagement_projection(tenant_id, contact_id) ON DELETE CASCADE
);
-- Recover the one business record, not a second historical payload archive.
-- Deleted Contacts are deliberately excluded.
INSERT INTO crm_interaction (tenant_id, intent_id, contact_id, notes, recorded_at)
SELECT intent.tenant_id, intent.id, intent.subject_id, intent.payload_content::jsonb ->> 'note', intent.completed_at
FROM kernel.typed_intent intent
JOIN crm_contact_engagement_projection contact
  ON contact.tenant_id = intent.tenant_id AND contact.contact_id = intent.subject_id
WHERE intent.status = 'SUCCEEDED' AND intent.action_id = 'io.github.gmcnicol.crm.TypedCrmActions.recordInteraction';
GRANT SELECT, INSERT ON crm_interaction TO kernel_runtime;
GRANT SELECT, UPDATE, DELETE ON crm_interaction TO kernel_worker;
ALTER TABLE crm_interaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_interaction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON crm_interaction
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
