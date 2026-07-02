# AML — CBR for Anti-Money Laundering Investigation

## Why CBR

AML investigators assess suspicious transactions. When a new alert fires, CBR finds
similar past investigations and surfaces outcomes: was a SAR filed? Was it cleared?
What patterns of evidence led to escalation? This gives investigators decision
support grounded in institutional history, not just rule-based thresholds.

## CBR Paradigm

**Textual + Feature-Vector CBR.** Structural features (transaction pattern, risk tier,
jurisdiction) filter the candidate set precisely. The investigation narrative provides
semantic similarity for cases that share the same structural profile but differ in
the specifics that matter.

## Feature Schema

```java
CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("aml-investigation",
    FeatureField.categorical("transaction_pattern"),   // STRUCTURING, LAYERING, SMURFING, ROUND_TRIP
    FeatureField.categorical("entity_risk_tier"),      // LOW, MEDIUM, HIGH, PEP
    FeatureField.categorical("jurisdiction"),           // ISO 3166-1 alpha-2
    FeatureField.categorical("amount_range"),           // 0-10K, 10K-50K, 50K-100K, 100K-500K, 500K+
    FeatureField.numeric("prior_sars_on_entity", 0, 100),
    FeatureField.text("investigation_narrative"));
```

### Why these fields

- **transaction_pattern** — different patterns have different SAR filing rates; structuring investigations follow different playbooks than layering
- **entity_risk_tier** — a PEP entity has a fundamentally different risk profile; past PEP investigations are the relevant precedent
- **jurisdiction** — regulatory regimes differ; Cyprus investigations aren't comparable to US investigations
- **amount_range** — bucketed rather than continuous because AML thresholds are discrete (CTR at $10K, enhanced due diligence at higher tiers)
- **prior_sars_on_entity** — repeat offenders have different outcome distributions
- **investigation_narrative** — semantic match catches domain-specific patterns that categories miss ("shell company in Cyprus" vs. "trade-based ML through free trade zone")

## Retain — Storing Investigation Outcomes

When an investigation closes, retain the case:

```java
@ApplicationScoped
public class InvestigationOutcomeObserver {

    @Inject CbrCaseMemoryStore cbrStore;

    void onInvestigationClosed(@Observes InvestigationClosedEvent event) {
        var cbrCase = new FeatureVectorCbrCase(
            event.narrative(),                              // problem
            formatSolution(event),                          // solution summary
            event.disposition().name(),                     // SAR_FILED, CLEARED, ESCALATED
            event.confidence(),                             // analyst confidence
            Map.of(
                "transaction_pattern", event.pattern().name(),
                "entity_risk_tier", event.riskTier().name(),
                "jurisdiction", event.jurisdiction(),
                "amount_range", event.amountRange(),
                "prior_sars_on_entity", event.priorSarCount(),
                "investigation_narrative", event.narrative()));

        cbrStore.store(cbrCase, "aml-investigation",
            event.entityId(), AML_DOMAIN, event.tenantId(), event.investigationId());
    }

    private String formatSolution(InvestigationClosedEvent e) {
        return "Disposition: %s. Evidence: %s. Duration: %s days."
            .formatted(e.disposition(), e.evidenceSummary(), e.durationDays());
    }
}
```

## Retrieve — Finding Similar Past Investigations

When a new alert triggers an investigation:

```java
@ApplicationScoped
public class InvestigationAssistant {

    @Inject CbrCaseMemoryStore cbrStore;

    public List<FeatureVectorCbrCase> findSimilarInvestigations(Alert alert) {
        var query = CbrQuery.of(
            alert.tenantId(),
            AML_DOMAIN,
            "aml-investigation",
            Map.of(
                "transaction_pattern", alert.pattern().name(),
                "entity_risk_tier", alert.riskTier().name(),
                "jurisdiction", alert.jurisdiction()),
            10);

        return cbrStore.retrieveSimilar(query, FeatureVectorCbrCase.class);
    }
}
```

Results tell you: "8 similar past investigations for STRUCTURING / HIGH / CY —
6 resulted in SAR filing. Common evidence path: bank statement analysis → beneficial
ownership check → SAR."

## Compliance Considerations

- CBR does not make filing decisions — it provides historical context for human analysts
- All cases are tenant-isolated; cross-tenant leakage is impossible by design
- `eraseEntity()` supports GDPR Art.17 erasure across all stored cases for an entity
- Investigation narratives stored in `problem()` may contain PII — ensure Qdrant deployment meets your data residency requirements

## Mock → Production

| Phase | Backend | What works |
|-------|---------|-----------|
| **Now** | `memory-cbr-inmem` | Schema validation, categorical exact match, store/retrieve round-trip. No persistence. |
| **Production** | `memory-qdrant` | Payload filters on pattern/tier/jurisdiction + range on prior_sars + dense vector on narrative. Persistent. |

## Maven Dependencies

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-inmem</artifactId>
    <scope>test</scope>
</dependency>
```
