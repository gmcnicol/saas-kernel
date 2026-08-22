-- Canonical evidence uses generated Taxi Java Bindings only.
CREATE TABLE kernel.typed_projected_state (
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    state_version bigint NOT NULL CHECK (state_version >= 0),
    projection_type text NOT NULL,
    contract_version integer NOT NULL CHECK (contract_version >= 1),
    format_version integer NOT NULL CHECK (format_version >= 1),
    content text NOT NULL,
    checksum char(64) NOT NULL,
    PRIMARY KEY (
        tenant_id, subject_type, subject_id, state_version, projection_type, contract_version),
    UNIQUE (
        tenant_id, subject_type, subject_id, state_version, projection_type, contract_version, checksum)
);

CREATE TABLE kernel.typed_evaluation_snapshot (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    state_version bigint NOT NULL CHECK (state_version >= 0),
    projection_type text NOT NULL,
    projection_contract_version integer NOT NULL CHECK (projection_contract_version >= 1),
    state_checksum char(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    application_id text NOT NULL,
    application_version text NOT NULL,
    kernel_version text NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    reevaluate_at timestamptz,
    UNIQUE (id, tenant_id),
    UNIQUE (
        tenant_id, subject_type, subject_id, state_version, projection_type,
        projection_contract_version, state_checksum, evaluated_at, application_id,
        application_version, kernel_version, semantic_pack_id, semantic_pack_checksum),
    FOREIGN KEY (
        tenant_id, subject_type, subject_id, state_version, projection_type,
        projection_contract_version, state_checksum)
        REFERENCES kernel.typed_projected_state(
            tenant_id, subject_type, subject_id, state_version, projection_type,
            contract_version, checksum)
);

CREATE TABLE kernel.typed_evaluation_fact (
    snapshot_id uuid NOT NULL,
    tenant_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    fact_type text NOT NULL,
    contract_version integer NOT NULL CHECK (contract_version >= 1),
    format_version integer NOT NULL CHECK (format_version >= 1),
    derivation_id text NOT NULL,
    content text NOT NULL,
    checksum char(64) NOT NULL,
    PRIMARY KEY (snapshot_id, position),
    FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES kernel.typed_evaluation_snapshot(id, tenant_id)
);

CREATE TABLE kernel.typed_evaluation_applicable_action (
    snapshot_id uuid NOT NULL,
    tenant_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    action_id text NOT NULL,
    policy_id text NOT NULL,
    PRIMARY KEY (snapshot_id, position),
    FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES kernel.typed_evaluation_snapshot(id, tenant_id)
);

GRANT SELECT, INSERT ON kernel.typed_projected_state,
    kernel.typed_evaluation_snapshot, kernel.typed_evaluation_fact,
    kernel.typed_evaluation_applicable_action
    TO kernel_runtime, kernel_worker;

ALTER TABLE kernel.typed_projected_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_projected_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_projected_state
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_evaluation_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_evaluation_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_evaluation_snapshot
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_evaluation_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_evaluation_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_evaluation_fact
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));

ALTER TABLE kernel.typed_evaluation_applicable_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.typed_evaluation_applicable_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kernel.typed_evaluation_applicable_action
    USING (tenant_id = current_setting('kernel.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('kernel.tenant_id', true));
