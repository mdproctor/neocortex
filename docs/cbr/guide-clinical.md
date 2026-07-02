# Clinical — CBR for Adverse Event Investigation

## Why CBR

Clinical trials generate adverse events (AEs) that require investigation. When a new
AE is reported, CBR finds similar past AEs and surfaces what happened: did it trigger
a safety protocol? Was the trial arm modified? What was the causal assessment? This
gives safety officers evidence-based context from the trial's own history.

## CBR Paradigm

**Feature-Vector CBR.** Structured features (event type, trial arm, severity, timing)
provide precise filtering. The event description adds semantic context.

**Knowledge-Intensive CBR** (domain causal model — surface-similar AEs may have different
causal mechanisms) is future R&D. The current Feature-Vector approach is a pragmatic
first step that delivers value without requiring a causal model.

## Feature Schema

```java
CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("clinical-adverse-event",
    FeatureField.categorical("adverse_event_type"),    // MedDRA preferred term
    FeatureField.categorical("trial_arm"),             // TREATMENT, CONTROL, OPEN_LABEL
    FeatureField.numeric("severity_grade", 1, 5),      // CTCAE grade 1-5
    FeatureField.numeric("time_to_onset_days", 0, 365),
    FeatureField.text("event_description"));
```

### Why these fields

- **adverse_event_type** — hepatotoxicity and nephrotoxicity have different investigation playbooks and different historical safety-protocol trigger rates
- **trial_arm** — treatment-arm AEs and control-arm AEs have fundamentally different signal significance
- **severity_grade** — CTCAE grade 3+ events require different response protocols; past outcomes for the same severity band are the relevant precedent
- **time_to_onset_days** — early-onset and late-onset AEs often have different causal mechanisms; a hepatotoxicity event at day 14 is a different signal than one at day 180
- **event_description** — semantic match catches clinical nuance that categorical codes miss ("acute liver failure with concurrent statin use" vs. "transient transaminase elevation")

## Retain — Storing AE Investigation Outcomes

When an AE investigation concludes:

```java
@ApplicationScoped
public class AdverseEventOutcomeObserver {

    @Inject CbrCaseMemoryStore cbrStore;

    void onAeInvestigationClosed(@Observes AeInvestigationClosedEvent event) {
        var cbrCase = new FeatureVectorCbrCase(
            event.eventDescription(),                       // problem
            formatSolution(event),                          // solution summary
            event.disposition().name(),                     // SAFETY_PROTOCOL, CLEARED, MONITORING
            event.causalityConfidence(),                    // nullable
            Map.of(
                "adverse_event_type", event.meddraPreferredTerm(),
                "trial_arm", event.trialArm().name(),
                "severity_grade", event.ctcaeGrade(),
                "time_to_onset_days", event.onsetDays(),
                "event_description", event.eventDescription()));

        cbrStore.store(cbrCase, "clinical-adverse-event",
            event.subjectId(), CLINICAL_DOMAIN, event.tenantId(), event.aeId());
    }

    private String formatSolution(AeInvestigationClosedEvent e) {
        return "Disposition: %s. Causality: %s. Action: %s."
            .formatted(e.disposition(), e.causalityAssessment(), e.actionTaken());
    }
}
```

## Retrieve — Finding Similar Past AEs

When a new AE is reported:

```java
@ApplicationScoped
public class AdverseEventAssistant {

    @Inject CbrCaseMemoryStore cbrStore;

    public List<FeatureVectorCbrCase> findSimilarEvents(AdverseEvent ae) {
        var query = CbrQuery.of(
            ae.tenantId(),
            CLINICAL_DOMAIN,
            "clinical-adverse-event",
            Map.of(
                "adverse_event_type", ae.meddraPreferredTerm(),
                "trial_arm", ae.trialArm().name()),
            10);

        return cbrStore.retrieveSimilar(query, FeatureVectorCbrCase.class);
    }
}
```

Results tell you: "4 similar past hepatotoxicity events in the treatment arm —
all triggered safety protocol. Median severity grade 3, median onset day 21."

## Causal Mechanism Caveat

Surface-similar AEs may have different causal mechanisms. A grade 3 hepatotoxicity
at day 14 with concurrent statin use is a different clinical entity than a grade 3
hepatotoxicity at day 14 from direct drug toxicity — even though the feature vectors
are identical.

Knowledge-Intensive CBR (Richter's "adaptation knowledge" container) would address
this by incorporating a domain causal model that distinguishes between mechanisms.
Until then, retrieved cases should be treated as **decision support, not decision
automation** — the safety officer applies clinical judgment to the CBR results.

## Mock → Production

| Phase | Backend | What works |
|-------|---------|-----------|
| **Now** | `memory-cbr-inmem` | Schema validation, categorical exact match, store/retrieve round-trip. No persistence. |
| **Production** | `memory-qdrant` | Payload filters on event_type/trial_arm + range on severity/onset + dense vector on description. Persistent. |

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
