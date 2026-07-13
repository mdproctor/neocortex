CREATE TABLE cbr_case (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(255)    NOT NULL,
    domain      VARCHAR(255)    NOT NULL,
    case_type   VARCHAR(255)    NOT NULL,
    cbr_type    VARCHAR(50)     NOT NULL DEFAULT 'plan',
    entity_id   VARCHAR(255)    NOT NULL,
    case_id     VARCHAR(255),
    problem     TEXT            NOT NULL,
    solution    TEXT            NOT NULL,
    outcome     TEXT,
    confidence  DOUBLE PRECISION,
    features    TEXT            NOT NULL DEFAULT '{}',
    plan_traces TEXT,
    stored_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT cbr_case_pk PRIMARY KEY (id)
);

CREATE INDEX cbr_case_lookup_idx ON cbr_case (tenant_id, domain, case_type);
CREATE INDEX cbr_case_entity_idx ON cbr_case (entity_id, tenant_id);
CREATE INDEX cbr_case_stored_at_idx ON cbr_case (stored_at);
