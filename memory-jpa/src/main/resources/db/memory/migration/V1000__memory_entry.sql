CREATE TABLE memory_entry (
    memory_id  VARCHAR(36)  NOT NULL,
    tenant_id  VARCHAR(255) NOT NULL,
    entity_id  VARCHAR(255) NOT NULL,
    domain     VARCHAR(255) NOT NULL,
    case_id    VARCHAR(255),
    text       TEXT         NOT NULL,
    attributes TEXT         NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT memory_entry_pk PRIMARY KEY (memory_id)
);

-- Primary query index: tenant/entity/domain scoping + chronological ordering
CREATE INDEX memory_entry_lookup_idx
    ON memory_entry (tenant_id, entity_id, domain, created_at DESC);

-- eraseEntity() index: delete all memories for an entity across all domains
CREATE INDEX memory_entry_erase_idx
    ON memory_entry (tenant_id, entity_id);
