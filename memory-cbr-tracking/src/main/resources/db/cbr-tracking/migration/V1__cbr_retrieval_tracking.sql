CREATE TABLE cbr_retrieval_traces (
    trace_id    TEXT PRIMARY KEY,
    case_type   TEXT NOT NULL,
    tenant_id   TEXT NOT NULL,
    domain      TEXT NOT NULL,
    query_json  TEXT NOT NULL,
    results_json TEXT NOT NULL,
    timestamp   TEXT NOT NULL
);

CREATE INDEX idx_cbr_traces_lookup
    ON cbr_retrieval_traces(case_type, tenant_id, domain, timestamp);
