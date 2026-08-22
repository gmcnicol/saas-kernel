-- Coordinates one current semantic deployment per greenfield Application.
CREATE TABLE kernel.application_semantic_deployment (
    application_id text PRIMARY KEY,
    semantic_pack_checksum character(64) NOT NULL
        CHECK (semantic_pack_checksum ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE kernel.application_semantic_deployment IS
    'Global operational coordination only; contains no tenant or workflow data.';

REVOKE ALL ON kernel.application_semantic_deployment FROM PUBLIC;
GRANT SELECT ON kernel.application_semantic_deployment TO kernel_runtime;

CREATE FUNCTION kernel.set_application_semantic_deployment(
    deployment_application_id text,
    deployment_checksum text)
RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_locks
        WHERE locktype = 'advisory'
          AND pid = pg_backend_pid()
          AND mode = 'ExclusiveLock'
          AND granted
          AND classid = ((hashtextextended(deployment_application_id, 0) >> 32)
                         & 4294967295::bigint)::oid
          AND objid = (hashtextextended(deployment_application_id, 0)
                       & 4294967295::bigint)::oid
          AND objsubid = 1
    ) THEN
        RAISE insufficient_privilege USING MESSAGE = 'Semantic deployment update requires exclusive lock';
    END IF;

    INSERT INTO kernel.application_semantic_deployment(application_id, semantic_pack_checksum)
    VALUES (deployment_application_id, deployment_checksum)
    ON CONFLICT (application_id) DO UPDATE
    SET semantic_pack_checksum = EXCLUDED.semantic_pack_checksum;
END;
$$;

REVOKE ALL ON FUNCTION kernel.set_application_semantic_deployment(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.set_application_semantic_deployment(text, text) TO kernel_runtime;
