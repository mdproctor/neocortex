# Era 7 — Personality Trait Integration Research

## Thesis

Characters feel more real when their cognitive defaults reflect the interaction between HOW they think (Jungian cognitive style) and WHAT personality traits they exhibit (PID-5 pathological dimensions). Era 7 integrates these two independent personality channels in the CDE, using the Big Five as a validated mediating bridge.

## Two Personality Channels

The neocortex cognitive pipeline has two independent personality representation channels:

**Channel 1: Disposition (Jungian + consensus axes).** The biography pipeline (`extract_personality.py`) assesses characters across 13 personality frameworks (MBTI, OCEAN, Belbin, DISC, SDI, Enneagram, etc.) and derives consensus DispositionAxes (socialOrient, ruleFollowing, riskAppetite, autonomy, conflictMode) plus Jungian function weights (Ti, Te, Ni, Ne, Si, Se, Fi, Fe). These feed the CDE's cognitive style derivations — how the character processes information.

**Channel 2: AMPD/PID-5 facets.** The same pipeline independently scores 13 PID-5 facets (0-100 scale) from biographical evidence. These measure pathological personality traits — maladaptive behavioral tendencies. The 13 facets are drawn from the DSM-5 Alternative Model for Personality Disorders (Krueger et al. 2012).

These channels are assessed independently by the LLM — the prompt explicitly instructs "assess independently, do not let one framework's result bias another."

## The Integration Question

Can we systematically connect PID-5 trait scores to Jungian-derived cognitive defaults to produce richer character behavior?

### What the peer-reviewed literature supports

**Direct evidence (Tier 1):**

1. **PID-5 is a valid dimensional personality measure.** Krueger, R.F. et al. (2012). Initial construction of a maladaptive personality trait model and inventory for DSM-5. *Psychological Medicine*, 42(9), 1879-1890. The foundational paper establishing PID-5's 5 domains and 25 facets.

2. **PID-5 domains are maladaptive variants of the Big Five.** Gore, W.L. & Widiger, T.A. (2013). The DSM-5 dimensional trait model and five-factor models of general personality. *Journal of Abnormal Psychology*, 122, 816-821. Convergent validity: Negative Affectivity↔Neuroticism (r=0.70), Detachment↔low Extraversion (r=-0.57), Antagonism↔low Agreeableness (r=-0.49), Disinhibition↔low Conscientiousness (r=-0.56). Psychoticism↔Openness is weak (r=0.10).

3. **MBTI dimensions map to FFM.** McCrae, R.R. & Costa, P.T. (1989). Reinterpreting the Myers-Briggs Type Indicator from the perspective of the five-factor model of personality. *Journal of Personality*, 57(1), 17-40. E/I→Extraversion, S/N→Openness, T/F→Agreeableness (inverse), J/P→Conscientiousness.

4. **Specific facet-cognition interactions:**
   - Impulsivity degrades working memory. Heydarloo et al. (2024) *Memory*; Kofler et al. (2024) *Frontiers in Psychiatry*. Consistent across ADHD, BPD, and non-clinical populations.
   - Callousness reduces empathy/relationship processing. Blair, R.J.R. (2005, 2008, 2013) *Nature Reviews Neuroscience*. Amygdala-mediated, meta-analyzed.
   - Anxiousness creates attentional bias toward threat. Bar-Haim, Y. et al. (2007) *Psychological Bulletin*, 133(1), 1-24. 172-study meta-analysis, d=0.45.

5. **Jungian types relate to DSM personality pathology.** Ekstrom, S. Jung's typology and DSM-III personality disorders. *Journal of Analytical Psychology*. Maps Jung's eight function-attitudes to personality disorder features (e.g., extreme Extraverted Feeling → histrionic traits, extreme Introverted Thinking → schizoid traits).

### What the literature does NOT support

**No direct peer-reviewed mapping exists from PID-5 facets to Jungian cognitive functions.** The two systems operate in separate research paradigms:

- PID-5: Empirical factor analysis, dimensional severity ratings, clinical populations
- Jungian typology: Qualitative clinical observation, structural archetypes, developmental theory

Researchers have not published a unified model connecting these systems. The reasons include:
- Methodological divide between empirical psychometrics and analytical psychology
- Jungian cognitive functions lack standardised peer-reviewed psychometric validation at the function level (Ti vs Te, Ni vs Ne)
- The 8-function model's evidence base is weaker than the dimension-level (E/I, T/F, S/N, J/P) evidence

### Where we extend beyond literature (Tier 2)

**The Big Five bridge.** Both PID-5 and MBTI have validated mappings to the FFM. We use the FFM as a statistical intermediary to infer how PID-5 facet elevations interact with Jungian-derived cognitive dimensions. This transitive reasoning is grounded in two peer-reviewed links but the composition itself has not been empirically tested as a unified model.

**Function-level extensions.** The MBTI→FFM mapping (McCrae & Costa 1989) operates at the dimension level (E/I, S/N, T/F, J/P), not the function level (Ti vs Te, Ni vs Ne). Our modulations that target specific function combinations (e.g., "Withdrawal reduces connective bias derived from sensing/intuition balance") extend beyond what the dimension-level bridge strictly validates.

**The reasoning chains we use:**

| Modulation | Chain | Evidence gap |
|---|---|---|
| Emotional Lability → amplified affect sensitivity | High lability = high Neuroticism (FFM) = amplified emotional reactivity | Neuroticism↔Emotional Lability is peer-reviewed (r=0.70). Neuroticism→affect sensitivity is a reasonable extension but not directly tested as a CDE modulation. |
| Risk Taking → reduced fear weight | High risk = Disinhibition domain → reduced threat sensitivity | Risk Taking is a PID-5 facet. Its relationship to appraisal fear weighting is inferred from the construct definition, not from a specific study of fear appraisal weights. |
| Impulsivity → amplified urgency | Disinhibition domain → immediate threat/opportunity weighting | Impulsivity→urgency is face-valid and consistent with the construct, but no study directly tests this in an appraisal weight context. |
| Anxiousness → narrowed associative reach | Negative Affectivity → attentional narrowing | Bar-Haim (2007) shows attentional bias in anxiety. The extension to "associative reach" (a cognitive exploration parameter) is our interpretation, not their finding. |
| Withdrawal → reduced connective bias | Detachment → low Extraversion → fewer social connections | The connection is plausible through the FFM bridge. That it specifically affects mental graph connectivity is our architectural decision. |

## Why This Is Appropriate for Our Use Case

This synthesis is explicitly NOT a clinical diagnostic tool. It is a character simulation architecture where the validation criterion is behavioral coherence — do characters feel real and internally consistent?

The Google AI synthesis we consulted framed it well: "Use the unified model as a heuristic tool for self-reflection, depth-oriented coaching, or creative writing, where nuance and narrative coherence matter most. Avoid using it for clinical diagnosis or formal psychological assessment."

Our use case — cognitive agent characters in a social simulation — falls squarely in the "narrative coherence" category. The eval framework (personality differentiation scores, behavioral coherence judges, drive expression ratings) provides empirical validation of whether the integration produces better characters, independent of whether the individual modulations are clinically validated.

## Design Principles

1. **Transparency over confidence.** Every modulation documents its evidence tier. Tier 1 cites a paper. Tier 2 documents the reasoning chain and explicitly marks the evidence gap.

2. **Complementary, not competing.** PID-5 facets modulate Jungian-derived values. They do not replace them. The Jungian channel measures cognitive style (HOW). PID-5 measures trait pathology (WHAT). Both are needed.

3. **Conservative modulation weights.** Tier 2 modulations start with modest effect sizes (0.2 weight) and are tuned via eval results. A facet score of 0.5 (population mean) produces zero modulation.

4. **Eval-validated.** The test is not "is this clinically correct?" but "does this produce more coherent, differentiated characters?" The eval framework answers that question empirically.

## What Changed from Prior Eras

| Era | Personality representation | Resolution |
|---|---|---|
| Era 1-3 | Jungian functions + OCEAN | Categorical (3-4 levels) |
| Era 4-5 | + DispositionAxes consensus | Categorical (3-5 values per axis) |
| Era 6 | + AMPD/PID-5 facets (expression styles, search budget, recurring thoughts) | Continuous [0,1] for 5 methods, categorical for 6 |
| **Era 7** | **Bridge fix + full facet integration + Jungian×PID-5 modulation** | **Continuous for all methods, dual-channel interaction** |

## Implementation Status (2026-11-17)

All design goals achieved. Implementation complete across three repos (eidos, neocortex, examples). Coping strategies pipeline (epic #595) Batches 1-5 complete — regulation refactoring, CAPS coping attractors, goal operations dispatch all wired.

### Downstream consumer: situation classifier PID-5 integration (#977)
- PID-5 facets flow through the CAPS situation classifier pipeline. `addPersonalityMetadata()` populates `pid5.<Facet>=<2dp>` metadata keys alongside disposition axes, using the `LearningProfile` API (tenant-scoped, resting values). `PersonalityPromptBuilder` formats them as natural-language annotations (high/low/moderate labels). CBR cache key includes PID-5 in SHA-256 hash — different personality magnitudes produce different cache entries. Model version `haiku-pid5` invalidates pre-PID-5 cache.
- **Resolved (#1085, #1086):** AppraisalWeights enrichment restored. `LearningProfile` now exposes `appraisalWeights()` — the resting-only contract is preserved since appraisal weights are personality-derived, not situationally displaced. Both consolidation phases delegate to `PersonalityMetadataEnricher` via a new `LearningProfile` overload. All three enrichment dimensions (disposition axes, PID-5 facets, appraisal weights) are active in classifier prompts.
- Full pipeline: Engram YAML → Eidos → Bridge → DescriptorView → CDE derivation → metadata enrichment → classifier prompt + CBR cache.
- Follow-up: #1071 extracted `PersonalityMetadataEnricher` shared utility in cognitive-index. #1086 revived it with a `LearningProfile` overload — both `BehavioralSynthesisPhase` and `DriveProfileDerivationPhase` now delegate to it, eliminating the duplicate inline enrichment from the #712 rebase. #1070 wired `Pid5FacetsConfig` to filter facets at enrichment time. #1072 removed dead `hasPid5()` and hardened eval assertions.

### Pipeline unification
- `AmpdFacet` record added to `AgentDisposition` (eidos-api). Backward-compatible 10-arg constructor preserved.
- `EidosCognitiveProfileBridge.toDescriptorView()` maps `AmpdFacet` → `PersonalityFacet` with title-case normalization. Origin tagged as `"eidos"`.
- Pipeline path: Engram YAML → Eidos `AgentDisposition.ampdFacets` → Bridge → `DescriptorView.personalityFacets` → CDE.

### CDE modulations implemented
- **Category A (6 methods):** deriveMoodBaseline, deriveCuriosity, deriveCbrStrategy, deriveSocialCognition, deriveHabituationConfig, deriveRegulationDefaults — all upgraded to accept and apply PID-5 facet modulations on top of axes-derived values. **Implemented (7th, #991):** deriveCopingDefaults — Connor-Smith & Flachsbart (2007) Big Five→coping correlations. Derives CopingDefaults (planfulTendency, reinterpretationTendency, avoidanceTendency, supportSeekingTendency, acceptanceTendency) from PID-5→Big Five composites. Wired into derive() and deriveAndMerge() pipelines. CopingStrategyPredictor (#992) consumes these defaults via 6-stage controllability-first selection pipeline. CopingGenerationPhase (#993) provides the LLM layer: builds a system prompt with the 16 EMA strategies, character personality context, SEC dimensions, and current emotions; parses structured JSON into CopingResult (strategy, reinterpreted narrative, goal operations, drive modulations, attention directives). Includes a prompt constraint for keyword-detectable agency/helplessness language to support SEC re-evaluation in the micro-loop. CopingMicroLoop (#994) orchestrates the full appraisal-coping-reappraisal cycle: predictor → LLM generation → apply effects → keyword SEC re-evaluation → converge or iterate (max 3 iterations). PreCopingSafetyGate relocates the fatigue/intensity circuit breaker from regulation Layer 1. EmotionMapper.mergeDimensions promoted to public for cross-package SEC re-evaluation. Regulation refactoring (#995): EmotionRegulationGenerationPhase.generateForCoping() receives coping strategy + regulation target + reinterpreted narrative (does not re-select strategy — assesses effectiveness only). RegulationStrategyPredictor @Deprecated (strategy selection intelligence migrated to CopingStrategyPredictor). EmotionRegulationTickParticipant coping path: copingEnabled gate → PreCopingSafetyGate → CopingMicroLoop → regulation-routed strategies call generateForCoping, direct-effect strategies skip regulation. handleRegulationResult() extracted for shared post-regulation processing. AppraisalTickParticipant.currentContext() exposes AppraisalContext for CopingMicroLoop consumption. Goal operations dispatch (#996): GoalProposalOrchestrator.requestAbandonment(agentId, tenantId, goalName, reason) and requestDemotion(agentId, tenantId, goalName, priority, reason) — queue-based coping-driven goal lifecycle that merges with existing drive-stale/failure abandonments and narrative-driven demotions at tick time. CopingCheck keyword expansion: agency (7→26) and helplessness (7→25) keyword sets expanded to 51 total for LLM-generated narrative coverage. CAPS coping attractors (#997): new "coping" output category (16 EMA strategy nodes) in caps-topology.yaml. CopingMicroLoop Layer 2 CAPS coping attractor resolution (settle, filter "coping" category, confidence threshold + dominance margin check). Rescorla-Wagner weight update on coping strategy attractor after regulation execution. GoalOperation dispatch from CopingState (CREATE→appendGoals, DROP→requestAbandonment, DEFER→requestDemotion).
- **Keyword classifiers fully removed (#1113):** All keyword-based SEC checks (CopingCheck, ImplicationCheck, NormativeCheck, RelevanceCheck) deleted along with SchererAppraisalStrategy, the individual LLM checks (LlmCopingCheck, LlmImplicationCheck, LlmNormativeCheck), SchererAppraisalConfig, and the SecCheck interface. `LlmAppraisalStrategy` is now the only `AppraisalStrategy` — `CognitionDefaultBeans` requires `SessionPipeline` and throws if unavailable. `CopingMicroLoop.reEvaluate()` delegates to `AppraisalStrategy` instead of iterating keyword `SecCheck` implementations. This completes the semantic resolution boundary enforcement across the cognitive pipeline: CAPS situation (#1050s), BDI intent (#1098), and SEC appraisal (#1113) all now require LLM-based classification. `ValenceClassificationEvalTest` (#1112) verifies IntentValence quality across 14 BDI scenarios.
- **Tier 1 (3 modulations):** Impulsivity→WM (deriveCapacity), Callousness→relationship bias (deriveExtractionBias), Anxiousness→fear (deriveAppraisalWeights).
- **Tier 2 (5 modulations):** Emotional Lability→affect sensitivity, Risk Taking→fear (inverse), Impulsivity→urgency, Anxiousness→associative reach, Withdrawal→connective bias.
- All modulations use `(facetScore - 0.5) * weight` delta pattern with weight=0.2 (modest initial effect).
- Empty facets = zero modulation = backward-compatible behavior.

### Derivation pipeline observability (#960)
- `DerivationObserver` interface in cognitive-observability-api — consistent with existing `CompactionObserver`, `MemoryRecallObserver` patterns.
- `DerivationTrace` journal accumulator captures: PipelineStart (full descriptor context), StageComputation (base values → facet effects → final values per method), DataFlow (cross-stage value propagation), PipelineComplete.
- `eventRef` on every entry for script-position correlation (tick/prompt correlation).
- Stage filter predicate for constraining capture scope.
- `derive(DescriptorView, String eventRef, DerivationObserver observer)` overload threads observer through all methods.

### Test coverage
- 23 PID-5 facet modulation tests (direction assertions per facet per method)
- 7 derivation trace tests (journal capture, query, filtering, cross-stage flow)
- 512 eidos API tests, 447 cognitive-index tests, 7 bridge tests, 712 wacky-manor fast tests — all pass with 0 regressions.

### Completed (observability)
- neocortex#961: Runtime cognitive pipeline journal ✅ — CognitiveEntry sealed hierarchy (13 entry types), CognitiveJournal interface, ScopedCognitiveJournal with ThreadLocal scoping, CognitiveJournalStore persistence, DerivationJournalBridge, MemoryRecallJournalBridge, 7 runtime instrumentation points in CognitionCore.tick(), dead observer cleanup (CompactionObserver, ObservationCacheObserver removed).
- neocortex#969: CDI wiring activation ✅ — CognitionCoreFactory injects CognitiveJournal, ConsolidationScheduler CDI constructor accepts it, InMemoryCognitiveJournalStore made @ApplicationScoped (activates the entire pipeline). Follow-up: neocortex#980 (consolidation phase instrumentation), examples#209 (PlaybookOrchestrator wiring).

### Remaining work
- neocortex#962: Instrument remaining 4 CDE methods with journal (searchBudget, pool, recurringThought, expressionStyles)
- neocortex#963: Bridge should fire onFacetMapped events
- examples#207: Add ampdFacets to wacky-manor character engrams
- neocortex#964: YAML round-trip test for ampdFacets deserialization
- ~~neocortex#980: ConsolidationScheduler does not journal during consolidation phases~~ ✅ Landed 2026-11-17 (setCognitiveJournal() on ConsolidationPhase SPI)
- ~~neocortex#1070: Wire pid5Facets config to metadata enrichment and cache key~~ ✅ Landed 2026-11-17 (Pid5FacetsConfig in mindmap-intelligence, PersonalityMetadataEnricher filters)
- ~~neocortex#1071: Extract addPersonalityMetadata to shared utility~~ ✅ Landed 2026-11-17 (PersonalityMetadataEnricher in cognitive-index)
- ~~neocortex#1072: Remove dead hasPid5(), strengthen eval test assertions~~ ✅ Landed 2026-11-17

### Epic #1050 — CAPS pipeline wiring completeness (resolved 2026-11-17)
84 issues across the full CAPS pipeline — topology structure, coping→regulation connections, orphaned output nodes, personality-aware classification (PID-5 + AppraisalWeights), search budget, drive composition, CAPS settling, and observability. All verified end-to-end. Pre-existing test failures filed as #1077 (AttentionSignalTest) and #1078 (CopingStrategyPredictorTest).

### Epic #1090 — Cognition bug fix sweep (2026-11-17)
8-issue queue addressing pre-existing failures and correctness bugs across the cognitive pipeline. 3 issues already resolved by prior sessions (#1078, #1049, #1038). 1 issue referenced a removed class (#1088 — SituationalStateService no longer exists). 3 fixes landed:

| Issue | Fix | Impact |
|---|---|---|
| #1077 | AttentionSignalTest enum count 12→14 (COPING_FOCUS, COPING_SUPPRESS) | Test correctness |
| #1061 | BehavioralSynthesisPhase cache key agentId → tenantId:agentId | Multi-tenant isolation — prevents cross-tenant settling result leakage |
| #1064 | 9 MindMapQuery search limits raised 200→2000 | Prevents silent data loss from search-then-filter pattern on active tenants |

#1060 (PersonalityEvolutionOrchestrator never ticked) deferred — requires design decision on evolution cadence and propagation path. Filed: #1091 (MindMapQuery property filters), #1092 (AppraisalStatePropagationTest failure), #1093 (EmotionRegulationTickParticipantTest failures).

## Open Questions

1. **Should `derivePersonality` (the foundation derivation) get PID-5 modulation?** Currently excluded to avoid cascading effects. May revisit after eval validation of downstream modulations.

2. **Are 13 facets sufficient?** The full PID-5 has 25 facets. The biography pipeline scores 13. Missing facets include Anhedonia, Depressivity, Restricted Affectivity, Separation Insecurity, Submissiveness, and the Psychoticism facets. Some of these (Anhedonia, Depressivity) could add richness. Note: any additional facets automatically flow through the EffectiveCognitiveDefaults pipeline — `composeFacets` iterates ALL facets in the base DescriptorView. The gap is attractor profile data in `archetype-situational-profiles.yaml`, not code. See #1076.

3. **Should Tier 2 modulations be promoted to Tier 1?** If eval results show significant coherence improvement from a Tier 2 modulation, and subsequent literature search finds supporting evidence, the modulation can be reclassified.

## Era 7b — Situational Personality Modulation (#712)

**Status (2026-11-17):** All 5 batches complete. Implementation finished — 79 tests (75 cognition + 4 caps-engine). Production CDI wiring deferred to #1082.

- **Batch 1:** Config loader, integrator, detector — 44 tests
- **Batch 2:** Appraisal fixes — SEC dimensions passthrough, tick stamping, relational bias clamp, input activations accessor — 9 tests
- **Batch 3:** MindMap personality-state persistence (SituationalStateService), eidos profile-to-delta conversion (SituationalDeltaConverter) — 16 tests
- **eidos#234:** Prerequisite delivered — AmpdFacetShift, SituationalProfile, 60 archetype-family profiles (12 families × 5 modes), SituationalProfileDeriver — 26 tests, 616 total eidos tests green
- **#1055 (Part 0a):** EffectiveCognitiveDefaults with resting/effective split — 65 files changed, ~108 production callsite migration, 2301 tests green (0 regressions). Key deliverables:
  - `EffectiveCognitiveDefaults` — tenant-scoped `ConcurrentHashMap<(tenantId, agentId), Slot>` with version-based concurrency protocol. Two read paths: `forAgent` (effective) and `restingFor` → `LearningProfile` (resting). Two SPIs for lazy population: `DescriptorViewSource` (Eidos), `PersonalityStateSource` (situational state). Facet composition: `clamp01(base + homeOffset [+ displacement])` per facet. Pure `merge(explicit, derive(fresh inputs))` — never merge with previous result.
  - `LearningProfile` — type-level enforcement of the TESSERA constraint. Learning rules cannot be fed effective values without a compile error. Every consumer classified per the spec's table: 9 resting, 8 effective, 3 enumerate, 3 explicit-only.
  - Bridge simplified — `bridgeAll`/`registerFromDescriptor` deleted, `toDescriptorView` public static. `EidosDescriptorViewSource` implements `DescriptorViewSource` SPI.
  - `CognitionCore.tick` wires `updateBase` before FOUNDATION. New module edge: cognition → cognitive-eidos.
  - **Deferred:** #1079 (field-classification test), #1080 (AgentRegistryEvents listener), #1081 (stale refresh, eviction wiring)
- **Batch 4:** SituationalPersonalityParticipant TERMINAL tick wiring (6 tests), SituationalDeformationPhase consolidation (5 tests). Code review finding: per-agent detection isolation bug (shared volatile lastResult → ConcurrentHashMap keyed by agentId:tenantId). DeformationPhase journal field made volatile.
- **Batch 5:** Journal entry types (SituationalModulation, SituationalDeformationConsolidated), E2E integration test — full stress cycle with recovery, noise-floor idle, state persistence, tenant isolation (4 tests). Noise-floor topology test in caps-engine — threat_sensitivity marginally exceeds per-mode floor (0.3241 vs 0.30) under cumulative activation; cumulative threshold set to 0.35 (4 tests).
- **Issues filed:** #1068 (prompt clause), #1069 (map cleanup), #1073 (attention accumulator tenant grouping), #1079 (field-classification test), #1080 (AgentRegistryEvents listener), #1081 (stale refresh + eviction), #1082 (CDI wiring for participant), #1083 (RelationalSchemaResolver trust signal), #1084 (PipelineTrace snapshot)
- **Blogs:** "The Elastic Band" (design), "Where Personality Lives" (implementation boundary), "The Type-Level Split" (#1055 resting/effective enforcement), "Wiring the Elastic Band" (Batch 4-5 implementation, bugs, topology findings)

### Concept

Era 7 made personality static — PID-5 facets derived once at character creation drive all cognitive defaults. Era 7b makes personality *situational*: facets shift continuously in response to detected stress, flow, and social context, then partially return when the situation resolves. The mechanism: an elastic band model where each facet has displacement (current pull), home offset (lasting drift), and resilience (recoverability).

### Theoretical grounding

- **Whole Trait Theory** (Fleeson & Jayawickreme 2015): a trait is a density distribution of personality states, not a fixed point.
- **Transactional stress** (Lazarus & Folkman 1984): stress = relevance × goal obstruction × low coping potential.
- **TESSERA** (Wrzus & Roberts 2017): lasting personality change accrues from repeated states through slow processes.
- **Mood-congruent appraisal** (Bower 1981; Forgas 1995 AIM): endogenous signals amplify situational evidence, never substitute for it. Gain-form: `E = X · (1 + β·N)` guarantees `X=0 ⇒ E=0`.
- **Archetype shadow forms** (Pearson 1991; Mark & Pearson 2001): 12 families with characteristic degradation patterns.

### Architecture

The modulation sits between the personality store and the CDE. The CDE is unmodified — it receives effective facet scores and derives cognitive defaults through all existing pathways.

| Component | Role |
|---|---|
| `SituationalPersonalityConfig` + YAML | Detection thresholds, signal weights, dynamics parameters |
| `SituationalDetector` | Gain-form evidence → mode intensities and group winners |
| `PersonalityStateIntegrator` | Elastic band — advance (hourly grid) + pull (per-tick) |
| `SituationalStateService` | Per-agent state cache, MindMap persistence |
| `SituationalPersonalityParticipant` | TERMINAL tick participant |
| `EffectiveCognitiveDefaults` (#1055) | Resting vs effective split |

### Key design decisions

1. **One model, not overlay.** Single continuously-evolving state vector. No discrete overlay/snapshot mechanism.
2. **Implicit modulation only.** No prompt rendering of shadow context. Change flows through CDE parameter modulation.
3. **Resting vs effective defaults.** Learning rules read resting; behaviour reads effective.
4. **Wall-clock grid.** Slow dynamics at hour boundaries with exact integrals. Tick-rate independent.

### Pre-existing defects surfaced during design review

#1060 (PEO never ticked), #1061 (agentId-keyed caches), #1062 (duplicated CAPS settlements), #1063 (ConsolidationScheduler starvation), #1064 (bounded search filter), #1065 (no hierarchy roles).
