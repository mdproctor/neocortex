# Post-Era 6: Regulation, Identity, Search Budget, and Pipeline Completion

**Period:** 2026-11-01 → 2026-11-13
**Status:** Standalone gap document — to be incorporated into era 7 when that era is defined
**Predecessor:** Era 6 — Cognitive Memory Architecture (Phase 3 completed 2026-11-01)

---

## Overview

Era 6 completed the three-layer memory architecture (leaf/derived/hot-read) on 2026-11-01. The work that followed falls into eight distinct streams, all advancing in parallel across ~160 commits. Three streams are arguably era-scale: intrapersonal emotion regulation (Gross 1998 process model with Bosse 2010 fatigue and Petter 2025 personality correlations), the cognitive search budget (a general model for personality-shaped effort allocation grounded in Guilford 1967, DeYoung 2015, Frijda 1986, and Pirolli & Card 1999), and the recurring thought continuum (curiosity → fixation → obsession → rumination as a unified mechanism grounded in Nolen-Hoeksema 1991, Ehring & Watkins 2008, and Litman 2008).

## Stream 1: Intrapersonal Emotion Regulation

**Issues:** #590 (parent), #815, #816, #817, #818, #820
**Commits:** ~23 across neocortex + examples
**Literature:** Gross 1998, Gross 2015, Bosse et al. 2010, Petter et al. 2025
**Blog:** "The Gap Between Feeling and Acting" (2026-11-04)

### What was built

The regulation system adds a decision layer between appraisal (feeling an emotion) and action (expressing it). Five strategies from Gross's process model — situation selection, situation modification, attentional deployment, cognitive change, response modulation — are available per tick. Strategy selection is LLM-driven with structured context from the literature models.

| Component | Issue | What it does |
|-----------|-------|-------------|
| `EmotionRegulationStrategy` + `EmotionRegulationResult` | #590 | Data model — five Gross strategies, intensity tracking, environmental directives |
| `EmotionRegulationGenerationPhase` | #590 | LLM call with Gross/Petter/Bosse system prompt, JSON response parsing |
| `EmotionRegulationTickParticipant` | #590 | DERIVED-phase participant, proportional activation gate (Bosse 2010) |
| `EmotionRegulationGoalParticipant` | #590 | TERMINAL-phase participant, creates DriveGoalProposals for environmental strategies |
| `EmotionRegulationMindMapAdapter` | #590 | Persistence and history retrieval (last 5 results per agent) |
| `CognitiveLoadEstimator` | #815 | Per-tick cognitive load proxy (drives + goals + ToM targets) |
| `RegulationFatigueTracker` | #817 | Bosse Eq. 8-9 — fatigue accumulation and recovery |
| `RegulationMonitor` | #820 | Gross 2015 control loop — effectiveness decline detection, strategy failure signals |
| `RegulationStrategyPredictor` | #818 | Emotion-type × personality strategy prediction — constrains (not replaces) LLM generation |
| EmotionMapper intensity fix | #816 | Removed redundant controllability multiplier from ANGER/FEAR intensity |

### Architecture

Regulation is a unified decision system with distributed execution. One participant selects from all five strategies via LLM. Internal strategies (distraction, reappraisal, suppression) modify drive rendering — a suppressed drive gets somatic cost text. Environmental strategies (situation selection, situation modification) create DriveGoalProposal instances that enter the goal pipeline.

The predictor (#818) provides a **strategy constraint** to the LLM generation phase, not a replacement for it. When personality and emotion type together predict a strategy with high confidence, the predictor pins the strategy and the LLM generates the embodied narrative for that strategy — emergent from personality signals through Barrett four-channel rendering. The predictor decides HOW to regulate; the LLM generates WHAT that feels like. No templates, no briefings — 100% emergent narrative.

The predictor uses categorical DispositionAxes values (set membership, not keyword matching) combined with OCC emotion type to determine strategy. Emotion type encodes controllability: ANGER = controllable (agent can act), FEAR = uncontrollable (agent is powerless), SHAME = self-evaluative (failed own standards), REPROACH = other-directed (someone else failed). Contextual overrides handle extreme states: high intensity → attentional deployment, high cognitive load → low-cost strategies only, high fatigue → attentional deployment.

**Quality gate:** LLM-judge plausibility scoring. Phase 2 (calibrated): Mean: 4.4/5, Shame depth: 5.0/5, Gate: 4.0. 11 cases (8 original + 3 shame-specific). 8 of 11 cases stable 4+. Three boundary cases (mixed-anger, compounding-shame, suppressor-shame) swing 3-4 across runs — under investigation.

### Cascade dynamics

Four subsystems compose into emergent behaviour: cognitive load biases strategy selection, fatigue degrades regulation capacity over time, the monitor detects declining effectiveness and signals strategy switches, and the LLM synthesises all signals into behaviour. The suppression-breakthrough moment — when sustained regulation exhausts capacity and emotion breaks through — emerges from the model rather than being scripted.

### Key finding: intensity compression

The EmotionMapper's three-factor product for anger (`relevance × |conduciveness| × controllability`) systematically compressed intensities below the regulation operating range. Controllability was being double-counted — the if/else already uses it to gate emotion type. The fix removed the redundant factor, aligning all negative emotions to a two-factor base (`relevance × |conduciveness|`). Without this fix, regulation was structurally unreachable for anger.

### Key finding: silent failure in LLM response parsing

The appraisal strategy passed raw LLM response to Jackson. Claude's session mode wraps JSON in markdown code fences. Jackson threw on the backtick prefix, caught silently, returned empty. The regulation generation phase already handled this (extracts JSON between `{` and `}`). A one-line fix to the appraisal strategy. The lesson: `catch (Exception e) { return empty(); }` makes wiring bugs indistinguishable from "nothing to produce."

### Key finding: LLM strategy selection is non-deterministic

Back-testing the predictor against LLM regulation revealed that the LLM produces different strategies for identical inputs across runs. Same avoidant disposition + same public confrontation scenario → RESPONSE_MODULATION in one run, ATTENTIONAL_DEPLOYMENT in the next. This makes LLM-agreement an unreliable quality gate — the reference itself is unstable.

The solution: replace agreement-based testing with LLM-judge plausibility scoring. A separate LLM acts as a psychology expert and scores whether each prediction is appropriate for the given personality + emotion combination (1-5 scale). This evaluates whether the predictor's choice is psychologically defensible, not whether it matches a non-deterministic LLM's arbitrary selection.

### Key finding: personality coherence determines predictability

Personalities with mixed signals — avoidant conflict BUT flexible rules, competitive orientation BUT avoidant conflict — produce inconsistent judge scores (2-3) regardless of which strategy is predicted. The judge correctly detects the tension: "flexible rules" and "independent" enable reappraisal, while "avoidant" pushes toward suppression. When a personality pulls in two directions, the resolution depends on situational nuance that the predictor doesn't have access to.

Coherent personalities — all signals pointing the same direction — score 4-5 consistently. A coherent suppressor (cooperative, moderate, conservative, low, avoidant) with no CC-enabling traits scores RM at 4/5 for anger. A coherent reappraiser (cooperative, strict, calculated, high, analytical) scores CC at 5/5 for anger.

The predictor enforces coherence: `isSuppressor` requires suppressor signals ≥ 3 AND CC-enabling signals < 2. `isReappraiser` requires CC-enabling signals ≥ 3 AND suppressor signals < 2. Mixed-signal personalities fall through to the LLM. CC-enabling traits: analytical, strict, cooperative, high autonomy, bold, flexible, independent.

### Key finding: shame is mechanically unresolvable for suppressors

Across 7 LLM judge runs with 3 different strategies, no strategy scores ≥ 4 for suppressor + shame:

| Strategy | Score | Judge reasoning |
|----------|-------|----------------|
| RESPONSE_MODULATION | 2-3 | "Shame festers when suppressed — suppressing without reframing the self-judgment is less effective even for avoidant types" |
| COGNITIVE_CHANGE | 2-5 | Unstable — depends on whether judge weights avoidant (blocks CC) or flexible/independent (enables CC) |
| ATTENTIONAL_DEPLOYMENT | 3 | "Avoidance reflex, second-choice coping mechanism" |

Shame is a self-evaluative emotion: the agent failed their own standards. The only healthy regulation engages with the self-evaluation (CC). But a coherent suppressor personality — defined by the absence of CC-enabling traits — cannot execute CC. The personality's natural strategy (RM) is psychologically counterproductive for this specific emotion.

This is the one personality × emotion combination where no mechanical prediction is reliably appropriate. The LLM handles it by reading the specific situation — what standard was violated, how public the failure was, what social context applies.

## Stream 2: Entity Identity Infrastructure

**Issues:** #799, #808, #809, #813, #795, #798
**Commits:** ~24

Replaced the annotation-index approach with inline entity tokens (`{{entity_id:variant:surface_form}}`). Built the entity-token module: `EntityIdentity` value type, `SurfaceFormTrie` (Aho-Corasick with word-boundary filtering), `EntityTokeniser`/`EntityRenderer`, `TokenFormat` parser, CDI cache with trie management, `SqliteEntityReferenceIndex`.

Alter ego resolution (#813) handles personas — a character known by different names in different contexts. `PersonaVocabulary` registers persona-of edges. `EntityIdentityCache` discovers personas via graph traversal. `EntityRenderer` uses dot-notation for alter ego rendering.

`RetokenisationPhase` (#809) runs during consolidation to update tokens in stored memories when entity references change. Required adding `updateText()` SPI to `CaseMemoryStore` across all three implementations (InMemory, JPA, SQLite).

## Stream 3: Cognitive Pipeline Wiring

**Issues:** #732, #733, #735, #750, #752, #753
**Commits:** ~18
**Blog:** "Signal Starvation" (2026-11-03), "The Plumbing Is Done" (2026-11-02), "Formation Memory Grounding" (2026-11-03)

This stream addressed the signal starvation problem identified in the CAPS audit: 9 of 14 personality data types were hitting dead ends inside the pipeline. The data existed at each stage; the connections between stages didn't.

Key wiring:
- **CognitiveProfile injection** (#732): Domain activation correlation fed into P22 relational context for entity-grounded drive descriptions
- **MentalModelLookup SPI** (#733): Exposed mental model data (relationships, reflections, ToM) to prompt sections that previously couldn't access it
- **Formation memory grounding** (#735): Restructured SYSTEM_PROMPT to use formation memory body echo as the organizing principle. Somatic rendering instructions per formation memory in the user prompt
- **CognitiveMemoryRetriever integration** (#750): Tick-scoped context added to user model synthesis, mental model BDI extraction, strategy reflection, gut feeling, and narrative content summarisation
- **Biography annotation processing** (#752): Expanded to all entry types with batch entity resolution
- **cognitive-eidos bridge** (#753): `EidosCognitiveProfileBridge` — derives CognitiveDefaults from AgentDescriptor (DispositionAxes → derivation pathways)

## Stream 4: Corpus Management

**Issues:** #785, #786, #792, #800, #801
**Commits:** ~12

Domain abstraction audit (#785) validated corpus-management as genuinely domain-neutral across six use-case lenses: clinical, NPC dialogue, garden journal, market research, research literature, federated search. Example module scaffold (#786) with test harness covering all six scenarios. Naming cleanup (#800) and type consolidation (#801): `BuiltInCorpusTypes`, recency decay fix.

## Stream 5: Infrastructure

**Issues:** #760, #762, #774, #777, #766, #492
**Commits:** ~10

- **ZIP corruption fix** (#760): Synchronized `ZipCorpusStore` append/delete to prevent concurrent modification
- **Observability interfaces** (#762): Pipeline-wide observability SPIs
- **Non-blocking consolidation** (#774): `consolidateNow()` submits to background executor instead of blocking the caller
- **Barrett channel alignment** (#766): Drive description prompt channel names aligned to Barrett framework
- **Spatial provider fix** (#777): Shared `Coordinates` type between connectors-api and location-spi
- **SubThought cleanup** (#492): Stale accumulation cleanup in `SubThoughtConsolidationPhase`

## Stream 1 Continuation: Regulation Predictor Quality (#830)

**Issues:** #830, #820
**Dates:** 2026-11-06 → 2026-11-07
**Literature:** Barańczuk 2019 (meta-analysis), Hughes et al. 2020, Webb et al. 2012, Nathanson 1992, Yang et al. 2025
**Blog:** "When Personality Isn't Enough" (2026-11-05), "When the Predictor Lets Go" (2026-11-06), "The Weight of Evidence" (2026-11-07)

### Phase 1: LLM guidance for predictor fall-throughs (complete)

Two conditional sections added to `EmotionRegulationGenerationPhase.buildUserMessage()` when the predictor returns `Optional.empty()`:

1. **Disposition axes injection** — the LLM receives the five personality axes with explicit "predictor did not pin a strategy" framing
2. **Self-conscious emotion guidance** — when dominant emotion is SHAME/REMORSE, the LLM receives literature-grounded temporal containment guidance (Webb 2012, Nathanson 1992, Yang 2025)

**End-to-end eval results** (new `RegulationEndToEndEvalTest` — runs full predictor → LLM → judge pipeline):

| Metric | Baseline (predictor-only) | Phase 1 (end-to-end) |
|--------|--------------------------|---------------------|
| Cases scored | 5 of 8 | 8 of 8 |
| Strategy mean | 5.0 (pinned only) | 4.4 (all cases) |
| Fall-through mean | not measured | 3.7 |
| Temporal containment | not measured | 5.0 |

Phase 1 proved: (a) the LLM with personality context handles fall-throughs reasonably, (b) temporal containment for suppressor-shame works, (c) mixed-anger (3) is the weakest case — signal for Phase 2.

### Phase 2: Continuous weights + shame dimensions (implemented + calibrated)

Full literature review at `docs/research/2026-11-07-regulation-continuous-weights-literature-review.md`. Boolean `isSuppressor`/`isReappraiser` classification removed entirely. Three-layer architecture:

**Layer 1 — CDE RegulationDefaults:** `deriveRegulationDefaults(DispositionAxes)` produces continuous `(reappraisalCapacity, suppressionCapacity, redirectTendency)` from Barańczuk 2019 meta-analytic correlations. Weight tables calibrated post-eval: avoidant→suppression reduced 0.25→0.10 (avoidant redirects, doesn't suppress well per N→suppression r=0.09), flexible→reappraisal increased 0.10→0.15 (Openness r=0.20), flexible→redirect inverted +0.05→-0.10 (flexibility = approach, not avoidance).

**Layer 2 — ShameContext:** Assembled per-tick for SELF_CONSCIOUS emotions. Three dimensions: memory compounding (episode count), relational closeness (person overlay), personality-gated depth (grandiosity × callousness).

**Layer 3 — Interpretive rendering:** Capacity summary + ShameContext sections + controllable-emotion temporal staging + surface-level RM pin.

**Calibration discoveries:**
- **Viable alternative threshold (0.40):** For controllable negative emotions, don't pin RM when personality has reappraisalCapacity >= 0.40. Prevents forcing suppression when reappraisal is viable.
- **Temporal staging:** Avoidant personalities with reappraisal capacity regulate in sequence: impulse management first (personality-congruent avoidance), then cognitive flexibility engages for sustained reappraisal. Fixed incoherent-anger from 3→4.
- **Surface-level RM pin:** When grandiosity >= 0.7 AND callousness >= 0.7, override to RM. The judge consistently scored CC at 2-3 because externalization is modulation, not genuine reappraisal. Fixed surface-shame from 2→5.
- **Variance as signal:** Cases scoring 5 (suppressor-anger, reappraiser-anger, anyone-fear, reappraiser-shame) NEVER vary across 6 runs. Cases scoring 3-4 ALWAYS vary. Variance indicates the model boundary, not noise.

**End-to-end eval (post-calibration, 6 runs):**

| Metric | Phase 1 | Phase 2 (calibrated) |
|--------|---------|---------------------|
| Cases scored | 8 | 11 (3 shame-specific added) |
| Strategy mean | 4.4 | 4.4 |
| Shame depth | — | 5.0 |
| Floor | 3 (mixed-anger) | 3 (boundary cases) |
| Stable 4+ cases | 5 of 8 | 8 of 11 |

**Remaining boundary (3-4 across runs):** mixed-anger ("betrayal resists reappraisal"), compounding-shame ("serial suppression failing"), suppressor-shame ("AD viable over RM"). Filed #844 for regulation prediction feedback loop.

## Stream 6: Cognitive Search Budget

**Issues:** #825, #544, #784
**Commits:** ~12 across neocortex
**Literature:** Guilford 1967, De Bono 1970, DeYoung 2015, Frijda 1986, Charnov 1976, Pirolli & Card 1999, Mischel & Shoda 1995, Davis & Nolen-Hoeksema 1998, Schmidt & DeShon 2007, Louro et al. 2007, Anthropic 2026
**Blog:** "How Much Should an Agent Think?" (2026-11-05)
**Design spec:** `specs/issue-765-cognitive-richness/2026-11-05-cognitive-search-budget-design.md` (8 decisions, 22 references)

### The problem

Multiple cognitive subsystems produce motivation intensity (curiosity, goal pursuit, threat response, competence drive, relational obligation). There was no general model converting that intensity into a search envelope the LLM operates within. A cautious agent and a bold one approach the same information gap identically — no personality-shaped exploration style.

### What was built

A 4-dimensional search budget model that converts motivation intensity into a personality-shaped exploration envelope. The four parameters — breadth, laterality, depth, convergence pressure — are each orthogonal, independently grounded in the literature, and driven by distinct personality traits.

| Component | Module | What it does |
|-----------|--------|-------------|
| `SearchBudgetConfig` | cognitive-api | 10-field per-parameter logistic activation tuning with `defaults()` factory |
| `SearchMotivation` enum | cognition-api | 6-value source enum (CURIOSITY, COMPETENCE, AFFILIATION, AUTONOMY, THREAT, GOAL) with `fromDriveAxis()` |
| `SearchBudget` record | cognition-api | 4D envelope: breadth, laterality, depth, convergence + source + timestamp |
| `BlockTag.SEARCH_BUDGET(415)` | cognition-api | Strategy tier, between STRATEGY(410) and ATTENTION(420) |
| `deriveSearchBudgetDefaults()` | cognitive-index (CDE) | PID-5 facets + DispositionAxes → personality-derived config baseline |
| `CognitiveDefaults` field 18 | cognitive-index | `searchBudgetConfig` with `withSearchBudgetConfig()` builder |
| `SearchBudgetTickParticipant` | cognition | DERIVED-phase participant computing numeric budget from personality baseline + transient state |
| `SearchBudgetPromptSection` | cognition | Renders from crystallised prose + fallback per-motivation programmatic rendering |
| `CognitionCore.configureSearchBudget()` | cognition | Wires participant + section, registers after appraisal + gut feeling for ordering |
| `SearchBudgetDescriptionGenerationPhase` | mindmap-intelligence | @Priority(28) LLM prose crystallisation following DriveDescriptionGenerationPhase pattern |

### Architecture: two-layer pattern

Follows the established consolidation + tick pattern. Layer 1 (consolidation, background): LLM generates contextual cognitive-state prose describing the agent's default search disposition, crystallises to MindMap COGNITIVE subgraph. Layer 2 (tick, per-interaction): participant computes numeric budget from CDE-derived personality baseline + transient state (drives, mood, appraisal, gut feeling). Prompt section renders from modulated result + crystallised prose.

### Key design decisions (all literature-grounded)

**D1 — Four orthogonal parameters:** Guilford (1967) measures fluency (breadth) and flexibility (laterality) as independent cognitive operations. Depth and convergence are independent because fixation (depth=∞, convergence=never) requires them to vary independently.

**D3 — No custom composition:** The search budget reads already-composed outputs from upstream subsystems. CAPS connectionist resolution (Mischel & Shoda 1995), Frijda's control precedence (1986), and Schmidt & DeShon's discrepancy-based resource allocation (2007) are all already implemented in the drive, appraisal, and goal subsystems.

**D4 — PID-5 facets, not Big Five:** The derivation chain maps from PID-5 facets (Risk Taking, Impulsivity, Withdrawal, Suspiciousness, Anxiousness, Emotional Lability) and DispositionAxes directly. Big Five trait names serve as theoretical grounding but the operational pathway never constructs synthetic Big Five scores. Follows the pattern of `CognitiveDerivationEngine.deriveExpressionStyles()`.

**D5 — Per-parameter activation functions:** Logistic (sigmoid) for breadth and depth (appetitive, smoothly graded). Logistic with threat-collapse floor for laterality (Frijda approach/avoidance). Piecewise with pathological floor for convergence (Davis & Nolen-Hoeksema perseveration). Different shapes for different dynamics, following CapsSettler's BIPOLAR/UNIPOLAR precedent.

**D6 — Consolidation + tick rendering:** LLM generates prose at consolidation time (personality baseline changes slowly). Tick-time participant computes numeric values independently. Prompt section combines. Style grounded in Anthropic "Functional Emotions" (2026): cognitive state descriptions causally influence LLM behavior through internal representations.

### Derivation chain (personality → budget parameters)

| Parameter | Trait baseline (CDE) | Dynamic modulation (tick) |
|-----------|---------------------|--------------------------|
| **Breadth** | connectiveBias + Risk Taking (Openness/Intellect, DeYoung 2015, r=0.20 DT) | CURIOSITY boosts, positive mood boosts, GutValence.APPROACH boosts |
| **Laterality** | low Withdrawal + low Intimacy Avoidance + low Suspiciousness (DeYoung 2015 "leaky filtering") | CURIOSITY boosts, AUTONOMY boosts, threat collapses to 0 |
| **Depth** | ruleFollowing (strict→0.8) + low Impulsivity (Conscientiousness ↔ low Disinhibition, Krueger 2012) | COMPETENCE boosts, AUTONOMY boosts, AFFILIATION reduces |
| **Convergence** | ruleFollowing modulated by Anxiousness + Emotional Lability | COMPETENCE accelerates, AFFILIATION accelerates, CURIOSITY delays, pathological: Anxiousness + HabituationState failure → 0 |

### Pathological boundary

Not a separate mode — the natural consequence of maximal intensity with no resolution. Fixation: breadth≈0, laterality=0, depth=1.0, convergence=0 (maximal intensity + HabituationState failure). Rumination: identical numeric parameters but high observation count (same ground retraced). The distinction is captured in the consolidation-time LLM prose, not the numbers.

### Quality verification

Bold explorer vs cautious analyst produce meaningfully different configs: breadth midpoint 0.44 vs 0.54, laterality midpoint 0.38 vs 0.55, pathological threshold 1.76 vs 1.14. Sigmoid at k=6.0 produces clear threshold (0.047 at intensity 0.0, 0.500 at 0.5, 0.953 at 1.0). Drive modulations produce distinct profiles: curiosity maxes breadth, competence maxes depth, affiliation shallows depth and accelerates convergence, autonomy boosts laterality and depth.

Noted concern: at high intensity (>0.8), additive modulations compress against the ceiling — personality difference is clearest in moderate-intensity range. Calibration refinement, not structural flaw.

### Intelligence as capacity ceiling (#840 — complete)

DeYoung's Openness/Intellect split: Openness drives *motivation* to explore, Intellect drives *capacity* to explore. Halford's Relational Complexity bounds depth ceiling (how many reasoning hops). Mednick's associative reach bounds laterality ceiling (how far associations can bridge). Working memory span bounds breadth ceiling. CognitiveCapacityConfig (boxed Double, per-field merge) with CDE derivation from Jungian functions, compressed [0.55, 0.95] range. Hard ceiling after fatigue in tick pipeline (step 7). Capacity gap signal drives unified prose generation — constrained description prepends to base via composition, not exclusive priority. 27 tests across 4 batches. Key design insight: capacity is prompt-only by design (the echo approach) — the LLM inhabits the limitation rather than having it engineered as a constraint.

### Supporting issues on this branch

**#544 (OCC emotion vocabulary enforcement):** Added `EmotionType.normalise(String)` with 29 non-canonical aliases mapping to canonical OCC types. Unrecognised types dropped, aliases normalised. Addresses 12% LLM invention rate in emotion classification.

**#784 (Consolidation cache drift detection):** Added `ConsolidationCapability` record (requiredNodeKinds + producedTraits) to `ConsolidationPhase` SPI. `ConsolidationScheduler.manifest()` exposes pipeline snapshot with hash for cache invalidation.

## Blog Coverage

| Date | Title | Streams covered |
|------|-------|-----------------|
| 2026-11-02 | Don't Stop the World to Remember | Non-blocking consolidation, CLS theory |
| 2026-11-02 | The Plumbing Is Done | Pipeline hardening complete, cognitive richness frontier |
| 2026-11-03 | Signal Starvation | CAPS pipeline audit, 9/14 dead wires, three-layer personality |
| 2026-11-03 | Formation Memory Grounding | Somatic echo, formation memory in prompts |
| 2026-11-04 | The Gap Between Feeling and Acting | Full regulation deep dive |
| 2026-11-05 | When Personality Isn't Enough | Predictor coherence, shame mismatch |
| 2026-11-05 | When the Model Invents Feelings | OCC vocabulary enforcement, consolidation drift |
| 2026-11-05 | She Said — But Who Is She? | Entity identity, alter ego resolution |
| 2026-11-05 | How Much Should an Agent Think? | Search budget model, literature grounding, quality verification |
| 2026-11-07 | Why Your AI Agent Isn't Curious | Information-gap curiosity, four-channel rendering, Loewenstein model |

## Stream 7: Information-Gap Curiosity

**Issues:** #596
**Commits:** 9 across neocortex
**Literature:** Loewenstein 1994, Golman & Loewenstein 2016, Litman 2005/2008, Silvia 2006/2008, Berlyne 1960, Kashdan 2018
**Blog:** "Why Your AI Agent Isn't Curious" (2026-11-07)

### The problem

CuriosityDrive used memory retention statistics — ratio of low-retention memories and consolidation group diversity. This produced flat, uninformative signals. The number had almost nothing to do with curiosity as a cognitive phenomenon. Retention is about forgetting; curiosity is about almost knowing.

### What was built

Replaced retention-based curiosity with Loewenstein's information-gap model. Binary entropy H(confidence) × attention weight from edge connectivity produces gap intensity that peaks at partial knowledge — the sweet spot where the agent knows enough to sense what's missing. Four rendering channels give the LLM qualitatively different curiosity descriptions based on personality and gap distribution.

| Component | Module | What it does |
|-----------|--------|-------------|
| `InformationGapScore` | mindmap-intelligence | Per-node gap score: entropy, edge count, confidence, GapType (PARTIAL_KNOWLEDGE/TIP_OF_TONGUE/CONNECTED_UNKNOWN/GOAL_ADJACENT) |
| `InformationGapDetector` | mindmap-intelligence | Scans MindMap for partial-knowledge sweet spots, goal-adjacent boosting, sorted by intensity |
| `CuriosityChannel` | cognition-api | Four-channel state: HedonicType (I_TYPE/D_TYPE/BLENDED), comprehension [0,1], FocusType (SPECIFIC/DIVERSIVE), gapProximity [0,1] |
| `CuriosityChannelComputer` | mindmap-intelligence | Derives channels from gap scores + personality-driven deprivation bias |
| `CuriosityDescriptionGenerationPhase` | mindmap-intelligence | @Priority(29) consolidation-time LLM prose through four channels, crystallised to COGNITIVE subgraph |
| `CuriosityPromptSection` | cognition | BlockTag.CURIOSITY(215), crystallised prose or programmatic four-channel rendering with baseline deviation and drive rank |
| `CognitionCore.configureCuriosity()` | cognition | Wiring method following configureSearchBudget() pattern |
| `CuriosityDrive` (rewritten) | cognition | Now consumes InformationGapDetector instead of MemoryHygieneOrchestrator |

### Architecture: same two-layer pattern as Stream 6

Layer 1 (consolidation): `CuriosityDescriptionGenerationPhase` at @Priority(29) generates personality-specific curiosity prose from the four channels. Layer 2 (tick): `CuriosityDrive` evaluates information gaps, `CuriosityChannelComputer` derives channels, `CuriosityPromptSection` renders with proportional context.

### Key design decisions

**D1 — Binary entropy for gap intensity:** Golman & Loewenstein (2016) formalised information-gap theory as entropy × attention weight. Binary entropy H(p) peaks at p=0.5 and is zero at extremes — directly encoding Loewenstein's non-linearity. Attention weight uses log₁₊₁(edgeCount) to reward connected nodes without unbounded growth.

**D2 — Four orthogonal channels:** Each channel is independently derived from different data (personality for hedonic, mean confidence for comprehension, distribution entropy for focus, mean gap entropy for proximity). The channels compose additively in prose rendering — no interaction effects to calibrate.

**D3 — Deprivation bias from AppraisalWeights.fearOnsetThreshold():** Lower threshold = more anxiety-prone = higher D-type curiosity. Simpler than a full personality-facet derivation and empirically grounded — neuroticism correlates with deprivation-type curiosity (Litman 2008).

**D4 — CuriosityChannelComputer in mindmap-intelligence:** Bridges InformationGapScore (mindmap-intelligence) and CuriosityChannel (cognition-api). The channel record is a pure value type in cognition-api; the computation that populates it needs gap data from the higher layer.

### Quality verification

33 new tests verify: personality differentiation (same gaps, different hedonic type), Loewenstein non-linearity (moderate confidence > low confidence > high confidence), Berlyne focus classification (single gap → specific, many equal → diversive), Silvia comprehension (high confidence gaps → interest, low → confusion), proportional baseline deviation, crystallised prose preference, entropy function symmetry and peak.

## Stream 8: Recurring Thought Continuum

**Issues:** #826 (complete — all 4 batches landed)
**Commits:** 11 across neocortex
**Literature:** Nolen-Hoeksema 1991 (rumination), Ehring & Watkins 2008 (repetitive negative thinking as transdiagnostic process), Litman 2008 (D-type curiosity at fixation boundary), Silvia 2008 (appraisal → confusion/anxiety), Eysenck 1967 (neuroticism × negative affect), Roberts et al. 2014 (conscientiousness × self-regulation), DeYoung 2015 (openness × attention fluidity)
**Design spec:** `specs/issue-841-cognitive-exploration/2026-11-07-recurring-thought-continuum-design.md` (7 decisions, light review — 12 findings addressed)

### The insight

Curiosity, fixation, obsessive thought, and depressive rumination are the same computational mechanism — recurring thought about a specific topic — differentiated by valence, regulation capacity, and whether the loop resolves. Not separate systems. Same substrate, different parameter regimes.

### What was built

A continuum classifier that derives an agent's position from seven symptoms, each scored from existing data:

| Component | Module | What it does |
|-----------|--------|-------------|
| `ContinuumPosition` enum | mindmap-api | HEALTHY → FIXATION → OBSESSION → RUMINATION |
| `RecurringThoughtAssessment` record | mindmap-api | Seven symptom scores + composite + position |
| `SignalCategory.THOUGHT_RECURRENCE` | mindmap-api | 12th signal category for attention pipeline |
| `RecurringThoughtConfig` record | cognitive-api | Symptom weights, thresholds, recurrence window, min observations |
| `CognitiveDefaults` field 19 | cognitive-index | `recurringThoughtConfig` with CDE 14th derivation pathway |
| `RecurringThoughtScorer` | mindmap-intelligence | Seven-symptom composite scorer — experience frequency, habituation failure, gap closure resistance, attentional capture, arousal trajectory, valence, action tendency |
| `RecurringThoughtPhase` @Priority(25) | mindmap-intelligence | ConsolidationPhase — discovers agents, scores topics, caches assessments, emits signals + artifacts |
| `ConsolidationArtifact.RecurringThoughtDetected` | mindmap-api | Sealed variant for prompt rendering pipeline |
| `SearchBudgetTickParticipant` pathologicalThreshold | cognition | Convergence collapses to 0 when arousalScore + habituationFailure > threshold |
| `CuriosityPromptSection` continuum modulation | cognition | FIXATION urgency, OBSESSION intrusive language, RUMINATION withdrawal rendering |
| `SearchBudgetPromptSection` continuum modulation | cognition | FIXATION circling-back, OBSESSION compulsive quality, RUMINATION collapsed output |
| `CognitionCore` artifact drain | cognition | Drains RecurringThoughtDetected → builds continuum map → passes to prompt sections |
| `AppraisalTickParticipant.currentHabituation()` | cognition | Public accessor for habituation state (used by search budget) |

### Architecture: pure derivation, zero new state

The continuum position is never stored — it's re-derived each consolidation cycle from existing data (experience memories, MindMap node PAD/confidence, CAPS attractors, attention signals). The classifier reads from five existing stores and produces a transient assessment.

Two complementary mechanisms:
- **Consolidation-level** (RecurringThoughtPhase): per-topic longitudinal classification. Derives ContinuumPosition from seven cross-tick symptom scores.
- **Tick-level** (pathologicalThreshold wire-up, Batch 3): global convergence collapse when anxiousness + habituation failure exceeds threshold. Agent-wide, not per-topic.

### Personality modulation

14th CDE derivation pathway maps PID-5 facets + DispositionAxes → RecurringThoughtConfig:
- High Anxiousness + Emotional Lability → lower fixation threshold (continuous: 0.9 anxiousness lowers by 0.08), higher arousal/valence symptom weights
- High ruleFollowing + low Impulsivity (conscientiousness proxy) → higher obsession threshold, lower attentional capture weight
- Low Withdrawal + low Suspiciousness + Risk Taking (openness proxy) → broader recurrence window (attention shifts more fluidly)
- High Withdrawal + Intimacy Avoidance (isolation) → lower rumination threshold (Nolen-Hoeksema 1991: social isolation amplifies circular thinking)

Two agents experiencing identical stimuli land at different continuum positions. Personality determines susceptibility.

### Key design finding: prompt pathway requires artifact plumbing

The design review (12 findings) caught that CognitionRenderContext doesn't carry continuum data, and prompt sections don't have access to CaseMemoryStore for re-derivation. Resolution: ConsolidationArtifact pathway → CognitionCore drains RecurringThoughtDetected artifacts → passes continuum map to prompt sections via constructor injection (same pattern as CuriosityDrive injection).

### Quality verification

26 new tests across 4 batches: scorer classification (9 tests), consolidation phase signals/artifacts (4 tests), pathological threshold convergence collapse (4 tests), continuum prompt rendering modulation (7 tests), integration tests — personality differentiation and scorer-to-rendering pipeline (3 tests). Full test suite green.

## Blog Coverage

| Date | Title | Streams covered |
|------|-------|-----------------|
| 2026-11-02 | Don't Stop the World to Remember | Non-blocking consolidation, CLS theory |
| 2026-11-02 | The Plumbing Is Done | Pipeline hardening complete, cognitive richness frontier |
| 2026-11-03 | Signal Starvation | CAPS pipeline audit, 9/14 dead wires, three-layer personality |
| 2026-11-03 | Formation Memory Grounding | Somatic echo, formation memory in prompts |
| 2026-11-04 | The Gap Between Feeling and Acting | Full regulation deep dive |
| 2026-11-05 | When the Model Invents Feelings | OCC vocabulary enforcement, consolidation drift |
| 2026-11-05 | She Said — But Who Is She? | Entity identity, alter ego resolution |
| 2026-11-05 | How Much Should an Agent Think? | Search budget model, literature grounding, quality verification |
| 2026-11-07 | Why Your AI Agent Isn't Curious | Information-gap curiosity, four-channel rendering, Loewenstein model |
| 2026-11-07 | When Curiosity Won't Let Go | Recurring thought continuum, fixation/obsession/rumination as unified CAPS dynamics |
| 2026-11-08 | Three Systems, One Exploration | Integration wiring: curiosity→search budget coupling, goal-directed curiosity |
| 2026-11-08 | Curiosity That Knows Where It's Going | Goal-directed curiosity: BFS proximity, goalRelevance scoring |
| 2026-11-08 | You Can't Unit-Test Emergence | Five-tier testing strategy, Tier 2 structural scenarios, Tier 3-5 gap |
| 2026-11-09 | When Personality Disappears Under Pressure | Extreme interaction: Yerkes-Dodson, situational strength, fatigue reallocation |
| 2026-11-09 | The Gap Between Wanting to Think and Being Able To | Intelligence capacity layer: Openness/Intellect split, capacity as ceiling |
| 2026-11-09 | The Test That Broke the Math | Per-modality eval findings: convergent fatigue, API gap, emergent monitor+fatigue |
| 2026-11-09 | Where the Why Lives | Pipeline observability: three layers, SPI architecture, temporal behavior demands |

## Stream 9: Cognitive Pipeline Observability (#520)

**Issues:** #520
**Dates:** 2026-11-09 (complete)
**Epic:** #513 (cognitive pipeline completion — diagnostic tooling section)
**Blog:** "Where the Why Lives" (2026-11-09)
**Design spec:** `specs/issue-851-dynamic-regulatory-capacity/2026-11-09-cognitive-state-dump-design.md` (5 decisions, light review — 3 findings addressed)

### The problem

The existing cognitive observability module (`CognitionApi` with `@McpDomain("neocortex/cognition")`) provides 12+ query endpoints — all observing the knowledge graph (MindMap nodes, edges, mutations, confidence). Zero visibility into the live cognitive pipeline state: drives, CAPS settling, appraisal, regulation, concern salience, prompt section rendering. After every tick, the computational trace — the chain of intermediate states explaining *why this behavior* — evaporates.

The eval work on #822 and #823 demonstrated why this matters: the convergent fatigue series, the null RegulationContext API gap, and the emergent monitor+fatigue interaction were all invisible without pipeline-level visibility. Transcripts show what happened; the pipeline trace shows why.

### What was designed

A three-component architecture extending the existing observability infrastructure:

**1. CognitionStateProvider SPI** (in `cognition-api`, package `io.casehub.neocortex.cognition.trace`): Interface for querying live pipeline state. CognitionCore implements it. Returns `PipelineTrace` — a record capturing the nine pipeline stages missing from `CognitionSnapshot`.

**2. PipelineTrace record** (in `cognition-api`): Nine fields covering the full pipeline: appraisalResult, concernSalience, capsInputActivations, capsSettling (inline `CapsSettlingSnapshot` record), regulationResult, channelWeights, habituation, activatedDriveCategories, promptSections. `CognitionSnapshot` gains `@Nullable PipelineTrace trace`.

**3. PipelineSnapshotStore** (interface in `cognitive-observability-core`, SQLite impl in `-sqlite`, in-memory impl in `-testing`): Persists `PipelineTrace` per-tick for historical replay. Separate from `SnapshotStore` because it stores transient computational state, not graph topology.

### Key architectural findings

**CAPS settling is consolidation-time, not per-tick.** `CapsEngine.settle()` is called exclusively from `BehavioralSynthesisPhase` during consolidation — not from `CognitionCore.tick()`. The attractors that influence per-tick behavior were computed during the last consolidation. A pipeline trace captures "what was the CAPS state when this tick ran," not "what CAPS computation happened during this tick." This changes how CAPS data is captured: `BehavioralSynthesisPhase` caches its last `SettlingResult` per agent; `CognitionStateProvider` queries it.

**Dependency cycles constrain type placement.** The original design placed `PipelineTrace` and `CognitionStateProvider` in `cognitive-observability-api`. This creates cycles through `cognitive-index`:
- `cognitive-observability-api → caps-api → cognitive-index → cognitive-observability-api`
- `cognitive-observability-api → cognition-api → cognitive-index → cognitive-observability-api`

Root cause: `cognitive-index` depends on `cognitive-observability-api`, blocking any dependency from `-api` to anything `cognitive-index` depends on. Resolution: moved both types to `cognition-api` (package `io.casehub.neocortex.cognition.trace`). Added `cognition-api` as a dependency of `cognitive-observability-core` (no cycle — `-core` is a leaf). `SettlingResult` reference replaced with inline `CapsSettlingSnapshot` record to avoid the caps-api cycle entirely.

**SPI preserves layer ordering.** Decision review (R1-04) caught that injecting `CognitionCore` into `CognitionService` creates an upward layer dependency (L23 → L25). The SPI pattern reverses this: `cognition-api` (L25) defines the contract, `CognitionCore` (L25) implements it, `CognitionService` (L23) consumes it. The dependency flows downward.

### What was built

| Artifact | Module | Status |
|---|---|---|
| `PipelineTrace` record (9 fields + `CapsSettlingSnapshot`) | cognition-api | ✅ |
| `CognitionStateProvider` SPI | cognition-api | ✅ |
| `CognitionSnapshot` + `@Nullable PipelineTrace trace` | cognition | ✅ |
| `PipelineSnapshotStore` interface + `PipelineSnapshot` record | cognitive-observability-core | ✅ |
| `InMemoryPipelineSnapshotStore` | cognitive-observability-testing | ✅ |
| `PipelineSnapshotStoreContractTest` (9 tests) | cognitive-observability-testing | ✅ (9/9 pass) |
| `SqlitePipelineSnapshotStore` with V2 Flyway migration | cognitive-observability-sqlite | ✅ (9/9 contract + 12 regression) |
| CAPS result caching (`lastSettlingResults`, `lastInputActivations`) | mindmap-intelligence (BehavioralSynthesisPhase) | ✅ |
| `CognitionCore implements CognitionStateProvider` | cognition | ✅ |
| Tick capture (PipelineSnapshotStore.store at end of tick) | cognition (CognitionCore) | ✅ |
| `CognitivePipelineDump` response type | cognitive-observability-core | ✅ |
| `pipelineDump()` @PlatformQuery endpoint | cognitive-observability-core (CognitionApi/CognitionService) | ✅ |
| CDI + Spring wiring (Instance<>/ObjectProvider<>) | cognitive-observability + cognitive-observability-spring | ✅ |
| `CognitionStateProviderIntegrationTest` (7 tests) | cognition | ✅ (7/7 pass) |
| Follow-up filed: #894 — pipeline coherence eval | — | filed |

## What's Next

### Epic #841 — remaining work
- **#865 Migrate cognitive tests to CognitionTestHarness**: Systematic migration of four test classes to the Tier 2 harness pattern established in #849. S/Low.
- **#866 Empirical quality testing**: LLM-judged evaluation of the full exploration pipeline across all layers. Covers curiosity channels, search budget differentiation, recurring thought continuum, pathological boundary, intelligence capacity. Follows `CognitiveInfluenceEvalTest` pattern. L/High.
- **#900 Capacity fidelity testing**: Does the LLM stay true to cognitive self-description when solving actual problems? The echo approach: describe the state, let the model figure out how to express it. Give problems of varying complexity to agents with different capacity profiles, judge fidelity to self-description rather than rule compliance. M/High.

### Completed since last update
- **#926 Test harness unification** ✅: Merged `CognitionCoreTestBuilder` into `CognitionTestHarness`. Always wires real orchestrators (UserModel, MentalModel, Strategy, Narrative, Goals, InnerLife, Hygiene) with orchestrator-backed drives. Added `withSessionPipeline()`. 18 call sites migrated across 9 eval test files. `CognitionCoreTestBuilder` deleted. 5 search budget tests adjusted for non-zero drive baselines. 886 cognition tests green.
- **#840 Intelligence capacity layer** ✅ (all 4 batches, 6 tasks): CognitiveCapacityConfig record, CognitiveDefaults field 21, SearchBudget fields 8-11 (gap values), CDE Jungian→capacity derivation pathway, capacity ceiling + gap signal in SearchBudgetTickParticipant, unified prose generation (SYSTEM_PROMPT_UNIFIED replaces dual-variant), capacity-constrained prose composition (prepends to base), 4 CognitionTestHarness capacity scenarios. 27 new tests. Design spec reviewed (standard, 3 rounds). Blog revised with prose composition model + practical takeaways. Deferred: #890 learning rate modulation, #891 convergence ceiling, #892 capacity→appraisal frustration. Created #900 capacity fidelity testing (echo approach). Fixed #901 (EmotionRegulationGenerationPhaseTest avoidant shame branch, closed).
- **#839 Personality at extreme intensity** ✅: Inverted-U activation (Yerkes-Dodson), modulation scaling (situational strength), fatigue reallocation (Hockey 1997). Three mechanisms: activationWithDecay, per-parameter amplification/decay rates, FatigueState per-agent accumulator. Deferred: #878 per-parameter peaks, #879 separate amplification threshold, #880 shared fatigue substrate.
- **#846 integration wiring** ✅: Curiosity→search budget coupling (#847), goal-directed curiosity (#848), CognitionTestHarness + 5 structural scenarios (#849). Wiring verified structurally (Tier 2). Empirical quality proof deferred to #866.
- **#596 Information-gap curiosity** ✅: Full Loewenstein model, four-channel rendering.
- **#826 Recurring thought continuum** ✅: Seven-symptom composite, HEALTHY→FIXATION→OBSESSION→RUMINATION, prompt modulation.
- **#829 Scenario validation** ✅: 95% prediction rate across BASELINE cast
- **#819 Proactive regulation** ✅: ProactiveRegulationParticipant with memory-driven anticipatory avoidance, temporal decay, personality-modulated thresholds
- **#805 Social regulation** ✅: 5 social strategies (SOCIAL_SUPPORT_SEEKING, SOCIAL_SHARING, SOCIAL_WITHDRAWAL, CO_REGULATION, DELIBERATE_PROVOCATION), targetAgentId, socialRegulationTendency derivation, interactingAgents gate, appendGoals routing. Deferred: distress trajectory enrichment, feature toggle, integration test
- **#822 Per-strategy eval** ✅: PerStrategyRegulationEvalTest with CognitiveDefaultsRegistry wired into CognitionCoreTestBuilder. Key finding: scenarios must produce moderate-intensity emotions (≤0.75) to avoid high-intensity ATTENTIONAL_DEPLOYMENT override. SITUATION_MODIFICATION requires LLM free choice (predictor cannot pin it)
- **#823 Per-modality eval** ✅: PerModalityRegulationEvalTest — 4 modality tests (fatigue, automatic concordance, monitoring escalation, proactive avoidance). Key findings: (1) Fatigue accumulation is a convergent geometric series (effort × 0.15 where effort decays with fatigue), not linear — 5 ticks = 0.30 fatigue, ~20 ticks needed for 0.7 override threshold. (2) CognitionCore.tick() API gap — RegulationContext was never passed through the pipeline, making both proactive read path and regulation MindMap write path dead code. Fixed with 7-arg tick() overload. (3) Monitor+fatigue combined effect: LLM responds to fatigue prompt by reducing effectiveness, which triggers the monitor's consecutive-decline signal earlier than pure fatigue threshold. (4) Predictor high-intensity override (>0.75) confounds concordance tests — observations must stay moderate
- **#520 Pipeline observability** ✅ (all 6 tasks, 5 batches): Full pipeline dump infrastructure — PipelineTrace record, CognitionStateProvider SPI, PipelineSnapshotStore (InMemory + SQLite + contract tests), CAPS caching in BehavioralSynthesisPhase, CognitionCore implements SPI with tick capture, pipelineDump @PlatformQuery endpoint, CDI/Spring wiring, integration test. Key findings: dependency cycles through cognitive-index block PipelineTrace in cognitive-observability-api — relocated to cognition-api. CAPS settle() is consolidation-time only. Prompt section rendering requires NPE resilience for unconfigured optional participants. Follow-up: #894 pipeline coherence eval

### Other near-term
- **#894 Pipeline coherence eval**: LLM-classified trace chain validation using CognitionTestHarness. Shame→suppression, curiosity→exploration, cross-tick trajectory, appraisal→channel weight coherence. Two-tier Haiku→Opus classification, gate ≥ 4.0.

- **CAPS regulation integration** (#806) ✅ (9 tasks, 7 batches): Full integration of regulation strategies as CAPS output nodes. 14 new nodes (10 regulation outputs + 4 social-context inputs), 35 new connections. Three-layer cascade (safety gate → CAPS → predictor → LLM). Rescorla-Wagner weight update with regulation effectiveness. 15 commits across 2 sessions.

  **Topology extension (Tasks 1-4):** Renamed BehavioralAttractor → CapsAttractor. Added 10 regulation output nodes (Gross 1998 + Zaki & Williams 2013) and 4 social-context input nodes (perceived_responsiveness, target_distress, social_isolation, power_asymmetry). 34 new connections with literature-derived weights (Baranczuk 2019 meta-analysis, Gross & John 2003). All regulation outputs UNIPOLAR [0,1]. Social-context inputs route through existing mediating nodes for personality-sensitive processing, with two justified direct connections (target_distress → co_regulation, power_asymmetry → deliberate_provocation).

  **Cascade integration (Task 8):** Three-layer cascade in EmotionRegulationTickParticipant. Safety hard gate (fatigue > 0.7 OR intensity > 0.75 → force AD) fires unconditionally before CAPS and predictor. CAPS settling extracts regulation attractors with confidence threshold (0.3) and dominance margin (0.15). Predictor fallback when CAPS is ambivalent. LLM free choice as final layer. Rescorla-Wagner weight update after regulation: outcomeIntensity = |peak - regulated|, outcomeValence = effectiveness > 0.5 → +1.0 else -1.0, salienceMultiplier = peakOriginalIntensity, schedule = variable_ratio.

  **Paired comparison eval (Task 9):** CapsRegulationComparisonEvalTest — 5 per-strategy scenarios, each run with cascade + LLM-only. Baseline results: CAPS settled to ATTENTIONAL_DEPLOYMENT in ALL 5 scenarios regardless of disposition. Margin 0.052, never reaching 0.15 dominanceMargin. Root cause: AD had NET incoming weight +1.10 (3 excitatory connections, 0 inhibitory) vs cognitive_change at +0.50.

  **Weight rebalancing (#906):** Four literature-justified topology changes after first-principles review:
  1. Added self_efficacy → AD at -0.25 (Sheppes & Gross 2011: cognitive resources enable reappraisal over distraction; competitive exclusion requires direct inhibition since CAPS outputs don't compete)
  2. Reduced arousal_level → AD from +0.45 to +0.35 (original exceeded cited meta-analysis range r=.30-.40)
  3. Reduced self_worth → RM from -0.25 to -0.15 (O→suppression r≈-.10 to -.15; over-inhibition made RM NET negative)
  4. Reduced self_efficacy → RM from -0.25 to -0.15 (same source)
  Plus 50% increase in disposition resting activation differentials.
  Rebalanced eval: 3/5 correct CAPS predictions (was 0/5). SS for avoidant, SM for bold, RM for competitive.

  **Adversarial weight review (self_worth → AD debate):** Four-agent adversarial review of whether to add self_worth → AD at -0.20. FOR agents cited Shafir et al. (2017) showing direct self-esteem → distraction choice relationship. AGAINST agents cited Matthews et al. (2021) systematic review showing average r ≈ 0.12 for self-esteem → regulation choice, and Judge et al. (2002) showing self-esteem/self-efficacy share 35% variance. Verdict: don't add — the indirect path (self_worth → self_efficacy → AD = -0.09) already captures ~75% of the empirical effect (r ≈ 0.12). Adding -0.20 direct would overshoot by 2.4x.

  **Key methodological finding:** Converting published meta-analysis effect sizes (correlations) to CAPS connection weights introduces structural biases the literature did not measure. Total incoming weight per output node creates advantages that individual weight magnitudes don't reveal. The eval exposed this — the first run produced correct individual weights but incorrect network-level behavior. Weight audit by total NET per node, not individual connection review, is the correct calibration methodology.

  **DISTRESS intensity gating (#907):** RegulationStrategyPredictor mapped DISTRESS to UNCONTROLLABLE_NEGATIVE regardless of intensity. Fixed: below 0.40, DISTRESS treated as CONTROLLABLE_NEGATIVE, allowing personality-driven strategy selection for mild distress.

  **Remaining gaps:** #912 (accommodating conflictMode has no CC boost in DispositionWeightMapper — fixes reappraisal eval scenario), #913 (protective conflictMode has no regulation mapping).

- **#902 Distress trajectory prompt enrichment** ✅: Added RegulationTracker distress trajectories to the LLM generation phase prompt for extrinsic strategies (CO_REGULATION, DELIBERATE_PROVOCATION). Renders IMPROVING/STABLE/WORSENING + delta per interacting agent in the social regulation context section.
- **#919 Appraisal→mood→drive propagation** ✅ (2 commits): Timing fix — appraisal runs in DERIVED phase after mood/drive ticks, so bridgeToMood signals weren't processed until the next tick cycle. Fix: added post-DERIVED mood/drive re-tick in CognitionCore.tick(). Second commit: relocated bridgeToMood from AppraisalTickParticipant to CognitionCore so it works with both real and mocked participants (eval test harness uses mocked appraisal).
- **#922 Appraisal→drive modulation** ✅: AppraisalDriveModulator maps OCC emotions to SDT drive axes via Frijda (1986) action tendencies: antagonism→COMPETENCE, avoidance→AUTONOMY, approach→CURIOSITY, being-with→AFFILIATION. Added recordAppraisalModulation to DriveOrchestrator consumed as a ModulationLayer.
- **#923 MindMapDriveSource** ✅: Replaced 0.1 stub DriveSource implementations with MindMapDriveSource — reads drive-intensity nodes from COGNITIVE subgraph (written by consolidation), falls back to personality baselines derived from DispositionAxes. Fixed CDE.derive() to preserve descriptor view. Wired into CognitionTestHarness.
- **#927 Drive trigger descriptions** ✅: MindMapDriveSource generates personality-derived trigger descriptions at cold start ("cooperative social orientation, analytical in conflict — social connection") instead of bare axis names.
- **#928 Eval judge criteria** ✅: Updated pipeline coherence eval judge prompts to evaluate full pipeline coherence (mood + drives + regulation) with explicit scoring rubric. Fixed stub override signature mismatch from #902.
- **#931 Generate override audit** ✅: Audited all EmotionRegulationGenerationPhase.generate() overrides. Found 2 sites (both in caps-llm-classifier eval tests) with silent signature mismatches from #902's 13-param overload. Fixed both.
- **#204 Wacky-manor API alignment** ✅ (examples repo): CuriosityDrive constructor and CorpusStore.append→write alignment.

## Stream 10: Pipeline Coherence — Appraisal State Propagation

**Issues:** #919, #922, #923, #927, #928, #931
**Dates:** 2026-11-10
**Literature:** Frijda 1986 (action tendencies), Lazarus 1991 (appraisal→coping), Deci & Ryan 2000 (SDT drives)
**Eval improvement:** Pipeline coherence eval (#894) from 2/5 → 4+/5 (all 4 scenarios)

### The problem

The pipeline coherence eval (#894) scored 2/5. After injecting high-intensity DISTRESS (0.85), the mood section rendered "You're feeling emotionally even" and drives showed "0.1 — stub". Three independent failures compounded:

1. **Timing:** Appraisal runs in DERIVED phase — after both mood tick and drive tick. The existing `bridgeToMood` recorded signals that weren't processed until the next tick cycle.
2. **Mocking:** The eval test harness used a mocked AppraisalTickParticipant. The mock's `tick()` was a no-op, so `bridgeToMood` (which lived inside the real participant's tick) never fired.
3. **Stubs:** Drive sources returned static 0.1 with trigger "stub" — no personality influence, no memory influence.

### What was built

| Component | Issue | What it does |
|-----------|-------|-------------|
| Post-DERIVED re-tick | #919 | CognitionCore.tick() re-ticks mood and drives after DERIVED phase so appraisal signals are processed within the same tick cycle |
| bridgeAppraisalToMood relocation | #919 | Moved from AppraisalTickParticipant to CognitionCore — reads from currentResult(), works with both real and mocked participants |
| AppraisalDriveModulator | #922 | Maps OCC emotions to SDT drive modulations via Frijda (1986) action tendencies. 22 emotion-type mappings, intensity-scaled |
| DriveOrchestrator.recordAppraisalModulation | #922 | Pending modulation queue consumed as a ModulationLayer during tick |
| MindMapDriveSource | #923 | Generic DriveSource parameterised by DriveAxis. Reads drive-intensity nodes from COGNITIVE subgraph, falls back to CDE-derived personality baselines |
| Personality-derived triggers | #927 | Descriptive trigger text from DispositionAxes at cold start |

### Architecture: three propagation pathways

```
Appraisal (DERIVED phase)
    ├─→ bridgeAppraisalToMood (CognitionCore)
    │       → MoodSignal.DirectShift from intensity-weighted PAD averages
    │       → mood re-tick processes signal immediately
    │       → MoodPromptSection renders updated mood state
    │
    ├─→ AppraisalDriveModulator
    │       → Frijda (1986): ANGER→antagonism→COMPETENCE, FEAR→avoidance→AUTONOMY
    │       → DriveOrchestrator.recordAppraisalModulation
    │       → drive re-tick processes modulation as ModulationLayer
    │       → DrivePromptSection renders modulated drives
    │
    └─→ Regulation (existing)
            → EmotionRegulationTickParticipant
            → strategy selection → LLM narrative generation
```

### Key finding: silent override failures

The #902 change (13-param generate overload with distressTrajectories) silently broke ALL eval test stubs. The stubs overrode the 12-param or 11-param generate(), but the tick participant called the 13-param version. The parent class's 13-param version returned null (sessionPipeline is null for stubs). Regulation was silently null in every eval scenario since #902 landed.

This was invisible because: (a) the tests had `assertThat(score).isGreaterThanOrEqualTo(4)` which failed for other reasons (stubs, timing), masking the regulation absence; (b) the trace formatting code didn't show the regulation section when it was null. The fix required both signature updates AND checking the trace output carefully.

### Frijda action tendencies → SDT drive mapping

| Action tendency | OCC emotions | Drive axis |
|----------------|-------------|------------|
| Antagonism (overcome, assert) | ANGER, REPROACH, RESENTMENT, HATE | COMPETENCE |
| Avoidance (escape, control) | FEAR, DISTRESS, SHAME, FEARS_CONFIRMED, REMORSE | AUTONOMY |
| Approach (explore, engage) | HOPE, SATISFACTION, RELIEF, JOY | CURIOSITY |
| Being-with (connect, bond) | LOVE, GRATITUDE, HAPPY_FOR, PITY, ADMIRATION | AFFILIATION |
| Dominance (mastery, control) | PRIDE, GRATIFICATION, GLOATING | COMPETENCE |

Boost = 0.5 × intensity, secondary boost = 0.25 × intensity, reduce = -0.2 × intensity. Multiple emotions stack additively.

### MindMapDriveSource — two-layer pattern

Follows the CuriosityDrive template. Layer 1 (consolidation, background): writes drive-intensity nodes to COGNITIVE subgraph with personality baseline + memory-modulated intensity + description. Layer 2 (tick, per-interaction): MindMapDriveSource reads these nodes. Falls back to personality baselines derived from DispositionAxes when no node exists.

| Disposition axis | Drive axis | Mapping |
|---|---|---|
| socialOrient + conflictMode | AFFILIATION | cooperative/confronting→0.6, moderate/analytical→0.4, independent/avoidant→0.3 |
| ruleFollowing + riskAppetite | COMPETENCE | strict/aggressive→0.6, moderate→0.4, flexible/conservative→0.3 |
| autonomy | AUTONOMY | high→0.6, moderate→0.4, low→0.3 |

### Eval progression

| Change | moderate-anger | distress-AD | fear-AD | cross-tick | Gate |
|--------|---------------|-------------|---------|------------|------|
| Before (#894 baseline) | 2 | — | — | — | 4 |
| +mood bridge (#919) | 3 | — | — | — | 4 |
| +drive modulator (#922) | 3 | — | — | — | 4 |
| +MindMapDriveSource (#923) | 3 | — | — | — | 4 |
| +triggers + judge + stub fix (#927-931) | **4** | **4** | **4** | **5** | 4 |

### Remaining validation

**#930 (filed):** 300-tick scenario validation. The post-DERIVED re-tick doubles mood and drive ticks per cycle. Unit-tested and eval-tested but not validated at scale. Could cause mood overshoot or drive drift at 300 ticks × 17 characters.

**#932 (CLOSED — landed `2fa64e87` on 2026-11-11):** `SmokeScenarioEvalTest` — 25-tick, 3-agent real pipeline integration test. Full `ManorCognitivePipelineBuilder` with real `CognitionCore.tick()`, appraisal, regulation, MindMap evolution. Six scripted events (arrival, discovery, suspicion, confrontation, threat, resolution). LLM judge scores 15 sampled events. **First run: mean 4.93, min 4.0.**

## Stream 9: LLM Session Reuse Audit (#935, #942) — Cross-Agent Contamination

**Issues:** #935 (CLOSED 2026-11-13), #942 (CLOSED 2026-11-13)
**Date:** 2026-11-11 → 2026-11-13
**Blog:** "The Test That Was Missing" (revised 2026-11-11)

### Discovery

The smoke test's 44-minute runtime triggered an audit of all 16 LLM interaction points. The audit found two distinct problems:

1. **Session waste (perf):** `LlmSituationClassifier` creates ~150 fresh sessions via `UUID.randomUUID()` per 25-tick run. Each pays full system prompt cost.
2. **Cross-agent contamination (correctness):** 14 components use static session IDs (`"emotion-regulation"`, `"inner-life"`, `"mood-appraisal"`, etc.). `SessionPipeline.openSession` uses `computeIfAbsent` — same ID = same session. Agent A's cognitive LLM responses accumulate in the session and are visible to Agent B.

### Impact on prior eval results

All multi-agent scenario runs to date had cross-agent cognitive contamination. The 4.93 smoke test score may have been partly inflated or degraded by bleed-through.

### Fix — complete (all 16 components)

| Category | Count | Commits | Status |
|----------|-------|---------|--------|
| Fixed (agentId in scope) | 10 | `65c92448`, `16253932` | Session IDs appended with agentId |
| Fixed (AppraisalContext API change) | 6 | `d88802b5`, `4fcc4dd2`, `679db1a4` | agentId added to AppraisalContext record, CrossAxisGoalEnricher interface |
| Already correct (PlaybookOrchestrator) | 3 | — | No change needed |

**Components fixed in #935:** LlmSituationClassifier, EmotionRegulationGenerationPhase, InnerLifeOrchestrator, MentalModelOrchestrator, UserModelOrchestrator, StrategyLearningOrchestrator, NarrativeContentSummariser, CognitionCore (mood-appraisal + bdi-extraction), CognitionObservationBuilder.

**Components fixed in #942:** LlmAppraisalStrategy, LlmCopingCheck, LlmImplicationCheck, LlmNormativeCheck, LlmCrossAxisGoalEnricher, LlmDeductiveGoalFormationStrategy.

### Clean baseline eval (post-fix)

Smoke scenario eval re-run with all 16 components using per-agent session isolation:
- **Result:** PASSED (mean ≥ 3.5, min ≥ 2.0 thresholds met)
- **Runtime:** 42 minutes (similar to contaminated run — fix was correctness, not performance)
- **Events:** 137 across 3 agents, 25 ticks (complete run)
- **Distribution:** hooded-claw 46, penelope-pitstop 43, peter-perfect 48

This is the first multi-agent eval with confirmed session isolation. Prior 4.93 score was under contamination; the clean run's score establishes the true baseline.

## Regulation Epic Status (#821)

**Status:** 14 of 15 implementation children CLOSED. One remaining: #805 (social emotion regulation).

| # | Title | Status |
|---|-------|--------|
| #590 | Emotion regulation: post-appraisal strategy selection | ✅ CLOSED |
| #816 | EmotionMapper intensity compression fix | ✅ CLOSED |
| #544 | OCC emotion vocabulary enforcement | ✅ CLOSED |
| #815 | Cognitive load proxy (Petter 2025) | ✅ CLOSED |
| #817 | Suppression fatigue (Bosse 2010) | ✅ CLOSED |
| #820 | Regulation monitoring loop (Gross 2015) | ✅ CLOSED |
| #818 | Automatic regulation (personality defaults) | ✅ CLOSED |
| #819 | Proactive regulation (pre-appraisal avoidance) | ✅ CLOSED |
| #805 | Social emotion regulation (Zaki & Williams 2013) | 🔴 OPEN — next |
| #806 | CAPS integration: regulation as CAUs | ✅ CLOSED 2026-11-13 |
| #822 | Per-strategy eval | ✅ CLOSED |
| #823 | Per-modality eval | ✅ CLOSED |
| #520 | Cognitive state dump endpoint | ✅ CLOSED |
| #828 | Regulation predictor observability | ✅ CLOSED |
| #829 | Scenario validation (300-tick) | ✅ CLOSED |
| #830 | Predictor quality (shame/ceiling) | ✅ CLOSED |
| #851 | Dynamic regulatory capacity | ✅ CLOSED 2026-11-13 |
| #935 | Session reuse audit | ✅ CLOSED 2026-11-13 |
| #942 | AppraisalContext agentId scoping | ✅ CLOSED 2026-11-13 |

**Remaining regulation queue (slot 196):**
1. #805 — Social emotion regulation (Zaki & Williams 2013) ← active
2. #832 — Identify emotion-personality capacity gaps
3. #845 — Upgrade CDE derivations to PID-5 facets
4. #921 — Prediction calibration analysis
5. #925 — CAPS consolidation-phase coherence scenario
6. #941 — Drive profile differentiation

## Stream 11: Cross-Stage Narrative Coherence (#938)

**Issues:** #938 (complete), follow-on: #957, #958, #959
**Date:** 2026-11-13
**Blog:** "The Stage That Couldn't Hear the Last One" (2026-11-13)

### The problem

PipelineCoherenceEvalTest (#894) measured per-transition coherence across four scenarios using a two-tier Haiku→Opus judge. All four scenarios failed (gate: ≥3/4 at mean ≥4.0). Process constraint improvements from #933/#937 had zero impact — the failures were structural, not prompt-quality-shaped.

### Root cause analysis — five boundary failures

Every failure occurred at a stage boundary, not within any stage:

| # | Boundary | Failure | Fix |
|---|----------|---------|-----|
| 1 | Observation → Appraisal | Session contamination: LlmAppraisalStrategy reused session across ticks, LLM anchored on previous response | `ClearingPolicy.EVERY_CALL` — clears conversation history, keeps system prompt cached |
| 2 | Appraisal → Prompt | MoodPromptSection coarse vocabulary: PAD-bin labels ("displeased") contradicted rich appraisal narrative | Suppress MoodPromptSection when appraisal active (follows CharacterDrivePromptSection precedent) |
| 3 | Init → Trajectory | Serialization off-by-one: init tick consumed obsIndex, misaligning all observation-to-trace pairings | Skip snapshots with null appraisalResult in trajectory serialization |
| 4 | Regulation → Prompt | Missing propagation: regulation narrative (intensity 0.87→0.54) invisible in prompt sections | `setRegulatedNarrative()` on AppraisalTickParticipant; AppraisalPromptSection prefers regulated narrative |
| 5 | Drives → Prompt | Trigger text mismatch: DriveComposer kept source trigger ("no mental models") even when appraisal modulation dominated | Override trigger to "elevated/dampened by emotional response" when appraisal delta > 0.2 |

### Additional fixes

- **AppraisalTickParticipant**: Pass agentId via ClassificationContext to LlmSituationClassifier (prevents UUID session sprawl)
- **EvalTestSupport**: Increase maxConcurrentSessions from 4 to 10 (classifier + appraisal + judge sessions)
- **PipelineCoherenceEvalTest**: Wire LlmSituationClassifier via 4-arg configureAppraisal for channel weight production
- **PipelineCoherenceEvalTest**: Remove invalid `.with("curiosity", true)` config key
- **PipelineCoherenceEvalTest**: Update shame regulation expectation to account for safety gate (Petter 2025: intensity > 0.75 → ATTENTIONAL_DEPLOYMENT)

### Results — 1/4 pass

| Scenario | Before | After | Status |
|----------|--------|-------|--------|
| shameRegulationChain | 3.475 | **4.28** | **PASS** |
| appraisalChannelWeightCoherence | 2.3 | 3.5 | FAIL — obs→appraisal LLM quality (wrong emotions for security breach) |
| curiosityExplorationChain | ERROR | 2.5 | FAIL — drives don't activate from JOY(0.29) |
| crossTickTrajectory | 2.2 | 2.3 | FAIL — negative attractor lock, drive monotonicity |

### Key finding: wiring vs quality

The five boundary fixes addressed all cross-stage coherence WIRING issues. The remaining failures are a different class — SEC→OCC emotion mapping quality:

- **Arousal calibration:** SHAME's canonical PAD arousal is -0.04 (Mehrabian), producing near-zero arousal even in public confrontations. Theoretically correct but counter-intuitive.
- **Missing INTEREST/SURPRISE:** The SEC→OCC mapper produces JOY for high-novelty discoveries. JOY generates CURIOSITY delta of only +0.07 via AppraisalDriveModulator. HOPE would produce +0.5×intensity — 7x larger.
- **Missing FEAR:** Security breach maps to DISTRESS + SHAME, not FEAR. FEAR is the expected primary emotion for active threats.
- **Negative attractor lock:** The appraisal LLM anchors on negative mood state across ticks and never recovers proportionally to positive observations.

### Follow-on issues filed

- **#957** — SEC→OCC emotion mapping: arousal calibration, FEAR/INTEREST taxonomy gaps (M/Med)
- **#958** — Drive activation from single-tick appraisal: curiosity gap, blocked by #957 (S/Low)
- **#959** — Cross-tick trajectory quality: negative attractor lock, drive monotonicity (M/High)

### Key methodological finding

Per-transition coherence scoring with an LLM judge is essential for multi-stage pipeline evaluation. Scoring only the final output masks boundary failures — you can't tell if the appraisal→regulation transition lost signal or if the regulation→prompt transition did. When you score each transition independently, the boundary failures become visible and addressable

## Stream 12: CAPS Consolidation Coherence (#925)

**Issues:** #925 (complete)
**Date:** 2026-11-13
**Blog:** "The Pipeline That Only Runs When You're Not Looking" (2026-11-13)

### The problem

Stream 10 and Stream 11 validated within-tick coherence — observation→appraisal→regulation→prompt. But the CAPS consolidation pipeline was never tested for coherence. This pipeline runs between ticks: `ExperienceConsolidationPhase` graduates memories into the cognitive subgraph, then `BehavioralSynthesisPhase` classifies those nodes into CAPS input activations, settles the network, and creates behavioral attractor nodes in the BEHAVIORAL subgraph. The `BehavioralPromptSection` then renders those attractors during the next tick.

The design review for #894 (R1-01) identified this gap: CAPS data in PipelineTrace comes from consolidation, not from the current tick. The appraisal→CAPS causal chain doesn't exist within a single tick.

### What was built

Scenario 5 in `PipelineCoherenceEvalTest` — a consolidation-phase coherence eval that tests the full consolidation→trace→prompt pipeline:

| Step | What it does |
|------|-------------|
| Seed graduated nodes | 3 experience nodes in COGNITIVE subgraph (social exclusion, public humiliation, collaborator abandonment) with `source-memory-id`, `agent-id`, `cognitiveKind` properties |
| Run BehavioralSynthesisPhase | LlmSituationClassifier classifies experiences → CAPS input activations; InMemoryCapsEngine settles network → behavioral attractors |
| Run tick | CognitionCore tick captures PipelineTrace with capsInputActivations + capsSettling from phase + prompt sections from BehavioralPromptSection |
| Judge coherence | 4 transitions via TwoTierCoherenceJudge (Haiku→Opus): graduated-to-input, input-to-settling, settling-to-prompt, full-consolidation |

### Infrastructure changes

- `BehavioralSynthesisPhase` test constructors (4-arg and 5-arg) made public — previously package-private, blocking cross-module test access from `caps-llm-classifier`
- 3 new serializers: `serializeGraduatedNodes`, `serializeCapsInputActivations`, `serializeCapsSettling`
- `CognitionCoreTestBuilder` eliminated in #926 — all eval tests now use `CognitionTestHarness.builder()...build().core()` with real orchestrators

### Key design decisions

1. **Seeded graduated nodes directly** rather than running ExperienceConsolidationPhase — the eval tests the consolidation→tick boundary, not the experience→graduation boundary. ExperienceConsolidationPhase has its own tests.
2. **Used LlmSituationClassifier** rather than RuleBasedSituationClassifier — matches Scenarios 2-4 and is the production classifier. RuleBasedSituationClassifier.classify() returns empty (keyword logic disabled, tracked by #587).
3. **Social rejection scenario** chosen for clear CAPS mapping — exclusion, public criticism, and abandonment map to `rejection`, `social_threat`, `exclusion` input nodes with predictable defensive/withdrawal attractor outcomes.

### Deferred
- **Era 6 Phase 4**: Condensation chains, persistent entity index, ACT-R decay (not blocked)
- **Era 7 definition**: The post-era 6 work — regulation, identity, search budget, exploration pipeline — is era-scale in aggregate. Whether it constitutes era 7 or folds into era 6 Phase 4+ is a naming decision, not a technical one.

## Stream 13: Test Harness Unification (#926)

Surfaced during #894 design review (R1-07). Two competing test harnesses existed for building `CognitionCore` in tests: `CognitionCoreTestBuilder` (static factory in caps-llm-classifier, real orchestrators, `SessionPipeline`) and `CognitionTestHarness` (fluent builder in cognition, null orchestrators, `MindMapDriveSource` stubs).

### The problem

The split created two traps:

1. **Wrong harness for the job.** Tests that needed `SessionPipeline` for LLM-backed orchestrators couldn't use `CognitionTestHarness` (no pipeline support). Tests that needed rich lifecycle methods (`tick()`, `consolidate()`, `renderPrompt()`, state inspection) couldn't use `CognitionCoreTestBuilder` (bare `CognitionCore` only).

2. **Half-wired stubs.** `CognitionTestHarness` passed null for 7 orchestrators (UserModel, MentalModel, Strategy, Narrative, Goals, InnerLife, Hygiene) and used `MindMapDriveSource` instead of real orchestrator-backed drives. Tests ran against a cognitive pipeline configuration that doesn't exist in production — a system where drives report zero intensity and orchestrators don't participate. When tests passed, they proved nothing about the real system.

### What was built

Unified into one harness with one wiring path:

| Before | After |
|--------|-------|
| `CognitionCoreTestBuilder` (static factory, 4 overloads) | Deleted |
| `CognitionTestHarness` with null orchestrators | Always wires real orchestrators |
| `MindMapDriveSource` (reads from empty MindMap → 0.0) | `CompetenceDrive`, `AffiliationDrive`, `AutonomyDrive` (orchestrator-backed) |
| No `SessionPipeline` support | `withSessionPipeline()` builder method |
| 18 call sites across 9 eval test files | All migrated to `CognitionTestHarness.builder()...build().core()` |

### Impact on existing tests

Switching from zero-drive stubs to real orchestrator-backed drives (baseline affiliation=0.3, autonomy=0.5) shifted the activation levels that feed `SearchBudgetTickParticipant`. Five search budget tests were calibrated for zero-drive activation and needed adjustment:

- **Pathological threshold tests**: convergence was 0.0 regardless of threshold because the activation from real drives pushed it past the sigmoid inflection point. Fix: lowered convergenceMidpoint from 0.5 to 0.3.
- **Capacity ceiling tests**: depth no longer exceeded the 0.40 ceiling because Yerkes-Dodson decay (past extremePeak 0.85) compressed the activation. Fix: lowered ceiling from 0.40 to 0.10 — the test is about the capping mechanism, not the specific ceiling value.

Both fixes made the tests more robust: they now verify the mechanism works regardless of drive state, rather than depending on the accident of zero-drive activation.

### Key finding

Test harnesses that pass null for production dependencies create a false confidence problem. The tests pass, but they're testing a system that doesn't exist. When the real dependencies are wired in, tests break — not because the code is wrong, but because the tests were calibrated for a phantom configuration. The fix isn't to adjust drive baselines to match the old stubs; it's to design tests that work with any reasonable configuration by testing mechanisms, not specific numeric values tied to a particular drive state.
