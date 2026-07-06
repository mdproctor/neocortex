CREATE TABLE retrieval_records (
    retrieval_id   TEXT PRIMARY KEY,
    query_text     TEXT NOT NULL,
    expanded_text  TEXT,
    tenant_id      TEXT NOT NULL,
    corpus_name    TEXT NOT NULL,
    max_results    INTEGER NOT NULL,
    timestamp      TEXT NOT NULL
);

CREATE TABLE retrieved_documents (
    retrieval_id        TEXT NOT NULL REFERENCES retrieval_records(retrieval_id),
    source_document_id  TEXT NOT NULL,
    relevance_score     REAL NOT NULL,
    PRIMARY KEY (retrieval_id, source_document_id)
);

CREATE TABLE retrieval_feedback (
    retrieval_id        TEXT NOT NULL REFERENCES retrieval_records(retrieval_id),
    source_document_id  TEXT NOT NULL,
    outcome             TEXT NOT NULL,
    timestamp           TEXT NOT NULL,
    PRIMARY KEY (retrieval_id, source_document_id)
);

CREATE INDEX idx_records_corpus_ts
    ON retrieval_records(tenant_id, corpus_name, timestamp);

CREATE INDEX idx_feedback_retrieval
    ON retrieval_feedback(retrieval_id);

CREATE INDEX idx_feedback_ts
    ON retrieval_feedback(timestamp);
