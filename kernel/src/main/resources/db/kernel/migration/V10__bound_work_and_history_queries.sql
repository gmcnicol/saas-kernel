CREATE INDEX intent_pending_due
    ON kernel.intent (accepted_at, id) WHERE status = 'PENDING';
CREATE INDEX intent_retry_due
    ON kernel.intent (next_attempt_at, id) WHERE status = 'RETRY_WAIT';
CREATE INDEX intent_claimed_expiry
    ON kernel.intent (lease_until, id) WHERE status = 'CLAIMED';
CREATE INDEX intent_tenant_history
    ON kernel.intent (tenant_id, accepted_at, id);

CREATE INDEX reevaluation_unleased_due
    ON kernel.reevaluation_request (due_at, tenant_id, subject_type, subject_id)
    WHERE lease_token IS NULL;
CREATE INDEX reevaluation_lease_expiry
    ON kernel.reevaluation_request (lease_until, due_at, tenant_id, subject_type, subject_id)
    WHERE lease_token IS NOT NULL;

CREATE OR REPLACE FUNCTION kernel.enforce_intent_transition() RETURNS trigger
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
    RAISE EXCEPTION 'invalid Intent transition: % -> %', OLD.status, NEW.status
        USING ERRCODE = 'K0001';
END;
$$;

CREATE OR REPLACE FUNCTION kernel.enforce_event_sequence() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_sequence integer;
BEGIN
    SELECT COALESCE(MAX(sequence), 0) + 1 INTO expected_sequence
    FROM kernel.event WHERE intent_id = NEW.intent_id;
    IF NEW.sequence <> expected_sequence THEN
        RAISE EXCEPTION 'invalid Event sequence for Intent %: expected %, received %',
            NEW.intent_id, expected_sequence, NEW.sequence
            USING ERRCODE = 'K0002';
    END IF;
    RETURN NEW;
END;
$$;
