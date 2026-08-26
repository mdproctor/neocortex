CREATE TABLE IF NOT EXISTS mindmap_node (
    node_id           TEXT NOT NULL,
    tenant_id         TEXT NOT NULL,
    name              TEXT NOT NULL,
    subgraph_id       TEXT NOT NULL,
    confidence_origin TEXT NOT NULL,
    confidence        REAL NOT NULL,
    provenance        TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    confirmed_at      TEXT NOT NULL,
    valid_from        TEXT,
    valid_until       TEXT,
    traits            TEXT NOT NULL DEFAULT '[]',
    refs              TEXT NOT NULL DEFAULT '[]',
    pleasure          REAL,
    arousal           REAL,
    dominance         REAL,
    properties        TEXT NOT NULL DEFAULT '{}',
    superseded_at     TEXT,
    superseding_id    TEXT,
    supersession_reason TEXT,
    reinstated_at     TEXT,
    PRIMARY KEY (node_id)
);

CREATE INDEX IF NOT EXISTS mindmap_node_tenant_idx
    ON mindmap_node (tenant_id);

CREATE INDEX IF NOT EXISTS mindmap_node_subgraph_idx
    ON mindmap_node (tenant_id, subgraph_id);

CREATE INDEX IF NOT EXISTS mindmap_node_name_idx
    ON mindmap_node (tenant_id, name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS mindmap_edge (
    edge_id           TEXT NOT NULL,
    tenant_id         TEXT NOT NULL,
    source_node_id    TEXT NOT NULL,
    target_node_id    TEXT NOT NULL,
    edge_type         TEXT NOT NULL,
    tier              TEXT NOT NULL,
    confidence_origin TEXT NOT NULL,
    confidence        REAL NOT NULL,
    provenance        TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    valid_from        TEXT,
    valid_until       TEXT,
    pleasure          REAL,
    arousal           REAL,
    dominance         REAL,
    properties        TEXT NOT NULL DEFAULT '{}',
    PRIMARY KEY (edge_id)
);

CREATE INDEX IF NOT EXISTS mindmap_edge_tenant_idx
    ON mindmap_edge (tenant_id);

CREATE INDEX IF NOT EXISTS mindmap_edge_source_idx
    ON mindmap_edge (tenant_id, source_node_id);

CREATE INDEX IF NOT EXISTS mindmap_edge_target_idx
    ON mindmap_edge (tenant_id, target_node_id);

CREATE INDEX IF NOT EXISTS mindmap_edge_type_idx
    ON mindmap_edge (tenant_id, edge_type);

CREATE TABLE IF NOT EXISTS mindmap_alias (
    tenant_id TEXT NOT NULL,
    alias     TEXT NOT NULL,
    node_id   TEXT NOT NULL,
    PRIMARY KEY (tenant_id, alias)
);

CREATE TABLE IF NOT EXISTS mindmap_subgraph (
    subgraph_id  TEXT NOT NULL,
    tenant_id    TEXT NOT NULL,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL,
    root_node_id TEXT,
    created_at   TEXT NOT NULL,
    PRIMARY KEY (subgraph_id)
);

-- FTS5 on node names + properties for text search
CREATE VIRTUAL TABLE IF NOT EXISTS mindmap_fts
    USING fts5(name, properties, content='mindmap_node', content_rowid='rowid');

CREATE TRIGGER IF NOT EXISTS mindmap_fts_ai AFTER INSERT ON mindmap_node BEGIN
    INSERT INTO mindmap_fts(rowid, name, properties) VALUES (new.rowid, new.name, new.properties);
END;

CREATE TRIGGER IF NOT EXISTS mindmap_fts_ad AFTER DELETE ON mindmap_node BEGIN
    INSERT INTO mindmap_fts(mindmap_fts, rowid, name, properties) VALUES('delete', old.rowid, old.name, old.properties);
END;

CREATE TRIGGER IF NOT EXISTS mindmap_fts_au AFTER UPDATE ON mindmap_node BEGIN
    INSERT INTO mindmap_fts(mindmap_fts, rowid, name, properties) VALUES('delete', old.rowid, old.name, old.properties);
    INSERT INTO mindmap_fts(rowid, name, properties) VALUES (new.rowid, new.name, new.properties);
END;
