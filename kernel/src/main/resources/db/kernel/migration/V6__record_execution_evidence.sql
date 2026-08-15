ALTER TABLE kernel.intent_audit
    ADD COLUMN failure_reason text,
    ADD COLUMN evidence_state_version bigint,
    ADD COLUMN evidence_state_checksum char(64),
    ADD COLUMN semantic_pack_id text,
    ADD COLUMN semantic_pack_checksum char(64),
    ADD COLUMN applicability_policy_id text,
    ADD COLUMN applicability_result boolean,
    ADD COLUMN authorisation_bundle_id text,
    ADD COLUMN authorisation_bundle_checksum char(64),
    ADD COLUMN authorisation_allowed boolean,
    ADD COLUMN authorisation_correlation uuid;

ALTER TABLE kernel.intent_audit ADD CONSTRAINT intent_audit_failure_reason CHECK (
    failure_reason IS NULL OR failure_reason IN (
        'STATE_OR_SEMANTIC_STALE', 'NOT_APPLICABLE', 'AUTHORISATION_DENIED'));
