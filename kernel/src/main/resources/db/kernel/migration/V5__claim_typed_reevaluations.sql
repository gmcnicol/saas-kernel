-- Claims scheduled typed reevaluations without a parallel legacy queue.
CREATE INDEX typed_reevaluation_due
    ON kernel.typed_evaluation_snapshot (reevaluate_at, tenant_id, subject_type, subject_id)
    WHERE reevaluate_at IS NOT NULL;

GRANT SELECT, UPDATE ON kernel.typed_evaluation_snapshot TO kernel_intent_claimer;
CREATE POLICY typed_reevaluation_claiming ON kernel.typed_evaluation_snapshot
    FOR ALL TO kernel_intent_claimer USING (true) WITH CHECK (true);

CREATE FUNCTION kernel.claim_due_typed_reevaluation(due_before timestamptz)
RETURNS TABLE (snapshot_id uuid, tenant_id text, due_at timestamptz)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    selected_id uuid;
    selected_tenant text;
    selected_due_at timestamptz;
BEGIN
    SELECT candidate.id, candidate.tenant_id, candidate.reevaluate_at
    INTO selected_id, selected_tenant, selected_due_at
    FROM kernel.typed_evaluation_snapshot candidate
    WHERE candidate.reevaluate_at <= due_before
    ORDER BY candidate.reevaluate_at, candidate.tenant_id,
             candidate.subject_type, candidate.subject_id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;
    IF selected_id IS NULL THEN RETURN; END IF;

    UPDATE kernel.typed_evaluation_snapshot
    SET reevaluate_at = NULL
    WHERE id = selected_id;
    RETURN QUERY SELECT selected_id, selected_tenant, selected_due_at;
END;
$$;

ALTER FUNCTION kernel.claim_due_typed_reevaluation(timestamptz)
    OWNER TO kernel_intent_claimer;
REVOKE ALL ON FUNCTION kernel.claim_due_typed_reevaluation(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.claim_due_typed_reevaluation(timestamptz)
    TO kernel_worker;
