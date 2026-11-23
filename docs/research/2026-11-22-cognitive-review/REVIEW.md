# Neocortex cognitive review — full findings

Date: 2026-11-22. Reviewed at `c3230fd` (branch `claude/magical-sagan-5e2uum`, identical to main). Nothing in any repo was changed.

Scope: `cognition`, `cognition-api`, `cognitive-api`, `cognitive-index`, `cognitive-eidos`, `caps-*`, `mindmap-intelligence`, the cognitive parts of `mindmap-*` and `memory-*`, plus `docs/research`, `docs/eval`, the eval tests, and the open issues on `casehubio/neocortex`. Wiring was checked against `casehubio/agency` at `f54bcae`.

How to read the status column:

- **V** — I checked the code path myself, or reproduced it by running code.
- **R** — reported by a reviewer who read the code path end to end; I did not re-verify it. Line numbers in R items are approximate.

Tests: the existing suites pass locally (cognition 1,348; cognitive-index, caps-engine and mindmap-intelligence 1,354 combined). Every defect below is therefore one the tests do not catch.

---

## A. Wiring: what production actually runs

| # | Finding | Where | Status |
|---|---|---|---|
| A1 | `CognitionConfig.all()` leaves appraisal, salience, behavioural (CAPS + gut feeling), regulation, coping, situational personality, entity knowledge, perspective comparison and domain activation **off**. `agency` uses `all()` in both its Quarkus and Spring wiring. `wacky-manor` starts from `all()` and switches on appraisal, salience and regulation from its own config; behavioural, coping and situational personality stay off there too. Only tests use `withAllSubsystems()`. | `cognition-api/.../core/CognitionConfig.java:72`; `agency/.../AgencyBeans.java:347`; `agency-spring/.../AgencyAutoConfiguration.java:332` | V |
| A2 | No production caller, in neocortex, agency or wacky-manor, for `configureSearchBudget`, `configureCuriosity`, `configureTheoryOfMind`, `configureProactiveRegulation`, `configureBehavioralSynthesis`, `configurePipelineSnapshotStore`, `configureTenantRegistrar`, `setMoodPersister`, or cache eviction. `CognitionTestHarness` calls several of them directly, so tests validate a pipeline production never assembles. | `cognition/.../core/CognitionCore.java`; `CognitionCoreFactory.java`; `CognitionTestHarness.java:510-531` | V |
| A3 | One `CognitionCore` is shared by all agents (application-scoped in agency; one instance for all characters in wacky-manor), but it keeps `lastAgentId`, `lastTenantId`, `lastDescriptor`, `lastBriefing`, `lastConsolidationArtifacts`, `lastContinuumMap` as instance fields. `promptSections()` takes no agent argument and reads them. Agent B can receive agent A's soft constraints, attention briefing, consolidation artifacts and arousal-based tier filter. | `CognitionCore.java:119-137, 781-870, 1380` | V |
| A4 | agency calls `core.promptSections()` once per websocket session, before the first tick, and reuses that list for every turn. Sections that are added conditionally on per-tick state (attention, consolidation, temporal focus, reflection, soft constraints, tier filter) are frozen at session-open state. | `agency-core/.../CognitionAvatarAdapter.java:51-53`; `speech-ws/.../SpeechWebSocket.java:143` | V |
| A5 | agency ticks on a 30-second wall-clock timer and never passes an observation. `AppraisalTickParticipant` returns early on a null observation, so appraisal would have nothing to appraise even if A1 were fixed. `wacky-manor` does pass an observation on each scenario tick, so this row applies to agency only. Dynamics expressed "per tick" (habituation, fatigue, drive smoothing, situational pull) therefore run on timer rate, and double if one agent has two sessions. | `SpeechWebSocket.java:172-186`; `AppraisalTickParticipant.java:159-166` | V |
| A6 | `CognitionCoreFactory` always calls `entityRendering(...)`, which wraps every section in `EntityRenderingSection`. `DirectiveSection.wrap` looks up directives and block tags by `getClass().getSimpleName()`, so with `directivePrompts` on, every section gets the generic "Consider this context in your response:" and loses its tag and sort order. | `CognitionCoreFactory.java`; `prompt/DirectiveSection.java:105-112` | V |
| A7 | Builder order: `configureGutFeeling` runs before `configureCognitiveMemoryRetriever`, so the gut-feeling participant captures a null retriever and never uses mood- or personality-modulated retrieval. | `CognitivePipelineBuilder.java:288-311`; `CognitionCore.java:1023` | V |
| A8 | `configureRegulation()` resolves the COGNITIVE subgraph id once at build time for `lastTenantId`, which is null then, so it looks up tenant `"default"`. Real tenants get no persistence or the wrong subgraph. | `CognitionCore.java:929-938` | V |
| A9 | `RecurringThoughtPhase` has `@Priority(25)` but no scope annotation and no producer, so it is never discovered. It is the only producer of `ConsolidationArtifact`, so the artifact channel and the fixation/obsession/rumination prompt modulation never fire. | `mindmap-intelligence/.../consolidation/RecurringThoughtPhase.java:31` | V |
| A10 | Classpath cognitive profiles and declarative rules load only from `file:` URLs; inside a packaged jar they are skipped silently. Tests run from `target/classes` and pass. | `cognitive-index/.../CognitiveDefaultsRegistry.java:108`; `DeclarativeRuleRegistry` | V |
| A11 | Several orchestrators have no CDI scope or producer in this repo (they come from agency); `TemporalFocusOrchestrator` and `ReflectionRetrievalOrchestrator` have no main-code implementation here. `NarrativePipeline`, `MemoryHygieneScheduler`, `SocialNormDetector` are never ticked. | `CognitionCoreFactory.java` | R |
| A12 | `safeRun` logs every failure as one generic WARNING with no per-capability counter, which is how a per-tick failure can go unnoticed. | `CognitionCore.java:1504` | V |
| A13 | `CognitionConfig` is a record of 27 positional booleans; presets are written as 27-argument constructor calls. | `CognitionConfig.java:43-74` | V |

---

## B. CAPS (personality network)

Reproduced on the real Java engine (`InMemoryCapsEngine`, default topology). Reproducer: `CapsRepro.java` in this directory (compile and run against the `caps-testing` classpath).

| Input | Attractors reported | Target node | Top of ranking |
|---|---|---|---|
| none | 14 | — | social_support_seeking 0.844, cautious_approach 0.778, social_sharing 0.735, attentional_deployment 0.696 |
| physical_threat = 1.0 | 15 | flight 0.597 → 0.616 | same four |
| betrayal = 1.0 | 15 | trust 0.500 → 0.467 | same four |
| reward = 1.0 | 18 | approach 0.571 → 0.670 | same four |
| rejection = 1.0 | 15 | withdraw 0.611 → 0.611 | same four |

- Maximally warm input (secure attachment, acceptance, responsiveness all 1.0): trust 0.571, withdraw 0.579, flight 0.597.
- Threat vs reward inputs: 19 and 21 attractors, 14 shared.
- Opposite personalities under social threat: largest settled difference 0.18.
- 30 fear events: `social_threat→BIS_activation` weight +0.420 → −0.837; BIS under threat 0.712 → 0.340.

| # | Finding | Where | Status |
|---|---|---|---|
| B1 | Unipolar nodes sit at `logistic(0) = 0.5` with zero input and transmit `0.5·w` downstream, so a constant background swamps the situation. Output is close to situation-invariant. | `caps-engine/.../CapsSettler.java:91-95, 245-264` | V (run) |
| B2 | The weight update is `Δw ∝ (outcomeValence − w·a_source)` per connection. There is no sum over cues, the target node does not appear, and the teaching signal is one global valence. Fixed point is `w = valence/activation` for every outgoing link of an active source. It is not Rescorla-Wagner, and it inverts threat pathways. | `caps-engine/.../CapsWeightUpdater.java:44-66` | V (run) |
| B3 | Regulation and coping call `capsEngine.updateWeights(...)`, which returns a new state, and discard the result; `saveState` is never called. The learning is a no-op. Wiring it up before fixing B2 would corrupt weights. | `cognition/.../regulation/EmotionRegulationTickParticipant.java:379, 475` | R |
| B4 | Disposition "resting activations" are only the initial condition (which decays away) and a constant added to reported strength after gating. They are not a bias in net input. Personality differences in ranking come mostly from these added constants. | `CapsSettler.java:47-49, 256-260`; `DispositionWeightMapper.java` | R |
| B5 | Cognitive distortions add a positive constant regardless of sign, so they pull negative beliefs toward zero; discontinuous gates create a limit cycle. | `CapsSettler.java:188-221` | R |
| B6 | The windowed convergence rule accepts a variance of 0.05, so persistent oscillation is reported as `CONVERGED`. | `CapsSettler.java:148-165` | R |
| B7 | The topology is almost entirely feed-forward (one small cycle, no lateral inhibition, no symmetric weights). "Attractor" means "output more than 0.05 above resting". | `caps-topology.yaml` | R |
| B8 | Nine output nodes and five mediating nodes have no incoming connections. The loader validates nothing. | `caps-topology.yaml`; `CapsTopologyLoader.java` | R |
| B9 | `ClassificationOutcomeEvaluator` scores a classification by convergence × max attractor strength. Given B1, B4 and B6 this is roughly a per-agent constant, and one outcome is written to every case in a batch. It is fed back as CBR confidence. | `mindmap-intelligence/.../consolidation/ClassificationOutcomeEvaluator.java` | R |
| B10 | CBR cache: on a fuzzy hit the case id is recomputed from the query, not taken from the hit; the lookup filters on fewer fields than the hash; 0.85 embedding similarity does not preserve polarity. | `caps-situation-cbr/.../CbrCachingSituationDecorator.java:113-149` | R |
| B11 | `RuleBasedSituationClassifier.classify()` returns empty; the keyword logic is in an unused method. | `caps-engine/.../RuleBasedSituationClassifier.java:26-33` | R |
| B12 | `BehavioralSynthesisPhase` passes mediating and output node ids as inputs; `settle` ignores them, `updateWeights` does not. Each attractor becomes a `CapsGenerated` node rendered into the prompt with no top-k or contradiction filter. Default disposition is hardcoded (#1204) although the real one is already injected. | `BehavioralSynthesisPhase.java:160-166, 217-231, 498-531` | R |
| B13 | Disposition modifiers: threshold multipliers act on a default of 0; several axis values used by callers have no YAML entry and are ignored silently; PID-5 facets never reach the engine. | `DispositionWeightMapper.java:112-246` | R |
| B14 | `LlmSituationClassifier` reuses session id `classifier-<agentId>`, so the first system prompt wins and history accumulates; failed nodes are still marked processed. | `caps-llm-classifier/.../LlmSituationClassifier.java:77-94` | R |
| B15 | "Pearce-Hall precision" is a ×0.9 / +0.1 step, not the published rule; EMPIRICAL protection decays to the floor after about 44 surprising updates; weights are unbounded. `inhibitory`, `decayResistance` and `generation` are never meaningfully updated. | `CapsWeightUpdater.java:56-78` | R |
| B16 | No topology or disposition version in persisted state (#415); whole-state replace with no optimistic lock. | `SqliteCapsEngine.java` | R |
| B17 | Tests do not constrain behaviour: no settler test, `assertAttractorPresent("approach")` passes on the no-input background. | `caps-testing`, `caps-engine/src/test` | R |

---

## C. Affect: appraisal, mood, drives, regulation

| # | Finding | Where | Status |
|---|---|---|---|
| C1 | On a tick with no observation the appraisal participant returns without clearing its last result. `CognitionCore` then re-bridges that stale result to mood and drives, and regulation re-reads it, every tick. | `AppraisalTickParticipant.java:159-166`; `CognitionCore.java:396-405` | V |
| C2 | Mood treats emotion PAD as an additive delta with no rate limit, then clamps. ALMA pulls mood toward the active-emotion centre over time. A few strong emotions pin mood at the clamp; with a 4-hour decay constant nothing recovers at conversational pace. | `mood/MoodOrchestrator.java:83-96` | V |
| C3 | Decay is applied after the new signal is added, using time since the last updating tick. After a long idle gap a fresh shift is immediately decayed away. | `MoodOrchestrator.java:98-113` | V |
| C4 | `GoalEmotionMoodBridge` adds standing goal emotions (for example HOPE on every active goal) as a delta on every tick. | `mood/GoalEmotionMoodBridge.java` | R |
| C5 | The regulation participant sets `cognitiveResourcePool = null` in its only real constructor. Depletion is always 0, the fatigue gate cannot fire, effort and recovery are never recorded. | `regulation/EmotionRegulationTickParticipant.java:196` | V |
| C6 | Coping drive modulations are overwritten: `recordAppraisalModulation` does `put`, and `CognitionCore` calls it again with emotion deltas for the same key. | `drive/DriveOrchestrator.java:147`; `CognitionCore.java:401-404` | V |
| C7 | Drive personality modulation uses `values.getFirst().weight()` and ignores which pole the term names. "Cautious" raises curiosity exactly as "bold" does. Tests only use high-pole terms. | `drive/DriveComposer.java:119-123` | V |
| C8 | `drives.tick` runs twice per core tick, so deactivation smoothing is applied twice (retention 0.49, not the documented 0.7) and every drive source is evaluated twice. | `CognitionCore.java:376, 415` | R |
| C9 | `CuriosityDrive` and the other drive sources keep `lastGaps`, `lastChannel` and similar in single fields on a shared instance; gap detection ignores the agent id. | `drive/CuriosityDrive.java:47-50` | R |
| C10 | `DriveAdaptationPhase` cursor compares random UUIDs lexically; it is not agent-safe; `current*(1+delta)` cannot recover from 0. | `drive/DriveAdaptationPhase.java:83-103, 155-171, 256` | R |
| C11 | Derived per-agent inputs are placed in the appraisal context but never read by `LlmAppraisalStrategy`: appraisal weights and habituation config. Habituation only counts exact-text repeats, and an LLM failure wipes its history. | `appraisal/LlmAppraisalStrategy.java:145-150` | R |
| C12 | Regulation and coping never change numeric affect; `regulatedIntensity` is not what gets bridged to mood or drives. Effectiveness is self-reported by the same LLM call and then drives learning, capacity and avoidance goals. | `regulation/*` | R |
| C13 | Regulation gating: only emotions within about ±0.014 of intensity 0.3 skip, so a 0.02 JOY triggers an LLM call; no valence check, so high JOY or PRIDE is "regulated" too. | `EmotionRegulationTickParticipant.java:222-232` | R |
| C14 | `EmotionMapper`: INTERRUPTION fires on low novelty at intensity `1 − novelty` (so 1.0 when novelty is missing); HOPE requires novelty; no path produces pride, relief, gratitude, admiration, satisfaction or disappointment. | `appraisal/EmotionMapper.java:59, 122-124` | V (interruption), R (rest) |
| C15 | `AlmaPadTable`: RELIEF dominance is −0.4 (Gebhard 2005 has +0.4); LOVE carries ALMA's Liking values; INTEREST is not in ALMA. | `cognitive-api/.../AlmaPadTable.java:23-28` | V |
| C16 | Concern salience: overlap is normalised by the longer text, so a 3-word goal against a 30-word observation scores at most 0.1 and the threshold is `> 0.1`. | `salience/ConcernBasedSalienceStrategy.java:48-52` | R |
| C17 | `CognitiveLoadEstimator` counts `drives().size()`, which is always 4 and equals the moderate threshold, so LOW is unreachable. | `regulation/CognitiveLoadEstimator.java:26-28` | R |
| C18 | Three different disposition vocabularies across the predictors, the drive source and eidos; unmatched terms fall to defaults. | `RegulationStrategyPredictor`, `CopingStrategyPredictor`, `MindMapDriveSource` | R |
| C19 | LLM session ids such as `llm-appraisal-<agentId>` and `mood-appraisal-<agentId>` are not tenant- or subject-scoped and reuse a multi-turn session. `lastMoodDelta` is keyed by agent id only. | `CognitionCore.java:158, 557, 645` | V (keys), R (session reuse) |
| C20 | `MoodOrchestrator.record` mutates an `ArrayDeque` outside the tick lock. Regulation results are never cleared or evicted. | `MoodOrchestrator.java:40-45` | R |
| C21 | Gut-feeling fallback scans affect memories tenant-wide with no agent filter and effectively restates current mood. | `gut/GutFeelingParticipant.java:106-107` | R |

---

## D. Personality derivation and cognitive-index

| # | Finding | Where | Status |
|---|---|---|---|
| D1 | The derivation engine keys on its own vocabulary (risk `conservative/calculated/bold`, social `independent/cooperative/competitive`, autonomy `low/moderate/high`). eidos canonical terms differ (`measured`, `collaborative`, `directed/semi-autonomous/autonomous`, Thomas-Kilmann conflict terms). Unrecognised terms fall through to neutral. Test fixtures use the engine's private vocabulary, and so do the wacky-manor character files, except `cautious` (2 uses) and `accommodating` (2 uses), which the engine does not recognise. | `cognitive-index/.../CognitiveDerivationEngine.java:53-185`; eidos `vocab/ConscientiousnessTerm.java` | V |
| D2 | Fear-onset threshold: anxiousness raises the threshold and the consumer fires fear when `urgency > 0.3 × threshold`, so anxious agents need more urgency to feel fear. The unit test asserts the inverted direction. | `CognitiveDerivationEngine.java:688-689`; `HeuristicGoalAppraisal.java:64` | V |
| D3 | The base fear threshold is derived from Sensing/Intuition via Openness. Fear and threat sensitivity load on Neuroticism, which MBTI does not measure. `SCALE_FACTOR = 1.5` is back-fitted. | `CognitiveDerivationEngine.java:625-696` | R |
| D4 | Derived values with no consumer in neocortex: mood baseline (mood uses the global 0,0,0), curiosity category weights, temporal-focus weights, CBR strategy, extraction bias, inference style, habituation domain modulation. Some map keys are upper-case while subgraph types are lower-case. | `CognitiveDerivationEngine.java`; `MoodOrchestrator.java:42` | R |
| D5 | Jungian profile → `PersonalityWeights` cannot change a ranking: queries are single-domain, so the domain weight is the same constant for every result. | `ModulationFactors.java:58` | R |
| D6 | Goal-relevance factor returns 1.0 / 0.7 / 0.4 / 0.0 at 1–4 hops but 1.0 for unreachable nodes, and runs a BFS inside a sort comparator. | `GoalRelevanceModulationFactor.java:39, 100` | R |
| D7 | Recency decay captures `Instant.now()` once in a singleton producer, so memories created after startup never age. `exp(-t/h)` is not a half-life (0.37 at `h`). | `cognition/.../CognitionDefaultBeans.java:291-294`; `ModulationFactors.java:19-27` | V |
| D8 | `RetrievalModulator` sorts by the product of factors and discards the store's relevance order. | `cognitive-api/.../RetrievalModulator.java:14` | R |
| D9 | DTW "correlation" is `1/(1 + mean distance)` with no normalisation, on series whose empty buckets are dropped, so flat or anti-phase series score STRONG. | `PadDtw.java:61`; `DomainActivation.java` | R |
| D10 | Circular-shift significance test: no +1 correction, 200 draws from n−1 distinct shifts, guard is only n < 3. Not valid below about n = 20. | `PadDtw.java:118-146` | R |
| D11 | Per-agent affect trajectories read the same ownerless history for every agent, so social-comparison alignment is always 1.0. | `CognitiveProfile.java:155`; `AffectEvents.java:32` | R |
| D12 | Privacy: visibility passes when the caller is null; `compare` returns any listed agent's private view; overlay load caps at 1000 per tenant unordered; profiles and rules are keyed by agent id without tenant. | `PrincipalVisibility`, `PerspectivalResolver`, `EffectiveCognitiveDefaults.java:166` | R |
| D13 | `updateBase` is called on every tick, bumping the version and running two full derivations, so the cache does nothing. | `CognitionCore.java:305-308` | V (call), R (cost) |
| D14 | Absent facets are neutral (0.5) for numbers but treated as LOW for expression styles. | `CognitiveDerivationEngine.java:1226-1229` | R |
| D15 | A neutral personality does not reproduce defaults; capacity derivation can only penalise. | `CognitiveDerivationEngine.java:321-345` | R |
| D16 | Mood baseline mapping is invented, not Mehrabian 1996 (and is unconsumed, D4). | `CognitiveDerivationEngine.java:389-417` | R |
| D17 | "Openness" and "Extraversion" proxies share inputs; effective personality dimensions are about three. | `CognitiveDerivationEngine.java:807-1192` | R |
| D18 | `RelationshipProcessor` reads only metadata for the target agent and ignores the typed `Action.targetAgent`. | `memory-core/.../RelationshipProcessor.java:35` | R |
| D19 | `CognitiveMemoryRetriever` holds one shared mutable tick context. | `CognitiveMemoryRetriever.java:24`; `CognitionCore.java:330` | V |

---

## E. Higher cognition

| # | Finding | Where | Status |
|---|---|---|---|
| E1 | Mental-model, user-model and strategy orchestrators drain their buffer, then check "enough signals in this drain" plus a cooldown, and drop the signals if the check fails. With one signal per tick the LLM synthesis never runs. | `MentalModelOrchestrator.java:186, 311-320`; `UserModelOrchestrator.java`; `StrategyLearningOrchestrator.java:185-213` | V (gate), R (drain) |
| E2 | Interaction quality is derived from the agent's own mood, read before the exchange has been applied; the user message is unused; negative-high-arousal maps to NEUTRAL. This feeds the user model and disposition evolution. | `CognitionCore.java:613-628` | V |
| E3 | Tick-path Theory of Mind feeds the agent's own previous-tick appraisal into the engine and stores the same posterior for every subject. The engine is a three-node chain, not Baker-style inverse planning. `configureTheoryOfMind` has no caller. | `CognitionCore.java:363-373`; `tom/TheoryOfMindBridge.java` | V (call site), R (engine) |
| E4 | Mental-model confidence decay multiplies the stored value by `0.5^(elapsed since lastReinforced)` each tick without advancing the reference, so decay compounds. | `MentalModelOrchestrator.java:283-299` | V |
| E5 | Relational-schema trust re-integrates the full memory history on every run with no cursor. In practice it is inert because valence is read from a field production never sets. | `relationship/RelationalSchemaEvolutionPhase.java:112-183` | R |
| E6 | Disposition evolution's only pressure sources map POSITIVE to the dominant function and NEGATIVE to the auxiliary, using E2's quality. The gating machinery around it is sound. | `personality/InteractionPressureSource.java:27-40` | R |
| E7 | Narrative pipeline partitions by tenant, not agent. | `narrative/ReflectionEventAdapter.java:37-39` | R |
| E8 | Memory hygiene: two constant terms keep the retention composite at or above 0.333, above both the eviction threshold (0.1) and the low-retention threshold (0.3), so nothing is evicted and the adaptation phase ratchets one way. | `memory/MemoryHygieneOrchestratorImpl.java`; `RetentionScore.java:14-27` | V (formula), R (thresholds) |
| E9 | Hygiene grouping compares each memory's query score to the threshold, not pairwise similarity. | `MemoryHygieneOrchestratorImpl.java:231-247` | V |
| E10 | Goals: tick results are discarded; cached proposals are not cleared on no-change; the deductive strategy is called with empty context; `queryGoals` returns every goal in the tenant with no agent filter; each surfacing writes two self-generated experience memories. | `goal/GoalProposalOrchestrator.java`; `goal/CognitiveGoalOrchestrator.java:119-160` | R |
| E11 | Regex JSON parsing in mood appraisal and BDI extraction: breaks on `]` or escaped quotes in text; `extractDouble` rejects a leading `+` although the prompt shows `+0.3`; success is counted even when nothing parsed; on LLM failure the raw utterance is stored as a belief at 0.8. | `CognitionCore.java:666-713` | V |
| E12 | Sub-thoughts that mention an entity are recorded as that entity's belief, desire or intention. | `subthought/SubThoughtTickParticipant.java:72-84` | R |
| E13 | Inner-life "silence" is measured from the last tick, not the last user activity. | `innerlife/InnerLifeOrchestrator.java:166-173` | R |
| E14 | Situational personality pull is applied per tick with no dt; `baselineFor()` returns 0.5 for every facet. | `situational/PersonalityStateIntegrator.java:86-180` | R |
| E15 | Tier filter: soft constraints are in the SUPPLEMENTARY tier and are dropped when arousal ≥ `1 − personalityDominance`; sections added by later customizers bypass the filter. | `prompt/TierFilterCustomizer.java` | V |
| E16 | Belief revision only erodes; one cursor per tenant advances even when an agent's processing threw. Disabled by default. | `belief/BeliefRevisionPhase.java:75-130` | R |
| E17 | Reflection synthesiser discards any heuristic without a resolvable source turn id, silently. | `reflection/LlmReflectionSynthesizer.java:268-279` | R |

---

## F. Consolidation and MindMap intelligence

| # | Finding | Where | Status |
|---|---|---|---|
| F1 | Memory cursors use `memory_id > ? ORDER BY memory_id` over `UUID.randomUUID()` ids on SQLite and JPA. After the first catch-up most new experiences are skipped for graduation, sub-thought accumulation and goal recognition. In-memory sorts by creation time, so tests pass. | `memory-sqlite/.../SqliteMemoryStore.java:110, 301-302`; `memory-jpa/.../JpaMemoryStore.java` | V (SQLite) |
| F2 | `GoalAffectPhase` stores a synthetic "OCC emotion: …" experience and emits `AFFECT_CHANGE` for every goal on every consolidation pass, with no change detection. These crowd the graduation budget and corroborate themselves. | `consolidation/GoalAffectPhase.java:150-170` | V |
| F3 | Cross-tenant leakage in the attention path: one phase's `signals()` does not drain; the accumulator and mediator are keyed by principal id only. | `SubThoughtConsolidationPhase.java:87`; `CognitiveAttentionAccumulator.java:34-119` | R |
| F4 | SQLite `search` applies `LIMIT` before the trait filter with no `ORDER BY`, and inner-joins on subgraph; two phases pass a subgraph *type* as a subgraph id. Crystallised curiosity prose is never found on SQLite. | `mindmap-sqlite/.../SqliteMindMapStore.java:706-777`; `CuriosityDescriptionGenerationPhase.java:170` | R |
| F5 | `beginTickAllPhases` swaps global access counts and resets every tenant's significance counter even when only some tenants run. | `ConsolidationScheduler.java:222-224, 343-349` | R |
| F6 | Sub-thought consolidation is inert: the extractor always sets entity to null and the phase skips null entities. | `SubThoughtExtractor.java`; `SubThoughtConsolidationPhase.java:124` | R |
| F7 | Graduation: a later non-blocked memory advances the cursor past a corroboration-blocked one; actions and outcomes have no subject and always pass; the graduated node is the first 100 characters of raw text. | `ExperienceConsolidationPhase.java:165-224` | R |
| F8 | Curiosity channels are near-constant by construction (focus is DIVERSIVE whenever there are five or more gaps; comprehension ≈ 0.5, gap proximity ≈ 1.0). | `CuriosityChannelComputer.java:57-95` | R |
| F9 | `CuriosityRefreshPhase` computes signals and discards them; subgraph priority yields types where consumers expect ids. | `CuriosityRefreshPhase.java:28` | R |
| F10 | Attention signals are level-triggered: overdue goals, decayed linked goals and flagged merge pairs re-signal on every pass. | `GoalPrioritizationPhase.java:137-209`; `MergeDetectionPhase.java:113-120` | R |
| F11 | Auto-merge at `0.6·JaroWinkler + 0.4·neighbour Jaccard ≥ 0.9` merges "John Smith" and "Joan Smith" when they share their only neighbour. Merge is irreversible and conflicts are discarded. Goal merge at JW ≥ 0.90 collapses "Write chapter 1" and "Write chapter 2". | `MergeDetectionPhase.java:96-153`; `GoalResolutionPhase.java` | R |
| F12 | Confidence decay is one 30-day half-life for every node and edge; per-type half-life is never read and nothing refreshes the decay reference. A stated fact is at 0.06 after 120 days. | `mindmap-core/.../ConfidenceDecayDecorator.java:107-113` | V |
| F13 | Information-gap score is `H(confidence) × log(degree)`, where confidence is an origin constant times age decay, so it measures provenance and age. | `InformationGapDetector.java:76-108` | R |
| F14 | `RetrievalStrength` has no production caller; access is recorded on creation, not retrieval. | `RetrievalStrength.java`; `ConversationBridge.java` | R |
| F15 | Goal decay skips any goal with positive pleasure; active goals default to HOPE, so undiscussed goals never go dormant. | `GoalPrioritizationPhase.java:160-209` | R |
| F16 | Community summaries are `"Summary: " + first three names`; nothing reads them. | `CommunitySummaryPhase.java:169-177` | R |
| F17 | Tenants are discovered once at startup; `registerTenant` has no caller (see A2). The idle tracker is global. | `ConsolidationScheduler.java:137-201` | R |
| F18 | Per-pass cost is about 10–12 store round-trips per node per tenant. | `CuriositySignalGenerator`, `MindMapAnalyzer` | R |
| F19 | Extraction: every paragraph becomes a STATED 1.0 node; fuzzy name resolution at JW ≥ 0.85 attaches edges to the wrong entity; a hand-rolled JSON parser. | `ConversationBridge.java`; `MindMapExtractor.java`; `ExtractionJsonParser.java` | R |
| F20 | Derived-edge truth maintenance is in memory only and lost on restart. | `mindmap-core/.../DerivedEdgeDecorator.java` | R |

Default-deployment status of consolidation phases (R):

| Phase | Effectively active? | Note |
|---|---|---|
| AccessFrequency | Runs, no effect | Counts creations; nothing reads it |
| SurfacingAggregation | Only if fed | No writer of its input in this repo |
| ExperienceConsolidation | Yes | Stalls on SQLite/JPA (F1) |
| SubThoughtConsolidation | No | F6 |
| EdgeInference | Off by default | Build property |
| MergeDetection | Yes | F11 |
| RecurringThought | Never runs | A9 |
| SchemaDiscovery | Rarely | F9 |
| CuriosityDescription | Runs | Output invisible on SQLite (F4) |
| CommunitySummary | Runs | F16 |
| GoalResolution | Partly | Expand and sync have no-op SPIs |
| GoalAffect | Yes | F2 |
| GoalPrioritization | Priority yes, decay no | F15 |
| CuriosityRefresh | No-op | F9 |
| GoalRecognition | No | Recognizer has only a no-op implementation |

---

## G. Evidence base

Three levels of evidence, as the docs themselves imply:

1. **Internal numbers differ by personality.** Strong: 43 deterministic divergence tests, added in era 8. Before that, the era-8 doc says 6 of 48 capabilities had such a test.
2. **The rendered prompt differs.** Weak tests: `isNotEqualTo`, section counts.
3. **The LLM behaves differently, in the intended direction, versus a baseline.** Thin.

What level 3 shows (from `docs/research`, R unless marked):

| Comparison | Result | Source |
|---|---|---|
| Cognition vs pure briefing, with directives | Baseline 9/9/9 vs cognition 8/8/8; single run, same-model judge | `2026-09-14-measurement-driven-cognition-findings.md` (single-run caveat V at line 324) |
| Full pipeline vs hand-written brief | 3.57–3.68 vs 3.93; after the metric was redefined, 3.92 vs 3.88 | `CHARACTER-EMERGENCE-FINDINGS.md`, `era4-measurement-methodology-research.md` |
| Paired probes with a no-cognition control (n = 4) | Belief change 5/5; mood 1/5 ("functionally dead", V at line 89) | `pre-era-proving-emergence-2026-09-28.md` |
| Long runs, 300 → 900 events | Scheming drive expression 5.00 → 3.60 and 4.44 → 2.95 | `CHARACTER-EMERGENCE-FINDINGS.md` |
| Five relational/ToM sections added | Register regressed to 3.13 | `era5-relational-cognition-research.md` |

There is no ablation of CAPS, regulation, curiosity, search budget, ToM or recurring thought against a cheap persona prompt.

Admissions in the project's own docs (V = I located the line):

- "Run-to-run variance is unmeasured." (`era4-measurement-methodology-research.md:139`, V)
- "Pipeline improvements … redistribute quality but don't create new quality — architectural ceiling." (`CHARACTER-EMERGENCE-FINDINGS.md:1097`, V)
- "Mechanical mood (CAPS→PAD) empirically unviable." "BDI extraction was 100% failure rate." (`era3-cognitive-layering-research.md:1602-1603`, V)
- "All multi-agent scenario runs to date had cross-agent cognitive contamination." (`2026-11-05-post-era6-regulation-and-identity.md:618`, V)
- Descriptive load state "was ignored by the LLM"; what worked was "You MUST choose it." (`2026-11-11-process-constraint-regulation-findings.md:14, 24`, V)
- CAPS regulation "always settles to ATTENTIONAL_DEPLOYMENT … near-identical CAPS profiles across 5 different personalities." (R; consistent with section B)

Method problems (R):

- Judge is the same model family, sees the named element and criterion, is not blind; no human raters, no cross-vendor judge.
- Most LLM evals are N = 3 with "at least 2 of 3".
- Constants were tuned on the scenarios later reported; files are named `…-calibrated.json`, `…-rebalanced.json`, `…-pinned.json`.
- Nearly all behavioural work uses one five-character cartoon cast and one contrastive pair.
- The biography corpus is LLM-extracted, LLM-enriched and LLM-verified, and is scored on field coverage, not accuracy.

Literature use (R unless marked):

- `cognitive-pipeline-defaults.properties` says "Every property has a default grounded in published research" (V, line 3). Most citations are real and conceptually apt; most numeric values are not from the cited work.
- Likely wrong: Rescorla & Wagner 1972 cited as *Psychological Review* (it is a book chapter) and as giving α = 0.3–0.4; "nodes fire at midpoint (Mischel & Shoda 1995)"; "Barrett (2017) 4-channel"; the ALMA values in C15.
- Could not be verified: Petter et al. 2025, Yang et al. 2025, Firth et al. 2024, arXiv:2601.10025, arXiv:2607.20803.
- No DOIs or URLs anywhere in `docs/research`.

---

## H. Direction

GitHub state: 88 open issues, no milestones. Twelve carry the `epic` label, nine of which propose new theories: #591 Bayesian ToM, #593 cognitive dissonance, #594 moral foundations, #597 habit vs deliberation, #598 prospect theory, #600 active inference, #602 temporal discounting, #603 social influence, #604 defence mechanisms.

Open issues that already point at the right problems: #475 (drift detection), #390 (prose vs JSON rendering), #386 (long-horizon simulation), #391 (prompt-effectiveness feedback), #722 (pipeline cost), #1062 (consolidate CAPS settles), #1102 (relationship quality always NEUTRAL), #1204, #1205, #415.

Recommended order of work:

1. **Make production and tests run the same pipeline.** One composition path used by both; a startup report of which capabilities are live; an integration test that builds from the factory and asserts each enabled capability emits a section after scripted turns (A1–A13).
2. **Decide the agent model.** Either one core per agent, or key all `last*` state by agent and tenant and add `promptSections(agentId, tenantId)` (A3, A4, C9, D19).
3. **Fix what corrupts state today.** F1 (cursor), F2 (goal-affect spam), C1 (stale appraisal), E4 (compounding decay), F11 (auto-merge), F12 (uniform confidence decay), D2 (fear sign), D1 (vocabulary).
4. **Measure before building more.** In this order:
   - Noise floor: 10 or more identical-config repeats to get the judge and generator variance.
   - Four-arm ladder on held-out scenarios, judge blind to arm: character sheet only; sheet plus a natural-language memory, belief and relationship summary; sheet plus one cheap "inner state" LLM call; full pipeline. The pipeline has to beat the second and third arms.
   - Leave-one-out ablation per subsystem using the existing config flags; remove or park anything with no effect.
   - A cross-vendor judge and a small blind human panel on pairwise items.
   - A drift benchmark over 500–1,000 turns with hostile or deceptive characters.
   - Cost per arm (LLM calls and seconds per agent-tick).
5. **Rebuild or retire CAPS** depending on what step 4 shows. As implemented it does not produce situation-dependent output (section B).
6. **Hold the nine theory epics** until step 4 has run.

What is good and worth keeping: the candid negative-results log; the paired-probe design with a no-cognition control; the per-stage cognitive journal; provenance tags on weights; the deterministic divergence suite as a regression net; the disposition-evolution gating and ledger; wall-clock mood decay; the scheduler's single-thread and per-phase isolation; the species-topology plus per-agent overlay design.

---

## I. Recorded run data from `casehubio/examples` (added after the main review)

**Read this section with one caveat:** the owner confirms the wacky-manor runs date from around era 4–6, and that later cognitive work has not yet been wired into wacky-manor. The data below describes the pipeline as it ran then. It counts as evidence about today's code only where the same code is still in place, which was confirmed for the additive mood rule and the CAPS settler. A re-run after wiring, with the cognitive journal captured per tick, is needed for a fair assessment of the current model.

Source: `wacky-manor/docs/eval` at `7e7c9a5`. The 300-tick transcripts hold only dialogue, actions and thinking text, with no per-tick cognitive state. Two other files do hold state, and both match predictions from the code review. All of this is V (computed from the files).

**CAPS output is the same for nearly every character (confirms B1, B4, B12).** `era6-memory-snapshot/era6-phase2-*.json`, five characters:

- All five have the same 25 CAPS-generated node ids.
- Four of the five, including the scheming villain Vincent, have the same top patterns at almost the same strengths:

| Character | Top CAPS patterns (strength) |
|---|---|
| Clara | cautious_approach 0.996, trust 0.995, proximity_seek 0.970, fawn 0.895, flight 0.891 |
| James | cautious_approach 0.997, trust 0.982, proximity_seek 0.969, flight 0.911, fawn 0.894 |
| Brixton | cautious_approach 0.998, trust 0.972, proximity_seek 0.972, flight 0.918, withdraw 0.915 |
| Vincent | cautious_approach 0.997, proximity_seek 0.970, trust 0.970, withdraw 0.912, fawn 0.895 |
| Reginald | approach 1.000, fight 0.998, risk_take 0.972, assert 0.972, explore 0.898 |

- Strengths sit near 1.0, which the prompt renders as "deeply ingrained".

**Mood pins at the clamp (confirms C2).** `cognitive-snapshots-2026-10-07.jsonl`, 300 ticks over about 3 hours, 60 snapshots per character, `max-displacement=1.0`:

| Character | Mood (P, A, D) at tick 10 → 25 | Snapshots with all three axes at ±1 |
|---|---|---|
| hooded-claw | (0.61, 0.30, 0.15) → (0.99, 0.99, 0.99) | 56 of 60 |
| dick-dastardly | (−0.09, 0.09, −0.06) → (−0.99, 0.99, −0.99) | 20 of 60 |
| ant-hill-mob | (0, 0, 0) throughout | 0 of 60; mood never moved |

In the same file, character drives and goals are identical in every snapshot for every character across the 300 ticks. This file predates later mood changes, but the additive rule is still in `MoodOrchestrator`.

**Long-run homogenisation (crude lexical check, indicative only).** I counted "warm" words (good night, thank you, friend, love, sorry, together, tomorrow …) per 1,000 words by fifth of each character's speech:

| Run | Character | First fifth | Last fifth |
|---|---|---|---|
| `300-tick-opus-baseline-20261028` (2,300 events) | dick-dastardly | 4.9 | 21.4 |
| | hooded-claw | 7.6 | 23.6 |
| | peter-perfect | 4.8 | 24.9 |
| | ant-hill-mob | 7.2 | 22.6 |
| | penelope-pitstop | 13.1 | 21.4 |
| `300-tick-clean-opus-20261029` (851 events) | hooded-claw | 5.9 | 5.3 |
| | dick-dastardly | 3.6 | 5.6 |

In the 2,300-event run all five characters converge on about the same rate, villains included. In the 851-event run the villains hold. A keyword count is not a behavioural measure; it only says the drift result in #475 is visible in this data and is worth measuring properly.

**Sample size of the scored eval.** `300-tick-opus-baseline-20261028/full-eval.json` scores 30 of the 2,300 events, six per character.

**Other things the examples repo shows:**

- `era6-memory-snapshot/FINDINGS.md` records that runtime experiences were stored in domain `experience` but recalled from domain `manor`, so characters could not recall their own experiences, and that seeding ran twice (duplicated nodes).
- `wacky-manor` builds its own orchestrators by hand in `ManorCognitivePipelineBuilder`, so it is a third composition of the pipeline alongside `CognitionCoreFactory` and the test harness.

---

## Not covered

- `inference-bayes` beyond its use in ToM; CBR internals; RAG, corpus and knowledge-pipeline modules (out of scope).
- `casehub-mosaic` (cognitive workbench) could not be cloned, so any wiring there is unknown; A1 and A2 are stated for neocortex, agency and wacky-manor only.
- I did not run wacky-manor or any LLM-in-the-loop scenario; section I is analysis of the committed files.
- The ~50 LLM-in-the-loop eval classes under `caps-llm-classifier/src/test` were read for method, not run.
- Reviewers listed files they did not reach; the main ones are the situational-personality YAML and `ShadowArchetypePromptSection`, `NarrativeContentSummariser`, the drive→goal mappers, and the Mem0 and Graphiti backends.
- GitHub issues were read from the public web pages; the API was not available for `casehubio/neocortex` in this session.
