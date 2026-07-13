package io.casehub.neocortex.memory.cbr.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cbr_case")
public class CbrCaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "domain", nullable = false)
    public String domain;

    @Column(name = "case_type", nullable = false)
    public String caseType;

    @Column(name = "cbr_type", nullable = false, length = 50)
    public String cbrType;

    @Column(name = "entity_id", nullable = false)
    public String entityId;

    @Column(name = "case_id")
    public String caseId;

    @Column(name = "problem", nullable = false, columnDefinition = "TEXT")
    public String problem;

    @Column(name = "solution", nullable = false, columnDefinition = "TEXT")
    public String solution;

    @Column(name = "outcome", columnDefinition = "TEXT")
    public String outcome;

    @Column(name = "confidence")
    public Double confidence;

    @Column(name = "features", nullable = false, columnDefinition = "TEXT")
    public String features;

    @Column(name = "plan_traces", columnDefinition = "TEXT")
    public String planTraces;

    @Column(name = "stored_at", nullable = false)
    public Instant storedAt;
    @Column(name = "outcome_detail", columnDefinition = "TEXT")
    public String  outcomeDetail;

    @Column(name = "last_outcome_at")
    public Instant lastOutcomeAt;

}
