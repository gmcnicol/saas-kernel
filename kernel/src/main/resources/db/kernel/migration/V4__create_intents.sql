CREATE TABLE kernel.intent (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    action_offer_id uuid NOT NULL,
    evaluation_snapshot_id uuid NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    action_id text NOT NULL,
    applicability_policy_id text NOT NULL,
    principal_type text NOT NULL,
    principal_id text NOT NULL,
    expected_state_version bigint NOT NULL,
    expected_state_checksum char(64) NOT NULL,
    application_id text NOT NULL,
    application_version text NOT NULL,
    kernel_version text NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    authorisation_bundle_id text NOT NULL,
    authorisation_bundle_checksum char(64) NOT NULL,
    authorised_at timestamptz NOT NULL,
    authorisation_correlation uuid NOT NULL,
    payload_type text NOT NULL,
    payload_version integer NOT NULL CHECK (payload_version > 0),
    request_checksum char(64) NOT NULL,
    envelope_checksum char(64) NOT NULL,
    accepted_at timestamptz NOT NULL,
    traceparent text,
    tracestate text,
    prior_intent_id uuid,
    status text NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'STALE', 'FAILED')),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (action_offer_id, tenant_id) REFERENCES kernel.action_offer(id, tenant_id),
    FOREIGN KEY (evaluation_snapshot_id, tenant_id) REFERENCES kernel.evaluation_snapshot(id, tenant_id),
    FOREIGN KEY (prior_intent_id, tenant_id) REFERENCES kernel.intent(id, tenant_id)
);

CREATE TABLE kernel.intent_payload_value (
    intent_id uuid NOT NULL,
    tenant_id text NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (intent_id, name),
    FOREIGN KEY (intent_id, tenant_id) REFERENCES kernel.intent(id, tenant_id)
);

CREATE TABLE kernel.intent_audit (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    intent_id uuid NOT NULL,
    sequence integer NOT NULL CHECK (sequence >= 0),
    from_status text,
    to_status text NOT NULL,
    occurred_at timestamptz NOT NULL,
    reason text NOT NULL,
    correlation uuid NOT NULL,
    UNIQUE (intent_id, sequence),
    FOREIGN KEY (intent_id, tenant_id) REFERENCES kernel.intent(id, tenant_id)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kernel_offer_resolver') THEN
        CREATE ROLE kernel_offer_resolver NOLOGIN NOBYPASSRLS;
    END IF;
END $$;
ALTER ROLE kernel_offer_resolver NOLOGIN NOSUPERUSER NOBYPASSRLS;

GRANT USAGE ON SCHEMA kernel TO kernel_offer_resolver;
GRANT SELECT ON kernel.action_offer TO kernel_offer_resolver;
CREATE POLICY offer_resolution ON kernel.action_offer FOR SELECT TO kernel_offer_resolver USING (true);

CREATE FUNCTION kernel.resolve_action_offer_tenant(offer_id uuid) RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog
    AS 'SELECT tenant_id FROM kernel.action_offer WHERE id = offer_id';
ALTER FUNCTION kernel.resolve_action_offer_tenant(uuid) OWNER TO kernel_offer_resolver;
REVOKE ALL ON FUNCTION kernel.resolve_action_offer_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.resolve_action_offer_tenant(uuid) TO kernel_runtime;

GRANT SELECT, INSERT ON kernel.intent, kernel.intent_payload_value, kernel.intent_audit
    TO kernel_runtime, kernel_worker;

ALTER TABLE kernel.intent ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.intent FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.intent
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.intent_payload_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.intent_payload_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.intent_payload_value
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.intent_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.intent_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.intent_audit
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
