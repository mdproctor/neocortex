# Era 8 — Cognitive Pipeline Validation Research

## Thesis

A cognitive agent architecture is only as real as its measurable behavioral differentiation. Era 8 establishes the validation methodology: systematic divergence testing of all 48 pipeline capabilities, proving that personality differences produce observable behavioral differences at every layer of the cognitive stack.

## The Measurement Gap

Eras 1-7 built the cognitive pipeline: CAPS settling (#39), Theory of Mind (#25), emotion regulation (#27), situational personality (#46), PID-5 facet integration, drive composition, search budgets, and 40+ other capabilities. Each was designed to produce personality-dependent output. But only 6 of 48 capabilities had tests proving personality differentiation (see Capability Audit below).

The gap is not in the pipeline's design — it's in the evidence. Without systematic divergence tests, we cannot distinguish between:
- A capability that produces personality-dependent output (working as designed)
- A capability that is correctly wired but produces identical output for all personalities (inert)
- A capability that is not wired at all (dead code)

## Capability Audit

Full audit conducted 2026-11-19. 48 distinct capabilities identified across 6 pipeline layers.

### Coverage before era 8

| Layer | Capabilities | Tested | Coverage |
|-------|-------------|--------|----------|
| Derivation | 15 | 2 | 13% |
| Tick pipeline | 14 | 2 | 14% |
| Prompt rendering | 9 | 1 | 11% |
| Consolidation | 7 | 1 | 14% |
| Multi-agent (ToM) | 4 | 0 | 0% |
| Multi-tick (regulation) | 5 | 0 | 0% |
| **Total** | **48** | **6** | **12.5%** |

### PID-5 facets that modulate the pipeline

| Facet | Capabilities modulated |
|-------|----------------------|
| Anxiousness | mood baseline, curiosity channel (I/D-type), search convergence, threat CPT priors, recurring thought escalation, habituation threshold |
| Risk-Taking | mood pleasure, search laterality, curiosity exploration weight, habituation rate, coping confrontation |
| Emotional Lability | mood arousal, recurring thought sensitivity |
| Withdrawal | search depth, social engagement |
| Callousness | ToM distress-avoidance coupling, expression styles |
| Hostility | coping confrontation, appraisal threat sensitivity |
| Suspiciousness | ToM evidence scaling (attributional bias), trust rate |
| Impulsivity | working memory span, search breadth, coping tendency |
| Intimacy Avoidance | affiliation drive damping |
| Manipulativeness | expression styles, social strategy |

### Disposition axes that modulate the pipeline

| Axis | Capabilities modulated |
|------|----------------------|
| socialOrient | mood arousal, trust rate, conflict interpretation, social centrality |
| ruleFollowing | CBR retrieval mode, repetition tolerance, coping suppression |
| riskAppetite | mood pleasure, habituation rate, coping confrontation vs avoidance |
| autonomy | mood dominance, regulation reappraisal weight, coping autonomy |
| conflictMode | conflict interpretation, regulation weights, coping defaults |

## Test Architecture

### Design principles

1. **Real pipeline, no mocks.** All tests run on `CognitionTestHarness` with `InMemoryCapsEngine`, real `CognitiveDerivationEngine`, real consolidation phases. In-memory stores behave identically to production — they are implementations, not mocks.

2. **Contrastive personality pairs.** Every test uses the same two agents: open/bold (low anxiousness, high risk-taking, cooperative) vs cautious/avoidant (high anxiousness, low risk-taking, independent). These produce maximal divergence across the most pipeline capabilities.

3. **Observable assertions.** Each test asserts a measurable difference — numeric inequality, different enum values, different string content. No "not null" assertions that pass for any value.

4. **Deterministic.** No LLM in the loop. Pipeline output is fully determined by personality inputs + observation text + MindMap state. Tests are repeatable.

### Test structure

```
cognition/src/test/java/.../eval/divergence/
  DivergenceFixtures.java                   — shared personality pairs and builder helpers
  DerivationDivergenceTest.java             — 9 tests (derivation-only, no tick)
  AppraisalDivergenceTest.java              — 8 tests (6 derivation + 2 tick-level via withAppraisalEnabled)
  ConsolidationDivergenceTest.java          — 6 tests (seeded MindMap + consolidation + trace)
  PromptSectionDivergenceTest.java          — 8 tests (tick + render)
  TheoryOfMindDivergenceTest.java           — 4 tests (derivation-level ToM)
  TheoryOfMindBridgeDivergenceTest.java     — 4 tests (standalone Bayesian posterior inference)
  EmotionRegulationDivergenceTest.java      — 4 tests (multi-tick)

cognition/src/test/java/.../eval/
  TestAppraisalStrategy.java                — rule-based AppraisalStrategy for tick-level tests
  TestAppraisalStrategyTest.java            — 2 tests (SEC + action tendency divergence)
  CognitionTestHarnessAppraisalTest.java    — 2 tests (harness wiring + mutual exclusion)
```

8 divergence test classes. 43 divergence tests total. All pass in <500ms, deterministic, no LLM.

`TestAppraisalStrategy` computes SEC dimensions from personality-derived `AppraisalWeights` (fear onset threshold → conduciveness, urgency weight → controllability) and action tendencies from `DispositionAxes` (cooperative → APPROACH, avoidant → AVOIDANCE). Wired via `Builder.withAppraisalEnabled(true)` through `CognitivePipelineBuilder.appraisal()` to create a real `AppraisalTickParticipant`. This enables tick-level assertions on `PipelineTrace.appraisalResult()` — the trace field was always null before because `CognitionConfig.all()` has `appraisalEnabled=false` and the mock path (`withAppraisalResult()`) never runs `tick()`.

### Epic and issues

- Epic: casehubio/neocortex#1175
- DivergenceFixtures + Derivation: #1176
- Appraisal: #1177
- Consolidation: #1178
- Prompt sections: #1179
- Theory of Mind: #1180
- Emotion regulation: #1181
- Cleanup (delete PersonalityDifferentiationTest): #1182

## Scenario Evaluation Strategy

### Two-layer validation

The divergence tests prove the pipeline **produces** differentiated output. Scenario evaluation proves the LLM **uses** that differentiated output to generate distinguishable character behavior.

| Layer | What it proves | Method | Deterministic? |
|-------|---------------|--------|---------------|
| Divergence tests | Pipeline produces different signals | CognitionTestHarness assertions | Yes |
| Scenario evaluation | LLM generates different behavior | Short-run + LLM classifier scoring | No |

### Short-run scenario methodology

From the drive expression eval work (era 4-5): targeted scenarios with 10-20 events, each designed to activate a specific cognitive pathway. Scored by LLM classifier. Golden outputs crafted for underperforming cases.

### Threat scenario design (planned)

The existing scenario repertoire exercises cooperative/competitive dynamics — roughly 40% of the pipeline. Threat pathways (ToM under deception, trust evolution under betrayal, CAPS under high-threat/high-uncertainty) remain unexercised.

Planned short-run scenarios:
- Betrayal discovery (ToM + trust evolution)
- False accusation (suspiciousness evidence weighting + defense)
- Alliance fracture (drive competition: loyalty vs self-preservation)
- Deceptive cooperation (CAPS threat/reward competition)
- Trust erosion over time (Bayesian posterior updating)

## Progress

### Landed

| Issue | Description | Date |
|-------|------------|------|
| #1160 | CognitionTestHarness CAPS settling + behavioral attractor divergence | 2026-11-19 |
| #1163 | EffectiveCognitiveDefaultsTest assertion fix (situational origin format) | 2026-11-19 |
| #1172 | CognitionTestHarness trajectory accessor (CapsTrajectory record) | 2026-11-20 |
| #1174 | InnerLifeOrchestratorTest NPE fix (unstubbed DriveProfile mock) | 2026-11-20 |
| #1176 | DivergenceFixtures + DerivationDivergenceTest — 9 tests | 2026-11-20 |
| #1177 | AppraisalDivergenceTest — 6 tests | 2026-11-20 |
| #1178 | ConsolidationDivergenceTest — 5 tests | 2026-11-20 |
| #1179 | PromptSectionDivergenceTest — 8 tests | 2026-11-20 |
| #1180 | TheoryOfMindDivergenceTest — 4 tests | 2026-11-20 |
| #1181 | EmotionRegulationDivergenceTest — 4 tests | 2026-11-20 |
| #1182 | Delete PersonalityDifferentiationTest — migrated to divergence suite | 2026-11-20 |
| #1171 | CAPS trajectory assertions — accessor mapping + both-agents-converge | 2026-11-20 |
| #1165 | DevelopmentalPhaseResolver MindMapNode overload tests (5 paths) | 2026-11-20 |
| #1164 | SituationalSnapshotAccessorProducer unit tests (6 tests) | 2026-11-20 |
| #1153 | SituationalPersonalityParticipant coverage — evictIdle, TrustAccessor, facet clamping | 2026-11-20 |
| #1079 | EffectiveCognitiveDefaults field classification test — resting vs effective | 2026-11-20 |
| #1187 | TheoryOfMindBridgeDivergenceTest — 4 standalone Bayesian posterior tests | 2026-11-20 |
| #1188 | TestAppraisalStrategy + withAppraisalEnabled + 2 tick-level appraisal tests | 2026-11-20 |
| #1195 | Wire SituationClassifier for capsInputActivations divergence — 1 test | 2026-11-20 |
| #930 | 300-tick scenario validation of CognitionCore re-tick — mood stability, drive coherence, baseline comparison | 2026-11-20 |
| #1183 | evictIdle for 8 remaining cognition orchestrators — cache eviction pattern | 2026-11-20 |
| #1184 | Wire registerTenant into CognitionCore tick — consolidation tenant discovery | 2026-11-20 |

### Blocked

| Issue | Description | Blocker |
|-------|------------|---------|
| #1162 | MutationContext → ScopedValue migration | maven.compiler.release=21 (platform constraint, #1173 closed as not-planned) |

### Closed (not planned)

| Issue | Description | Reason |
|-------|------------|--------|
| #1173 | Raise maven.compiler.release to 25+ | Must stay at 21 for Maven 4.0 alignment |

### Open — follow-up

| Issue | Description | Status |
|-------|------------|--------|
| #1187 | Standalone TheoryOfMindBridge divergence tests | queued |
| #1188 | Extend harness with lightweight appraisal for tick-level divergence | queued |

## Key Finding: The Tenant ID Trap

During #1160 implementation, CAPS initialization in the harness Builder used hardcoded `"test-tenant"` while `consolidate()` runs with the caller's tenant ID (`"test"`). Both agents were silently auto-initialized with identical default axes, making all CAPS settling results identical regardless of personality.

**Fix:** Lazy initialization in `consolidate()` — checks each agent's CAPS state on first call and initializes with their descriptor's disposition axes using the caller's actual tenant ID.

**Lesson:** Cross-component integration tests catch wiring bugs that unit tests cannot. The CAPS engine, the derivation engine, and the consolidation phase each worked correctly in isolation. The failure was in how they were connected — a tenant ID mismatch invisible to any single component's tests.

## Key Finding: CAPS Trajectory Non-Divergence

Issue #1171 tested whether contrasting personalities produce different settling trajectories (iterations, convergence type, saturation ratio). Both the open and cautious agents receive radically different CAPS input activations via `DriveCompetitionDetector.DRIVE_CAPS_MAPPINGS`:

- **Open:** explore, BAS_activation, approach (adventure + curiosity drives)
- **Cautious:** threat_sensitivity, distrust, BIS_activation, escape_assessment, FFFS_activation (suspicion + self-preservation drives)

Despite different inputs and personality-dependent connection weights via `DispositionWeightMapper`, both agents converge in exactly 10 iterations with identical convergence type (CONVERGED) and zero saturation. The existing `behavioralAttractorsDiverge` test confirms the settling *destinations* differ — but the *paths* are identical.

**Implication:** The current topology converges too quickly for trajectory to serve as a personality differentiation signal. The damping factor (0.4) and network structure produce rapid convergence regardless of input pattern. If trajectory divergence is desired as a test signal, the topology would need deeper feedback loops or tests would need to seed more extreme activation patterns that push some nodes toward saturation.

**Status:** #1171 landed with accessor correctness tests (mapping from `SettlingResult` to `CapsTrajectory`) and both-agents-present assertions. Trajectory divergence assertion deferred — not achievable without topology changes.

## Key Finding: Nearly All Derivation Paths Are Facet-Modulated

Issue #1079's field classification test revealed that the resting-vs-effective partition in `EffectiveCognitiveDefaults` is much narrower than expected. Almost every derivation path in `CognitiveDerivationEngine` accepts PID-5 facets as input and modulates its output accordingly:

| Classification | Fields |
|---------------|--------|
| **Displacement-independent** (3) | personality, personalityDominance, temporalFocus |
| **Displacement-sensitive** (8+) | appraisalWeights, habituationConfig, recurringThoughtConfig, cbrStrategy, moodBaseline, curiosity, socialCognition, graphStructure, extractionBias, capacityConfig |

The displacement-independent fields are only those derived from disposition profile terms (weighted Jungian functions) or goals alone. Everything else — including fields assumed to be "axes-only" like `cbrStrategy` — uses facets. For example, `deriveCbrStrategy` modulates `minSimilarity` via IMPULSIVITY and `decayDays` via RISK_TAKING.

**Implication:** Situational personality displacement (transient facet shifts from stress/flow/social mode engagement) ripples through CBR retrieval parameters, graph inference style, extraction bias, mood baseline, and curiosity weights — not just the emotion-adjacent fields like appraisalWeights. The `restingFor()` / `forAgent()` split in `EffectiveCognitiveDefaults` is load-bearing for CBR similarity scoring: without it, the CBR engine would use situationally-distorted parameters for case matching.

## Key Finding: Re-Tick Is Safe at 300 Ticks

Issue #930 validated the post-DERIVED mood/drive re-tick (#919) at scale. When appraisal is enabled, `CognitionCore.tick()` ticks mood and drives twice per cognitive cycle — once in FOUNDATION, once post-DERIVED after appraisal emotions propagate. The concern was cumulative drift: double mood decay, double drive EMA smoothing.

300-tick scenario with varied emotional stimuli (interest/distress/joy/fear cycling every 10 ticks) across two personality profiles:

| Metric | Result |
|--------|--------|
| Mood bounded [-1, 1] | ✅ All three PAD axes |
| No monotonic drift | ✅ First/last window delta < 0.5 |
| Drive intensities bounded [0, 1] | ✅ All 4 axes |
| Re-tick motivation vs baseline | ✅ Within 50% |

**Why it works:** `clampAxis` bounds mood to `baseline ± maxDisplacement`, preventing walk-past regardless of decay frequency. Drive EMA's asymmetric smoothing (instant on upswing, alpha=0.3 on down-ramp) means the double-tick effect only manifests as slightly faster deactivation, bounded by the next activation signal.

**Code review finding:** The initial drift assertion checked pleasure and arousal but omitted dominance — the axis most likely to drift under repeated appraisal emotion projections (high agency in positive emotions, low agency in fear/distress). Fixed before landing.

## Key Finding: Orchestrator Key Format Inconsistency

The evictIdle work (#1183) exposed that the 8 orchestrators don't agree on ConcurrentHashMap key format:

| Format | Classes |
|--------|---------|
| `agentId:tenantId` | DriveOrchestrator, MoodOrchestrator, GutFeelingParticipant, NarrativeOrchestrator, SearchBudgetTickParticipant, StrategyLearningOrchestrator |
| `agentId\|tenantId` | GoalProposalOrchestrator |
| `tenancyId:agentId` | InnerLifeOrchestrator |

No functional bug — each class's evictIdle uses the same key format as its tick method. But a generic eviction helper would need per-class key translation.

## Key Finding: EdgeInferencePhase Regex Fragility

Issue #1190 added edge-case tests for `EdgeInferencePhase.parseRelationships()`, exposing known limitations of the regex-based JSON parsing approach.

The `RELATIONSHIP_PATTERN` regex assumes fixed key ordering (`source → target → type`). Seven edge-case tests now document this:

| Scenario | Result |
|----------|--------|
| Standard key order | ✅ Matches |
| Reordered keys (type before source) | ❌ No match — silent data loss |
| Escaped quotes in entity names | ❌ No match — `[^"]+` can't handle `\"` |
| Empty relationships array | ✅ Returns empty list |
| Truncated JSON | ✅ Returns empty list |
| Multiple relationships | ✅ Extracts all |
| Mixed valid/reordered | ✅ Extracts only valid entries |

**Risk:** The system prompt instructs the LLM to produce `source, target, type` order, so the regex works in practice. But LLM output is non-deterministic — a reordered response would silently produce zero edges with no error or warning. The regex approach is a known fragility point; a JSON parser would eliminate all three failure modes (key order, escaped quotes, structural robustness).

## Finding: Embedding-Based Candidate Identification

Issue #1191 extended `EdgeInferencePhase` with an optional second candidate identification layer using `DenseEmbedder` cosine similarity.

**Problem addressed:** The co-occurrence approach only discovers entity pairs that appear together in the same experience memory. Conceptually related entities from separate contexts (e.g., two art movements discussed in different passages) are invisible to co-occurrence.

**Approach:** After co-occurrence candidates are built, remaining `maxCandidates` slots are filled by embedding-based candidates: each under-connected node's text representation (name + traits + property values) is embedded via `DenseEmbedder.embedBatch()`, pairwise cosine similarity is computed, and pairs above the configurable threshold (default 0.7) are added.

**Design decisions:**
- Co-occurrence takes priority (stronger signal from shared context)
- Embedding fills remaining capacity, not a separate allocation
- Config-gated: `embeddingCandidatesEnabled=false` by default (requires DenseEmbedder on classpath)
- Node text uses in-graph data only (name + traits + properties) — no additional store queries
- Follows ExperienceConsolidationPhase's `Instance<DenseEmbedder>` graceful degradation pattern
