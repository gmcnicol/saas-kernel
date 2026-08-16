CREATE TABLE kernel.typed_action_offer (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    evaluation_snapshot_id uuid NOT NULL,
    principal_type text NOT NULL,
    principal_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    action_id text NOT NULL,
    policy_id text NOT NULL,
    state_version bigint NOT NULL,
    state_checksum char(64) NOT NULL,
    payload_type text NOT NULL,
    payload_contract_version integer NOT NULL CHECK (payload_contract_version >= 1),
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    authorisation_bundle_id text NOT NULL,
    authorisation_bundle_checksum char(64) NOT NULL,
    authorised_at timestamptz NOT NULL,
    decision_correlation uuid NOT NULL,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (evaluation_snapshot_id, tenant_id)
        REFERENCES kernel.typed_evaluation_snapshot(id, tenant_id)
);

CREATE TABLE kernel.typed_intent (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    action_offer_id uuid NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    action_id text NOT NULL,
    policy_id text NOT NULL,
    expected_state_version bigint NOT NULL,
    expected_state_checksum char(64) NOT NULL,
    projection_type text NOT NULL,
    projection_contract_version integer NOT NULL,
    payload_type text NOT NULL,
    payload_contract_version integer NOT NULL,
    payload_format_version integer NOT NULL,
    payload_content text NOT NULL,
    payload_checksum char(64) NOT NULL,
    request_checksum char(64) NOT NULL,
    accepted_at timestamptz NOT NULL,
    traceparent text,
    tracestate text,
    prior_intent_id uuid,
    status text NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'STALE', 'FAILED')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    completed_at timestamptz,
    failure_reason text,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (action_offer_id, tenant_id) REFERENCES kernel.typed_action_offer(id, tenant_id),
    FOREIGN KEY (prior_intent_id, tenant_id) REFERENCES kernel.typed_intent(id, tenant_id)
);

CREATE TABLE kernel.typed_intent_audit (
    intent_id uuid NOT NULL,
    tenant_id text NOT NULL,
    sequence integer NOT NULL CHECK (sequence >= 0),
    from_status text,
    to_status text NOT NULL,
    occurred_at timestamptz NOT NULL,
    reason text NOT NULL,
    PRIMARY KEY (intent_id, sequence),
    FOREIGN KEY (intent_id, tenant_id) REFERENCES kernel.typed_intent(id, tenant_id)
);

CREATE TABLE kernel.typed_event (
    id uuid NOT NULL UNIQUE,
    intent_id uuid NOT NULL,
    tenant_id text NOT NULL,
    sequence integer NOT NULL CHECK (sequence >= 1),
    event_type text NOT NULL,
    event_contract_version integer NOT NULL,
    event_format_version integer NOT NULL,
    event_content text NOT NULL,
    event_checksum char(64) NOT NULL,
    resulting_state_version bigint NOT NULL,
    projection_type text NOT NULL,
    projection_contract_version integer NOT NULL,
    projection_format_version integer NOT NULL,
    projection_content text NOT NULL,
    projection_checksum char(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (intent_id, sequence),
    FOREIGN KEY (intent_id, tenant_id) REFERENCES kernel.typed_intent(id, tenant_id)
);

GRANT SELECT ON kernel.typed_action_offer, kernel.typed_intent,
    kernel.typed_intent_audit, kernel.typed_event TO kernel_runtime, kernel_worker;
GRANT INSERT ON kernel.typed_action_offer, kernel.typed_intent, kernel.typed_intent_audit TO kernel_runtime;
GRANT INSERT, UPDATE ON kernel.typed_intent TO kernel_worker;
GRANT INSERT ON kernel.typed_intent_audit, kernel.typed_event TO kernel_worker;

ALTER TABLE kernel.typed_action_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_action_offer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_action_offer
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_intent ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_intent FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_intent
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_intent_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_intent_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_intent_audit
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_event
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

GRANT SELECT, UPDATE ON kernel.typed_intent TO kernel_intent_claimer;
GRANT SELECT, INSERT ON kernel.typed_intent_audit TO kernel_intent_claimer;
CREATE POLICY typed_intent_claiming ON kernel.typed_intent FOR ALL TO kernel_intent_claimer
    USING (true) WITH CHECK (true);
CREATE POLICY typed_intent_claim_audit ON kernel.typed_intent_audit FOR ALL TO kernel_intent_claimer
    USING (true) WITH CHECK (true);

CREATE POLICY typed_offer_resolution ON kernel.typed_action_offer
    FOR SELECT TO kernel_offer_resolver USING (true);
GRANT SELECT ON kernel.typed_action_offer TO kernel_offer_resolver;
CREATE FUNCTION kernel.resolve_typed_action_offer_tenant(offer_id uuid) RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog
    AS 'SELECT tenant_id FROM kernel.typed_action_offer WHERE id = offer_id';
ALTER FUNCTION kernel.resolve_typed_action_offer_tenant(uuid) OWNER TO kernel_offer_resolver;
REVOKE ALL ON FUNCTION kernel.resolve_typed_action_offer_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.resolve_typed_action_offer_tenant(uuid) TO kernel_runtime;

CREATE FUNCTION kernel.claim_due_typed_intent(
    claim_token uuid,
    due_at timestamptz,
    claimed_at timestamptz,
    claim_until timestamptz)
RETURNS TABLE (intent_id uuid, tenant_id text, previous_status text)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    selected_id uuid;
    selected_tenant text;
    audit_sequence integer;
    selected_previous_status text;
BEGIN
    SELECT candidate.id, candidate.tenant_id, candidate.status
    INTO selected_id, selected_tenant, selected_previous_status
    FROM kernel.typed_intent candidate
    WHERE (candidate.status = 'PENDING' AND candidate.accepted_at <= due_at)
       OR (candidate.status = 'RETRY_WAIT' AND candidate.next_attempt_at <= due_at)
       OR (candidate.status = 'CLAIMED' AND candidate.lease_until < claimed_at)
    ORDER BY COALESCE(candidate.next_attempt_at, candidate.accepted_at), candidate.id
    FOR UPDATE SKIP LOCKED LIMIT 1;
    IF selected_id IS NULL THEN RETURN; END IF;

    SELECT COALESCE(MAX(sequence), -1) + 1 INTO audit_sequence
    FROM kernel.typed_intent_audit audit WHERE audit.intent_id = selected_id;
    UPDATE kernel.typed_intent SET status = 'CLAIMED', attempt_count = attempt_count + 1,
        lease_token = claim_token, lease_until = claim_until, next_attempt_at = NULL
    WHERE id = selected_id;
    INSERT INTO kernel.typed_intent_audit
        (intent_id, tenant_id, sequence, from_status, to_status, occurred_at, reason)
    VALUES (selected_id, selected_tenant, audit_sequence, selected_previous_status, 'CLAIMED', claimed_at,
        CASE WHEN selected_previous_status = 'CLAIMED'
            THEN 'expired worker lease reclaimed' ELSE 'worker lease claimed' END);
    RETURN QUERY SELECT selected_id, selected_tenant, selected_previous_status;
END;
$$;
ALTER FUNCTION kernel.claim_due_typed_intent(uuid, timestamptz, timestamptz, timestamptz)
    OWNER TO kernel_intent_claimer;
REVOKE ALL ON FUNCTION kernel.claim_due_typed_intent(uuid, timestamptz, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.claim_due_typed_intent(uuid, timestamptz, timestamptz, timestamptz)
    TO kernel_worker;
