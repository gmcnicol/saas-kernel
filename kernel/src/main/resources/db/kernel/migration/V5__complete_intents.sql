ALTER TABLE kernel.intent
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN completed_at timestamptz,
    ADD CONSTRAINT intent_lease_consistent CHECK (
        (status = 'CLAIMED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND lease_token IS NULL AND lease_until IS NULL));

CREATE TABLE kernel.event (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    intent_id uuid NOT NULL,
    sequence integer NOT NULL CHECK (sequence > 0),
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    event_type text NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    payload_version integer NOT NULL CHECK (payload_version > 0),
    occurred_at timestamptz NOT NULL,
    resulting_state_version bigint NOT NULL CHECK (resulting_state_version > 0),
    UNIQUE (intent_id, sequence),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (intent_id, tenant_id) REFERENCES kernel.intent(id, tenant_id),
    FOREIGN KEY (tenant_id, subject_type, subject_id, resulting_state_version)
        REFERENCES kernel.projected_state_version(tenant_id, subject_type, subject_id, version)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE kernel.event_payload_value (
    event_id uuid NOT NULL,
    tenant_id text NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (event_id, name),
    FOREIGN KEY (event_id, tenant_id) REFERENCES kernel.event(id, tenant_id)
);

CREATE TABLE kernel.reevaluation_request (
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    expected_state_version bigint NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    due_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, subject_type, subject_id),
    FOREIGN KEY (tenant_id, subject_type, subject_id, expected_state_version)
        REFERENCES kernel.projected_state_version(tenant_id, subject_type, subject_id, version)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_intent_claimer') THEN
        CREATE ROLE kernel_intent_claimer NOLOGIN NOBYPASSRLS;
    END IF;
END $$;
ALTER ROLE kernel_intent_claimer NOLOGIN NOSUPERUSER NOBYPASSRLS;

GRANT USAGE ON SCHEMA kernel TO kernel_intent_claimer;
GRANT SELECT, UPDATE ON kernel.intent TO kernel_intent_claimer;
GRANT INSERT ON kernel.intent_audit TO kernel_intent_claimer;

CREATE POLICY intent_claiming ON kernel.intent FOR ALL TO kernel_intent_claimer
    USING (true) WITH CHECK (true);
CREATE POLICY intent_claim_audit ON kernel.intent_audit FOR INSERT TO kernel_intent_claimer
    WITH CHECK (true);

CREATE FUNCTION kernel.claim_due_intent(
    claim_token uuid,
    claimed_at timestamptz,
    claim_until timestamptz,
    audit_id uuid,
    audit_correlation uuid)
RETURNS TABLE (intent_id uuid, tenant_id text)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    selected_id uuid;
    selected_tenant text;
BEGIN
    SELECT candidate.id, candidate.tenant_id INTO selected_id, selected_tenant
    FROM kernel.intent candidate
    WHERE candidate.status = 'PENDING'
    ORDER BY candidate.accepted_at, candidate.id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF selected_id IS NULL THEN
        RETURN;
    END IF;

    UPDATE kernel.intent AS claimed
    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
        lease_token = claim_token, lease_until = claim_until
    WHERE claimed.id = selected_id AND claimed.tenant_id = selected_tenant AND claimed.status = 'PENDING';

    INSERT INTO kernel.intent_audit
        (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation)
    VALUES (audit_id, selected_tenant, selected_id, 1, 'PENDING', 'CLAIMED',
            claimed_at, 'worker lease claimed', audit_correlation);

    RETURN QUERY SELECT selected_id, selected_tenant;
END;
$$;
ALTER FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, uuid, uuid)
    OWNER TO kernel_intent_claimer;
REVOKE ALL ON FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, uuid, uuid)
    TO kernel_worker;

GRANT SELECT, INSERT, UPDATE ON kernel.intent, kernel.intent_audit TO kernel_worker;
GRANT SELECT, INSERT ON kernel.event, kernel.event_payload_value, kernel.reevaluation_request
    TO kernel_runtime, kernel_worker;
GRANT UPDATE ON kernel.reevaluation_request TO kernel_worker;

ALTER TABLE kernel.event ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.event
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.event_payload_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.event_payload_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.event_payload_value
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.reevaluation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.reevaluation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.reevaluation_request
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
