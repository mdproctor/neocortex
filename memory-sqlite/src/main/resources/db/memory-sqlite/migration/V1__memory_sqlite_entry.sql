CREATE TABLE IF NOT EXISTS memory_entry (
    memory_id  TEXT NOT NULL,
    tenant_id  TEXT NOT NULL,
    entity_id  TEXT NOT NULL,
    domain     TEXT NOT NULL,
    case_id    TEXT,
    text       TEXT NOT NULL,
    attributes TEXT NOT NULL DEFAULT '{}',
    -- Stored as truncated-to-millis ISO-8601 (always 24 chars: ...T10:15:30.000Z).
    -- Instant.toString() emits 0/3/6/9 fractional digits; mixed widths sort incorrectly
    -- because '.' (ASCII 46) < 'Z' (ASCII 90). Truncation to millis guarantees uniform width.
    created_at TEXT NOT NULL,
    PRIMARY KEY (memory_id)
);

CREATE INDEX IF NOT EXISTS memory_entry_lookup_idx
    ON memory_entry (tenant_id, entity_id, domain, created_at DESC);

CREATE INDEX IF NOT EXISTS memory_entry_erase_idx
    ON memory_entry (tenant_id, entity_id);

-- FTS5 content table: mirrors text from memory_entry, maintained by triggers below.
CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts
    USING fts5(text, content='memory_entry', content_rowid='rowid');

CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON memory_entry BEGIN
    INSERT INTO memory_fts(rowid, text) VALUES (new.rowid, new.text);
END;

CREATE TRIGGER IF NOT EXISTS memory_fts_ad AFTER DELETE ON memory_entry BEGIN
    INSERT INTO memory_fts(memory_fts, rowid, text) VALUES('delete', old.rowid, old.text);
END;

-- Defensive: CaseMemoryStore is append-only at the SPI level (no update method exists).
-- Included for correctness should a future implementation update rows.
CREATE TRIGGER IF NOT EXISTS memory_fts_au AFTER UPDATE ON memory_entry BEGIN
    INSERT INTO memory_fts(memory_fts, rowid, text) VALUES('delete', old.rowid, old.text);
    INSERT INTO memory_fts(rowid, text) VALUES (new.rowid, new.text);
END;
