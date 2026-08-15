ALTER TABLE kernel.intent
    ADD COLUMN next_attempt_at timestamptz,
    DROP CONSTRAINT intent_lease_consistent,
    ADD CONSTRAINT intent_work_state_consistent CHECK (
        (status = 'CLAIMED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND next_attempt_at IS NULL)
        OR (status = 'RETRY_WAIT' AND lease_token IS NULL AND lease_until IS NULL AND next_attempt_at IS NOT NULL)
        OR (status NOT IN ('CLAIMED', 'RETRY_WAIT')
            AND lease_token IS NULL AND lease_until IS NULL AND next_attempt_at IS NULL));

ALTER TABLE kernel.intent DROP CONSTRAINT intent_failure_reason_check;
ALTER TABLE kernel.intent ADD CONSTRAINT intent_failure_reason_check CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'STATE_OR_SEMANTIC_STALE', 'NOT_APPLICABLE', 'AUTHORISATION_DENIED',
        'DETERMINISTIC_FAILURE', 'TRANSIENT_ATTEMPTS_EXHAUSTED'));

ALTER TABLE kernel.intent_audit DROP CONSTRAINT intent_audit_failure_reason;
ALTER TABLE kernel.intent_audit ADD CONSTRAINT intent_audit_failure_reason CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'STATE_OR_SEMANTIC_STALE', 'NOT_APPLICABLE', 'AUTHORISATION_DENIED',
        'DETERMINISTIC_FAILURE', 'TRANSIENT_ATTEMPTS_EXHAUSTED'));

GRANT SELECT ON kernel.intent_audit TO kernel_intent_claimer;
CREATE POLICY intent_claim_audit_read ON kernel.intent_audit FOR SELECT TO kernel_intent_claimer
    USING (true);

DROP FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, uuid, uuid);

CREATE FUNCTION kernel.claim_due_intent(
    claim_token uuid,
    due_at timestamptz,
    claimed_at timestamptz,
    claim_until timestamptz,
    audit_id uuid,
    audit_correlation uuid)
RETURNS TABLE (intent_id uuid, tenant_id text, previous_status text)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    selected_id uuid;
    selected_tenant text;
    selected_status text;
    audit_sequence integer;
BEGIN
    SELECT candidate.id, candidate.tenant_id, candidate.status
    INTO selected_id, selected_tenant, selected_status
    FROM kernel.intent candidate
    WHERE (candidate.status = 'PENDING' AND candidate.accepted_at <= due_at)
       OR (candidate.status = 'RETRY_WAIT' AND candidate.next_attempt_at <= due_at)
       OR (candidate.status = 'CLAIMED' AND candidate.lease_until < claimed_at)
    ORDER BY COALESCE(candidate.next_attempt_at, candidate.accepted_at), candidate.id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF selected_id IS NULL THEN
        RETURN;
    END IF;

    SELECT COALESCE(MAX(sequence), -1) + 1 INTO audit_sequence
    FROM kernel.intent_audit audit WHERE audit.intent_id = selected_id;

    UPDATE kernel.intent AS claimed
    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
        lease_token = claim_token, lease_until = claim_until, next_attempt_at = NULL
    WHERE claimed.id = selected_id AND claimed.tenant_id = selected_tenant;

    INSERT INTO kernel.intent_audit
        (id, tenant_id, intent_id, sequence, from_status, to_status, occurred_at, reason, correlation)
    VALUES (audit_id, selected_tenant, selected_id, audit_sequence, selected_status, 'CLAIMED',
            claimed_at, CASE WHEN selected_status = 'CLAIMED'
                THEN 'expired worker lease reclaimed' ELSE 'worker lease claimed' END, audit_correlation);

    RETURN QUERY SELECT selected_id, selected_tenant, selected_status;
END;
$$;
ALTER FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, timestamptz, uuid, uuid)
    OWNER TO kernel_intent_claimer;
REVOKE ALL ON FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, timestamptz, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.claim_due_intent(uuid, timestamptz, timestamptz, timestamptz, uuid, uuid)
    TO kernel_worker;

CREATE FUNCTION kernel.enforce_intent_transition() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;
    IF (OLD.status = 'PENDING' AND NEW.status = 'CLAIMED')
        OR (OLD.status = 'RETRY_WAIT' AND NEW.status = 'CLAIMED')
        OR (OLD.status = 'CLAIMED' AND NEW.status IN ('RETRY_WAIT', 'SUCCEEDED', 'STALE', 'FAILED')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid Intent transition: % -> %', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER enforce_intent_transition
    BEFORE UPDATE OF status ON kernel.intent
    FOR EACH ROW EXECUTE FUNCTION kernel.enforce_intent_transition();

CREATE FUNCTION kernel.enforce_event_sequence() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_sequence integer;
BEGIN
    SELECT COALESCE(MAX(sequence), 0) + 1 INTO expected_sequence
    FROM kernel.event WHERE intent_id = NEW.intent_id;
    IF NEW.sequence <> expected_sequence THEN
        RAISE EXCEPTION 'invalid Event sequence for Intent %: expected %, received %',
            NEW.intent_id, expected_sequence, NEW.sequence;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_event_sequence
    BEFORE INSERT ON kernel.event
    FOR EACH ROW EXECUTE FUNCTION kernel.enforce_event_sequence();
