# Post-Era 6: Regulation, Identity, and Pipeline Completion

**Period:** 2026-11-01 → 2026-11-05
**Status:** Standalone gap document — to be incorporated into era 7 when that era is defined
**Predecessor:** Era 6 — Cognitive Memory Architecture (Phase 3 completed 2026-11-01)

---

## Overview

Era 6 completed the three-layer memory architecture (leaf/derived/hot-read) on 2026-11-01. The work that followed falls into five distinct streams, all advancing in parallel across ~130 commits. The dominant stream — intrapersonal emotion regulation — is arguably era-scale in itself: a complete Gross 1998 process model implementation with Bosse 2010 fatigue dynamics, Petter 2025 personality correlations, and mechanical prediction fallback.

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

**Quality gate:** LLM-judge plausibility scoring. Floor: 4/5, Mean: 4.6/5, Gate: 4.0. Distribution: [4, 5, 4, 5, 5] across 5 scored predictions, 3 documented fall-throughs. See findings below.

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

## Blog Coverage

| Date | Title | Streams covered |
|------|-------|-----------------|
| 2026-11-02 | Don't Stop the World to Remember | Non-blocking consolidation, CLS theory |
| 2026-11-02 | The Plumbing Is Done | Pipeline hardening complete, cognitive richness frontier |
| 2026-11-03 | Signal Starvation | CAPS pipeline audit, 9/14 dead wires, three-layer personality |
| 2026-11-03 | Formation Memory Grounding | Somatic echo, formation memory in prompts |
| 2026-11-04 | The Gap Between Feeling and Acting | Full regulation deep dive |

## What's Next

- **Scenario validation**: Run wacky-manor scenario with predictor active, verify output quality matches LLM-only regulation
- **Proactive regulation** (#819): Situation selection before appraisal — emotional memory driving spatial avoidance
- **Social regulation** (#805): Interpersonal strategies (confiding, provocation, withdrawal) — requires intrapersonal system as foundation
- **CAPS integration** (#806): Encode stable regulation patterns as CAPS nodes for personality-consistent regulation without per-tick LLM calls
- **Era 6 Phase 4**: Condensation chains, persistent entity index, ACT-R decay (deferred, not blocked)
- **Era 7 definition**: Regulate the era boundary — regulation + identity + pipeline wiring may constitute era 7, or may fold into era 6 Phase 4+
