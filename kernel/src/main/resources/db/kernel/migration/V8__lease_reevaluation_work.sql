ALTER TABLE kernel.reevaluation_request
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD CONSTRAINT reevaluation_lease_consistent CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL));

GRANT SELECT, UPDATE ON kernel.reevaluation_request TO kernel_intent_claimer;
GRANT SELECT, INSERT, UPDATE, DELETE ON kernel.reevaluation_request TO kernel_runtime;
GRANT DELETE ON kernel.reevaluation_request TO kernel_worker;

CREATE POLICY reevaluation_claiming ON kernel.reevaluation_request
    FOR ALL TO kernel_intent_claimer USING (true) WITH CHECK (true);

CREATE FUNCTION kernel.claim_due_reevaluation(
    claim_token uuid,
    due_before timestamptz,
    claimed_at timestamptz,
    claim_until timestamptz)
RETURNS TABLE (
    tenant_id text,
    subject_type text,
    subject_id text,
    expected_state_version bigint,
    semantic_pack_id text,
    semantic_pack_checksum text)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    selected kernel.reevaluation_request%ROWTYPE;
BEGIN
    SELECT candidate.* INTO selected
    FROM kernel.reevaluation_request candidate
    WHERE (candidate.lease_token IS NULL AND candidate.due_at <= due_before)
       OR candidate.lease_until < claimed_at
    ORDER BY candidate.due_at, candidate.tenant_id, candidate.subject_type, candidate.subject_id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF selected.tenant_id IS NULL THEN
        RETURN;
    END IF;

    UPDATE kernel.reevaluation_request claimed
    SET lease_token = claim_token, lease_until = claim_until
    WHERE claimed.tenant_id = selected.tenant_id
      AND claimed.subject_type = selected.subject_type
      AND claimed.subject_id = selected.subject_id;

    RETURN QUERY SELECT selected.tenant_id, selected.subject_type, selected.subject_id,
        selected.expected_state_version, selected.semantic_pack_id,
        selected.semantic_pack_checksum::text;
END;
$$;

ALTER FUNCTION kernel.claim_due_reevaluation(uuid, timestamptz, timestamptz, timestamptz)
    OWNER TO kernel_intent_claimer;
REVOKE ALL ON FUNCTION kernel.claim_due_reevaluation(uuid, timestamptz, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.claim_due_reevaluation(uuid, timestamptz, timestamptz, timestamptz)
    TO kernel_worker;
