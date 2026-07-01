package io.casehub.neocortex.memory.jpa;

import io.casehub.neocortex.memory.*;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for a stored memory. domain stored as String (MemoryDomain.name()). */
@Entity
@Table(name = "memory_entry")
public class MemoryEntry extends PanacheEntityBase {

    @Id
    @Column(name = "memory_id", length = 36, nullable = false)
    public String memoryId;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "entity_id", nullable = false)
    public String entityId;

    @Column(name = "domain", nullable = false)
    public String domain;

    @Column(name = "case_id")
    public String caseId;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    public String text;

    /** JSON string serialized from Map<String,String> using Jackson ObjectMapper. */
    @Column(name = "attributes", nullable = false, columnDefinition = "TEXT")
    public String attributes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
