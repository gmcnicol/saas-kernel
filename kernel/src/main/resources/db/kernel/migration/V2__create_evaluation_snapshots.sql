CREATE TABLE kernel.projected_state_version (
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    checksum char(64) NOT NULL,
    PRIMARY KEY (tenant_id, subject_type, subject_id, version),
    UNIQUE (tenant_id, subject_type, subject_id, version, checksum)
);

CREATE TABLE kernel.projected_state_value (
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    version bigint NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (tenant_id, subject_type, subject_id, version, name),
    FOREIGN KEY (tenant_id, subject_type, subject_id, version)
        REFERENCES kernel.projected_state_version(tenant_id, subject_type, subject_id, version)
);

CREATE TABLE kernel.evaluation_snapshot (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    state_version bigint NOT NULL CHECK (state_version >= 0),
    state_checksum char(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    application_id text NOT NULL,
    application_version text NOT NULL,
    kernel_version text NOT NULL,
    semantic_pack_id text NOT NULL,
    semantic_pack_checksum char(64) NOT NULL,
    reevaluate_at timestamptz,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, subject_type, subject_id, state_version, state_checksum, evaluated_at,
            application_id, application_version, kernel_version, semantic_pack_id, semantic_pack_checksum),
    FOREIGN KEY (tenant_id, subject_type, subject_id, state_version, state_checksum)
        REFERENCES kernel.projected_state_version(tenant_id, subject_type, subject_id, version, checksum)
);

CREATE TABLE kernel.evaluation_fact (
    snapshot_id uuid NOT NULL,
    tenant_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    fact_type text NOT NULL,
    derivation_id text NOT NULL,
    PRIMARY KEY (snapshot_id, position),
    UNIQUE (snapshot_id, position, tenant_id),
    FOREIGN KEY (snapshot_id, tenant_id) REFERENCES kernel.evaluation_snapshot(id, tenant_id)
);

CREATE TABLE kernel.evaluation_fact_value (
    snapshot_id uuid NOT NULL,
    tenant_id text NOT NULL,
    fact_position integer NOT NULL,
    name text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (snapshot_id, fact_position, name),
    FOREIGN KEY (snapshot_id, fact_position, tenant_id)
        REFERENCES kernel.evaluation_fact(snapshot_id, position, tenant_id)
);

CREATE TABLE kernel.evaluation_applicable_action (
    snapshot_id uuid NOT NULL,
    tenant_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    action_id text NOT NULL,
    policy_id text NOT NULL,
    PRIMARY KEY (snapshot_id, position),
    FOREIGN KEY (snapshot_id, tenant_id) REFERENCES kernel.evaluation_snapshot(id, tenant_id)
);
