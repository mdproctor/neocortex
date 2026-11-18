# Neocortex Cognitive Model — Complete Reference

This document is the authoritative reference for every concept, value type, and psychological foundation in the casehub-neocortex cognitive system. It covers all subsystems from affect and emotion through personality, memory, reasoning, and consolidation.

---

## 1. Memory System

The memory system stores all cognitive content across six typed domains. Every memory belongs to a `Subject` (typed entity reference: `type` + `id`, lowercase-normalised type) and a `MemoryDomain` (string-named domain).

### 1.1 Memory Domains

| Domain | What it stores | Psychological basis |
|--------|---------------|---------------------|
| `experience` | Observations, actions, outcomes — what happened | Episodic memory (Tulving) |
| `relationship` | Per-agent-pair interaction records with quality signals | Social cognition, attachment theory |
| `reflection` | Synthesised insights at hierarchical levels | Metacognition (Flavell) |
| `mood` | PAD emotional state snapshots over time | Mehrabian's PAD model |
| `engagement` | Per-interaction social outcome measurements | Social signal processing |
| `affect` | PAD values stored on MindMap node changes | Affective computing |

### 1.2 Memory Record Structure

**Types:** `MemoryInput` (write), `Memory` (read), `MemoryScanRequest` (bulk scan), `MemoryQuery` (domain-scoped query)

Every `Memory` record contains:

| Field | Type | Description |
|-------|------|-------------|
| `memoryId` | String | Unique identifier |
| `subject` | `Subject` | Entity reference (type + id) |
| `domain` | `MemoryDomain` | Domain name string |
| `tenantId` | String | Tenant isolation |
| `caseId` | String | Optional case context |
| `text` | String | Prose content |
| `attributes` | `Map<String, String>` | Domain-specific key-value metadata |
| `createdAt` | `Instant` | Timestamp |
| `confidence` | `Confidence` | Origin + value + decay reference |
| `pleasure` | `Double` | PAD P dimension [-1, 1] |
| `arousal` | `Double` | PAD A dimension [-1, 1] |
| `dominance` | `Double` | PAD D dimension [-1, 1] |
| `principalId` | `PrincipalId` | Owner agent (for visibility scoping) |
| `sharedWith` | `Set<String>` | Agents with read access |

### 1.3 Domain-Specific Attribute Keys

**Experience** (`ExperienceAttributeKeys`):

| Key | Values | Description |
|-----|--------|-------------|
| `event-type` | `observation`, `action`, `outcome` | Type of experience event |
| `turn-id` | String | Conversation turn reference |
| `timestamp` | ISO-8601 | When the event occurred |
| `subject` | String | Entity the experience is about |
| `capability` | String | Agent capability used (for actions) |
| `result` | String | Outcome description |
| `target-agent` | String | Other agent involved |
| `source-channel` | String | Communication channel |
| `outcome-status` | String | Success/failure status |

**Formative Experience** (`FormativeAttributeKeys`) — biographical/developmental memories:

| Key | Description |
|-----|-------------|
| `catalogue-entry-id` | Backstory catalogue reference |
| `situation-types` | CAPS situation types (comma-separated) |
| `salience-multiplier` | Importance weight |
| `reinforcement-schedule` | Learning schedule |
| `developmental-period` | Life stage (childhood, adolescence, etc.) |

**Relationship** (`RelationshipAttributeKeys`):

| Key | Values | Description |
|-----|--------|-------------|
| `other-agent` | String | The other agent in the pair |
| `source-event-type` | String | What triggered this record |
| `quality-signal` | `POSITIVE`, `NEGATIVE`, `NEUTRAL` | Interaction quality |
| `turn-id` | String | Conversation turn reference |
| `timestamp` | ISO-8601 | When the interaction occurred |

**Reflection** (`ReflectionAttributeKeys`):

| Key | Description |
|-----|-------------|
| `reflection-level` | Hierarchical depth (1 = direct, 2 = meta, etc.) |
| `source-memory-ids` | Comma-separated IDs of source memories |
| `timestamp` | ISO-8601 |

**Mood** (`MoodAttributeKeys`):

| Key | Description |
|-----|-------------|
| `pleasure` | P dimension value as string |
| `arousal` | A dimension value as string |
| `dominance` | D dimension value as string |
| `turn-id` | Conversation turn that triggered the mood change |
| `active-context-ids` | Comma-separated context IDs for domain-partitioned correlation |
| `timestamp` | ISO-8601 |

**Engagement** (`EngagementAttributeKeys`):

| Key | Description |
|-----|-------------|
| `other-agent` | Agent being measured |
| `turn-id` | Turn reference |
| `responded` | Boolean — did they respond? |
| `response-time-ms` | Response latency |
| `response-length` | Character count of response |
| `affect-shift` | PAD delta magnitude |
| `reaction-count` | Number of reactions |
| `continued` | Boolean — did conversation continue? |
| `timestamp` | ISO-8601 |

### 1.4 Memory Capabilities

**Enum:** `MemoryCapability`

| Capability | Description |
|-----------|-------------|
| `CHRONOLOGICAL_ORDER` | Results ordered by time |
| `DOMAIN_SCOPED` | Query within a single domain |
| `CASE_SCOPED` | Filter by case ID |
| `SINCE_FILTER` | Filter by timestamp |
| `BATCH_STORE` | Store multiple memories atomically |
| `SEMANTIC_SEARCH` | Vector similarity search |
| `FULL_TEXT_SEARCH` | BM25/FTS keyword search |
| `TEMPORAL_GRAPH` | Temporal validity on results |
| `ENTITY_TYPE_FILTER` | Filter by graph entity type |
| `ENTITY_TRAVERSAL` | Graph traversal with depth |
| `FACT_SEARCH` | Edge/relationship search |
| `NODE_SEARCH` | Entity node summary search |
| `ERASE_BY_ID` | Delete specific memory |
| `ERASE_ENTITY` | GDPR full-entity wipe |
| `ERASE_DOMAIN_CASE` | Domain + case scoped erasure |
| `CROSS_TENANT_ERASE` | GDPR Art.17 across tenants |
| `SCAN` | Paginated attribute-filtered enumeration |
| `DISCOVER_TENANTS` | Tenant discovery |
| `PURGE` | Retention-based bulk deletion |
| `ENRICH_ATTRIBUTES` | Post-store attribute enrichment |

---

## 2. Experience Events

Typed agent experience ingestion — the primary way cognitive agents record what happens.

**Sealed hierarchy:** `ExperienceEvent` permits `Observation`, `Action`, `Outcome`, `FormativeExperience`

| Type | Extra fields | What it represents |
|------|-------------|-------------------|
| `Observation` | `subject` (required) | Something the agent noticed about an entity |
| `Action` | `capability` | Something the agent did |
| `Outcome` | `result` (required), `capability` | The result of an action |
| `FormativeExperience` | `catalogueEntryId`, `situationTypes`, `salienceMultiplier`, `reinforcementSchedule`, `developmentalPeriod`, PAD | Biographical/developmental experience (backstory seeding) |

All share: `agentId`, `tenantId`, `caseId`, `turnId`, `timestamp`, `description`, `confidence`, `metadata`

### 2.1 Sub-Thought Types

Experience memories can be decomposed into typed sub-thoughts — sentence-level cognitive fragments.

**Constants:** `SubThoughtTypes`

| Type | Description |
|------|-------------|
| `affect-observation` | Emotional reaction noted in the experience |
| `causal-inference` | Agent's reasoning about cause and effect |
| `evaluative` | Value judgment or assessment |
| `intention` | Future-directed plan or desire |
| `self-reflection` | Metacognitive observation about self |
| `association` | Connection drawn to other knowledge |
| `concern` | Worry or anxiety about a situation |

**Psychological basis:** Dual-process theory (Kahneman) — sub-thoughts capture the automatic System 1 reactions that accompany deliberate experience processing.

---

## 3. Affect — The PAD Model

Emotional state is represented as a point in the three-dimensional PAD space.

**Psychological basis:** Mehrabian's PAD (Pleasure-Arousal-Dominance) model of emotional state

### 3.1 Dimensions

| Dimension | Range | Low end | High end | What it measures |
|-----------|-------|---------|----------|-----------------|
| **Pleasure** (P) | [-1, +1] | Pain, distress, unhappiness | Joy, ecstasy, satisfaction | Hedonic valence — how good/bad the agent feels |
| **Arousal** (A) | [-1, +1] | Calm, relaxed, sleepy | Excited, stimulated, alert | Activation level — how energised the agent is |
| **Dominance** (D) | [-1, +1] | Submissive, controlled, helpless | Dominant, in-control, influential | Sense of control — how much agency the agent feels |

### 3.2 Mood Types

| Type | Fields | Description |
|------|--------|-------------|
| `MoodState` | `agentId`, `tenantId`, `timestamp`, `pleasure`, `arousal`, `dominance`, `cause`, `turnId`, `activeContextIds`, `metadata` | Current emotional state snapshot |
| `MoodBaseline` | `pleasure`, `arousal`, `dominance` | Per-agent resting emotional state — the PAD values mood decays toward |
| `MoodDecay` | Static utility | Exponential decay: `current + (baseline - current) * (1 - e^(-elapsed/tau))` |

### 3.3 PAD on Nodes

MindMap nodes carry PAD dimensions directly (`pleasure`, `arousal`, `dominance` — all `Double`). This represents the affective valence associated with an entity — how the agent feels about that person, place, concept, or goal.

**Connection to mood:** The `AffectTrajectoryDecorator` intercepts node PAD updates and stores domain="affect" memories, enabling longitudinal affect analysis via `AffectTrajectoryAnalyzer` (slope, volatility, trend).

---

## 4. OCC Emotions

The system models 22 discrete emotion types from the OCC (Ortony, Clore & Collins) theory.

**Psychological basis:** OCC emotion model — emotions arise from appraising events, actions, and objects relative to goals, standards, and attitudes.

### 4.1 All 22 Emotion Types

**Enum:** `EmotionType`

| Branch | Emotions | Trigger |
|--------|----------|---------|
| **Prospect-based** (goal lifecycle) | `HOPE`, `FEAR`, `SATISFACTION`, `DISAPPOINTMENT`, `RELIEF`, `FEARS_CONFIRMED` | Appraising future/past goal-relevant events |
| **Well-being** | `JOY`, `DISTRESS` | Appraising events as desirable/undesirable |
| **Fortunes of others** | `HAPPY_FOR`, `PITY` | Appraising events affecting liked others |
| **Fortunes of others** (disliked) | `RESENTMENT`, `GLOATING` | Appraising events affecting disliked others |
| **Attribution** | `PRIDE`, `SHAME`, `REPROACH`, `ADMIRATION` | Appraising self/other actions against standards |
| **Compound** | `GRATITUDE`, `ANGER`, `REMORSE`, `GRATIFICATION` | Combinations of attribution + well-being |
| **Object-based** | `LOVE`, `HATE` | Attitude toward objects/entities |

### 4.2 Emotion Record

**Type:** `CognitiveEmotion`

| Field | Type | Description |
|-------|------|-------------|
| `type` | `EmotionType` | Which of the 22 emotions |
| `intensity` | `double` [0, 1] | How strongly felt |
| `subjectId` | `String` | Entity the emotion is about |
| `onset` | `Instant` | When it began |
| `source` | `EmotionSource` | Origin of the emotion |
| `pad` | `PadProjection` | PAD impact of this emotion |

**Emotion Source** (`EmotionSource`):

| Value | Description |
|-------|-------------|
| `INTRINSIC` | Directly experienced by the agent |
| `EMPATHIC` | Felt on behalf of another |
| `ATTRIBUTED` | Inferred from context |

### 4.3 Emotion → PAD Mapping (ALMA Table)

**Type:** `AlmaPadTable` — maps each emotion to its canonical PAD projection, scaled by intensity.

| Emotion | P | A | D |
|---------|-----|-----|-----|
| ADMIRATION | +0.50 | +0.30 | -0.20 |
| ANGER | -0.51 | +0.59 | +0.25 |
| DISAPPOINTMENT | -0.30 | +0.10 | -0.40 |
| DISTRESS | -0.40 | -0.20 | -0.50 |
| FEAR | -0.64 | +0.60 | -0.43 |
| FEARS_CONFIRMED | -0.50 | -0.30 | -0.70 |
| GLOATING | +0.30 | -0.30 | -0.10 |
| GRATIFICATION | +0.60 | +0.50 | +0.40 |
| GRATITUDE | +0.40 | +0.20 | -0.30 |
| HAPPY_FOR | +0.40 | +0.20 | +0.20 |
| HATE | -0.60 | +0.60 | +0.30 |
| HOPE | +0.20 | +0.20 | -0.10 |
| JOY | +0.40 | +0.20 | +0.10 |
| LOVE | +0.40 | +0.16 | -0.24 |
| PITY | -0.40 | -0.20 | -0.50 |
| PRIDE | +0.40 | +0.30 | +0.30 |
| RELIEF | +0.20 | -0.30 | -0.40 |
| REMORSE | -0.30 | +0.10 | -0.60 |
| REPROACH | -0.30 | -0.10 | +0.40 |
| RESENTMENT | -0.20 | -0.30 | -0.20 |
| SATISFACTION | +0.30 | -0.20 | +0.40 |
| SHAME | -0.30 | +0.10 | -0.60 |

**Psychological basis:** ALMA (A Layered Model of Affect) — Gebhard (2005). Empirically derived PAD weights for OCC emotions.

---

## 5. CARMA Appraisal — The SEC Pipeline

The appraisal system evaluates incoming stimuli through Scherer's 4-stage Stimulus Evaluation Check pipeline. This is how raw perception becomes emotion.

**Psychological basis:** Scherer's Component Process Model (CPM) of emotion

### 5.1 The Four Stages

```
Stimulus → Relevance → Implications → Coping → Normative → Emotion
```

| Stage | SEC Dimensions evaluated | What it answers |
|-------|-------------------------|-----------------|
| **Relevance** | `relevance`, `novelty`, `urgency` | Is this worth attending to? |
| **Implications** | `conduciveness` | Does this help or hinder my goals? |
| **Coping** | `controllability`, `adjustability` | Can I do anything about it? |
| **Normative** | `internal-standards`, `external-standards` | Does this conform to my values and social norms? |

### 5.2 SEC Dimensions

**Constants:** `SecDimensions`

| Dimension | Range | Meaning |
|-----------|-------|---------|
| `relevance` | [0, 1] | How relevant to the agent's concerns |
| `novelty` | [0, 1] | How unexpected or new |
| `urgency` | [0, 1] | How time-pressured |
| `conduciveness` | [-1, 1] | Goal-positive (+) or goal-negative (-) |
| `controllability` | [0, 1] | How much the agent can influence the outcome |
| `adjustability` | [0, 1] | How well the agent can adapt |
| `internal-standards` | [0, 1] | Conformance to personal values (low = violation) |
| `external-standards` | [0, 1] | Conformance to social norms (low = violation) |

### 5.3 Appraisal Implementation

| Type | Description |
|------|-------------|
| `SecResult` | Dimensions map produced by an appraisal |
| `AppraisalStrategy` | `@FunctionalInterface` — full appraisal producing SEC dimensions + optional narrative |
| `LlmAppraisalStrategy` | Single sub-LLM Haiku call — SEC dimensions + evocative narrative in one pass |
| `SalienceStrategy` | `@FunctionalInterface` — perception filtering gate before appraisal |

### 5.4 SEC → Emotion Mapping

`EmotionMapper.mapEmotions()` translates SEC dimension patterns to OCC emotions:

| Pattern | Emotion |
|---------|---------|
| High relevance + low conduciveness + low controllability | `FEAR` |
| High relevance + low conduciveness + high controllability | `ANGER` |
| High relevance + low conduciveness (moderate control) | `DISTRESS` |
| High relevance + high conduciveness | `JOY` |
| High relevance + low internal standards | `SHAME` |
| High relevance + low external standards | `REPROACH` |

### 5.5 Appraisal Configuration

**Type:** `AppraisalWeights` — personality-modulated sensitivity thresholds

| Field | Range | What it controls |
|-------|-------|-----------------|
| `urgencyWeight` | > 0 | Multiplier on urgency perception |
| `relationshipWeight` | > 0 | Sensitivity to relationship-relevant stimuli |
| `fearOnsetThreshold` | > 0 | How easily fear is triggered |
| `selfStandardsStrictness` | [0.5, 2.0] | Personal standards strictness |
| `otherStandardsStrictness` | [0.5, 2.0] | Social norms strictness |

---

## 6. Action Tendencies

Emotions produce behavioural impulses — action tendencies in the Frijda framework.

**Psychological basis:** Frijda's action tendency theory — emotions are not just felt states, they prepare the organism for action.

### 6.1 The 9 Action Readiness States

**Enum:** `ActionReadiness`

| Tendency | Description | Triggered by |
|----------|-------------|-------------|
| `APPROACH` | Move toward, engage, seek | Positive conduciveness |
| `AVOIDANCE` | Move away, withdraw, escape | Negative conduciveness + low control |
| `ATTENDING` | Focus attention, orient, investigate | High novelty |
| `REJECTION` | Push away, refuse, deny | Strong negative valence |
| `ANTAGONISM` | Confront, oppose, fight | Negative conduciveness + high control |
| `INTERRUPTION` | Break current action, shift focus | Low novelty (boredom) |
| `SUBMISSION` | Yield, comply, defer | Low dominance |
| `DOMINANCE` | Assert, lead, control | High dominance |
| `INDIFFERENCE` | Disengage, ignore | Low relevance |

**Type:** `ActionTendency` — record with `readiness` (ActionReadiness), `intensity` [0, 1], `target` (entity)

---

## 7. Drive System

Four-axis motivational system modelling fundamental psychological needs.

**Psychological basis:** Self-Determination Theory (Deci & Ryan) + intrinsic motivation research

### 7.1 Drive Axes

**Enum:** `DriveAxis`

| Axis | What it motivates | Low state | High state |
|------|------------------|-----------|------------|
| `CURIOSITY` | Exploration, learning, novelty-seeking | Content with known | Actively seeking new information |
| `COMPETENCE` | Mastery, skill development, effectiveness | Passive acceptance | Driven to improve and demonstrate skill |
| `AFFILIATION` | Social connection, belonging, relationship | Solitary, independent | Seeking interaction and bonding |
| `AUTONOMY` | Self-direction, choice, independence | Compliant, dependent | Asserting agency and self-governance |

### 7.2 Drive Types

| Type | Description |
|------|-------------|
| `DriveSource` | `@FunctionalInterface` — evaluates current drive intensity for an agent |
| `DriveIntensity` | Record: `axis`, `intensity` [0, 1], `trigger` (what caused it) |
| `DriveProfile` | Snapshot of all four drive intensities |
| `DriveCategory` | `BASELINE` (temperamental) or `CHARACTER` (personality-shaped) |
| `Drive` | Record: `name`, `category`, `intensity` [0, 1], `trigger` |

### 7.3 Drive Sources (implementations)

| Source | Axis | How it computes intensity |
|--------|------|--------------------------|
| `CuriosityDrive` | `CURIOSITY` | Graph staleness, novelty signals, question density |
| `CompetenceDrive` | `COMPETENCE` | Outcome success rate, confidence growth |
| `AffiliationDrive` | `AFFILIATION` | Interaction frequency, relationship quality signals |
| `AutonomyDrive` | `AUTONOMY` | Agency signals, decision frequency |

### 7.4 Drives → Goals

Drives produce goal proposals via `DriveGoalMapper` implementations. When a drive intensity exceeds a threshold, the goal proposal orchestrator creates or strengthens GOAL nodes in the MindMap. Deductive goal formation (`DeductiveGoalFormationStrategy`) synthesises holistic goals from full cognitive context.

### 7.5 Need Tiers

**Enum:** `NeedTier` — Maslow-inspired hierarchy mapping drives to need levels

| Tier | Mapped from |
|------|------------|
| `SAFETY` | (baseline) |
| `TASKS` | (baseline) |
| `SOCIAL` | `AFFILIATION` |
| `SELF_EXPRESSION` | `COMPETENCE`, `AUTONOMY` |
| `UNDERSTANDING` | `CURIOSITY` |

---

## 8. CAPS Personality System

The Cognitive-Affective Processing System models personality as a dynamic network of processing units.

**Psychological basis:** Mischel & Shoda's CAPS theory — personality is not a set of traits but a network of interconnected cognitive-affective units that produce consistent but situation-contingent behaviour.

### 8.1 Network Architecture

The CAPS topology contains approximately 74 nodes across three layers:

| Layer | Node Count | Role | Examples |
|-------|-----------|------|----------|
| **Input** (~30) | Situational encodings | Represent perceived situations | `trust_offered`, `criticism_received`, `rejection`, `achievement_opportunity`, `social_threat`, `novelty_encountered` |
| **Mediating** (~20) | Internal processing | BIS/BAS/FFFS + cognitive mediators | `bis_anxiety`, `bas_reward`, `fffs_fight`, `self_efficacy`, `rumination`, `empathic_distress` |
| **Output** (~24) | Behavioural tendencies | Observable responses | `approach`, `withdraw`, `explore`, `fight`, `submit`, `nurture`, `assert`, `analyse` |

### 8.2 Node Types

| Type | Range | Description |
|------|-------|-------------|
| `INPUT` | Clamped | Activation set by situation classifier, fixed during settling |
| `MEDIATING` | Computed | Activation propagated from inputs and other mediating nodes |
| `OUTPUT` | Computed | Terminal behavioural tendencies |

| Range | Description |
|-------|-------------|
| `BIPOLAR` | Activation in [-1, +1]. Mediating nodes with opposing poles (e.g., approach vs avoid) |
| `UNIPOLAR` | Activation in [0, 1]. Simple intensity |

### 8.3 Connection Weights

**Type:** `ConnectionWeight`

| Field | Description |
|-------|-------------|
| `excitatory` | Positive activation transfer |
| `inhibitory` | Negative activation transfer (overlay) |
| `decayResistance` | Resistance to weight decay (0 = decays quickly, 1 = permanent) |
| `precision` | Certainty of the weight (higher = resists learning updates 10x) |
| `effectiveWeight()` | `excitatory - inhibitory` |

**Weight Provenance** (`WeightProvenance`):

| Value | Description |
|-------|-------------|
| `EMPIRICAL` | Derived from psychological research data |
| `CONSENSUS` | Agreed by domain experts |
| `ESTIMATED` | Computationally estimated |

### 8.4 Settling

When input nodes are clamped (e.g., `trust_offered = 0.8`), activation propagates through the network until it converges or oscillates.

**Type:** `SettlingResult`

| Field | Description |
|-------|-------------|
| `activations` | Final activation map (node ID → value) |
| `iterations` | How many steps to converge |
| `convergence` | `CONVERGED`, `OSCILLATION`, or `MAX_ITERATIONS` |
| `saturationRatio` | Fraction of nodes at extreme activation |
| `attractors` | Recurring behavioural patterns detected |

**Type:** `CapsAttractor`

| Field | Description |
|-------|-------------|
| `category` | Behavioural category (social, approach, avoidance, etc.) |
| `nodeId` | Output node ID |
| `strength` | Pattern strength [0, 1] |
| `highSaturation` | Whether the node was at extreme activation |
| `sourceGeneration` | Which settling pass produced this attractor |

### 8.5 Cognitive Distortions

**Type:** `DistortionDefinition` — data-driven cognitive distortions that warp the settling process

| Field | Description |
|-------|-------------|
| `id` | e.g., `all_or_nothing`, `catastrophizing`, `overgeneralization` |
| `baseThreshold` | Activation level that triggers the distortion |
| `multiplierMin/Max` | Range of the distortion amplification [compound cap: 0.1, 3.0] |
| `effect` | `MULTIPLICATIVE` or `ADDITIVE` |
| `negativeWeight/positiveWeight` | Asymmetric distortion (e.g., catastrophizing amplifies negatives) |
| `targetCategories` | Which node categories are affected |

### 8.6 Situation Classification

**SPI:** `SituationClassifier` — maps experience descriptions to CAPS input node activations

| Implementation | How it works |
|---------------|-------------|
| `RuleBasedSituationClassifier` | Keyword matching against topology input node keywords. Multi-label, 0.3 confidence threshold, multi-word phrase support |
| `LlmSituationClassifier` | LLM-backed classification via AgentProvider. Topology-aware system prompt with node IDs and keywords |

### 8.7 Disposition Modulation

`DispositionWeightMapper` adjusts CAPS connection weights based on the agent's disposition axes (socialOrient, ruleFollowing, riskAppetite, autonomy, conflictMode). Four resolution types: direct connection, tag group, node threshold, bare node name.

---

## 9. Goal System

Goals are represented as typed MindMap nodes in the GOAL subgraph with lifecycle management, affect, prioritisation, and decomposition.

### 9.1 Goal Vocabulary

Pre-registered edge types for goal relationships:

| Edge Type | Aliases | Description |
|-----------|---------|-------------|
| `enables` | facilitates | One goal facilitates another |
| `blocks` | hinders, prevents | One goal prevents another |
| `requires` | depends-on | Dependency |
| `contributes-to` | supports | Partial contribution |
| `decomposes-into` | part-of | Hierarchical breakdown |

### 9.2 Goal Affect (GoalAffectPhase)

PAD affect is computed on goal nodes based on lifecycle state:

| State | PAD Effect |
|-------|-----------|
| Blocked + urgent | Frustration (negative P, high A) |
| Completed | Satisfaction (positive P) |
| Dormant | Reduced arousal |

### 9.3 Goal Urgency (GoalUrgency)

Dynamic urgency from target-date:

```
urgency = 1.0 - (remaining_time / horizon_budget)
```

| Horizon | Budget |
|---------|--------|
| immediate | 4 hours |
| short | 1 day |
| medium | 7 days |
| long | 30 days |
| aspirational | 365 days |

Falls back to static `urgency` property when no target-date.

### 9.4 Goal Priority (GoalPrioritizationPhase)

Composite priority = `w_u*urgency + w_f*feasibility + w_a*affective_valence + w_i*importance`

### 9.5 Goal Lifecycle Signals

| Signal | Description |
|--------|-------------|
| `GOAL_RECOGNIZED` | New goal detected from experience memories |
| `BLOCKER_RESOLVED` | Blocking dependency cleared |
| `URGENCY_SPIKE` | Dynamic urgency crossed threshold |
| `PRIORITY_SHIFT` | Priority ranking changed |
| `DECAY_DETECTED` | Goal becoming inactive/abandoned |

---

## 10. MindMap Knowledge Graph

The knowledge graph represents everything the agent knows about the world — people, places, goals, activities, concepts, and their relationships.

### 10.1 Subgraph Types

**Constants:** `SubgraphTypes` — 13 types

| Type | Description |
|------|-------------|
| `person` | People and agents |
| `project` | Projects, endeavours |
| `research-area` | Areas of inquiry |
| `organisation` | Companies, institutions |
| `concept` | Abstract ideas |
| `general` | Uncategorised |
| `type-system` | Schema/type nodes (internal) |
| `cognitive` | Graduated beliefs and consolidated knowledge |
| `goal` | Goals at all tiers |
| `activity` | Events, interactions, experiences |
| `place` | Physical locations |
| `behavioral` | CAPS-generated behavioural attractors |
| `cultural` | Cultural context and norms |

### 10.2 Node Structure

**Interface:** `MindMapNode` (extends `Thing`)

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique identifier |
| `name` | String | Display name |
| `subgraphId` | String | Parent subgraph |
| `subgraphType` | String | Resolved from subgraph |
| `confidence` | `Confidence` | Certainty with origin and decay |
| `provenance` | String | How the node was created |
| `createdAt/updatedAt` | Instant | Timestamps |
| `validFrom/validUntil` | Instant | Temporal validity window |
| `traits` | `Set<String>` | Semantic trait tags |
| `refs` | `Set<NodeRef>` | External references (scheme/id/qualifier) |
| `pleasure/arousal/dominance` | Double | PAD affect dimensions |
| `properties` | `Map<String, String>` | Arbitrary key-value properties |
| `principalId` | PrincipalId | Owner agent |
| `sharedWith` | `Set<String>` | Visibility sharing |

### 10.3 Edge Structure

**Interface:** `MindMapEdge`

Edges carry: `id`, `sourceNodeId`, `targetNodeId`, `edgeType`, `tier` (REGISTERED/UNVALIDATED), `confidence`, `provenance`, `createdAt`, `updatedAt`, `validFrom/validUntil`, PAD dimensions, `properties`.

### 10.4 Confidence Model

**Record:** `Confidence` — origin + value + decay reference

**Psychological basis:** Bjork's dual-strength model — retrieval strength decays but storage strength persists.

| Origin | Initial confidence | Description |
|--------|-------------------|-------------|
| `STATED` | High (0.8-1.0) | Directly stated by user or authoritative source |
| `INFERRED` | Medium (0.5-0.8) | Derived through reasoning or extraction |
| `SPECULATED` | Low (0.3-0.5) | Hypothesised, not confirmed |
| `UNKNOWN` | Variable | Origin not tracked |

**Decay:** `ConfidenceDecayDecorator` applies exponential half-life decay on read operations. Configurable via `casehub.mindmap.confidence.half-life-days` (default 30).

### 10.5 Validation Tiers

**Enum:** `ValidationTier`

| Tier | Description |
|------|-------------|
| `REGISTERED` | Edge type is from a registered vocabulary with defined semantics |
| `UNVALIDATED` | Edge type is ad-hoc, not from a vocabulary |

### 10.6 Supersession and Reinstatement

Nodes can be superseded (replaced by a more accurate version) and reinstated. `SupersessionStatus` tracks: `superseded`, `supersededAt`, `supersedingId`, `reason`, `reinstatedAt`.

### 10.7 Graph Capabilities

**Enum:** `MindMapCapability`

`TRAVERSAL`, `MERGE`, `VOCABULARY`, `ALIAS`, `SUBGRAPH`, `SEARCH`, `SUPERSESSION`, `ERASE_NODE`, `ERASE_SUBGRAPH`, `ERASE_ENTITY`, `CROSS_TENANT_ERASE`, `GRAPH_ANALYSIS`

---

## 11. Consolidation Pipeline

The consolidation system processes raw knowledge into structured, graduated understanding through a sequence of prioritised phases — analogous to sleep consolidation in neuroscience.

**Psychological basis:** Memory consolidation theory (McGaugh, Stickgold) — experiences are processed and integrated into long-term knowledge during idle periods.

### 11.1 Consolidation Phases

**SPI:** `ConsolidationPhase` — `name()`, `run(tenantId, subgraphPriority)`, `beginTick()`, `signals()`, `artifacts()`

| Priority | Phase | What it does |
|----------|-------|-------------|
| 10 | `AccessFrequencyPhase` | Flush write-behind storage strength counters from RetrievalAccessTracker |
| 12 | `SurfacingAggregationPhase` | Aggregate surfacing memory patterns across entities |
| 15 | `ExperienceConsolidationPhase` | Graduate raw experiences to typed COGNITIVE nodes (beliefs, intentions, judgments) when corroborated |
| 16 | `SubThoughtConsolidationPhase` | Graduate cross-memory sub-thought patterns to MindMap nodes |
| 19 | `BehavioralSynthesisPhase` | Classify graduated experiences into CAPS situations, settle CAPS, create BEHAVIORAL attractor nodes |
| 20 | `MergeDetectionPhase` | Detect duplicate nodes via name similarity (Jaro-Winkler ≥ 0.85) + neighbour overlap (Jaccard ≥ 0.3) |
| 25 | `SchemaDiscoveryPhase` | Discover property patterns across entities of the same type → type schema |
| 30 | `CommunitySummaryPhase` | K-core clustering + LLM summary generation with hash-based invalidation |
| 35 | `GoalResolutionPhase` | Prune distant goals, expand approaching ones, merge shared sub-goals, sync lifecycle |
| 37 | `GoalAffectPhase` | Compute PAD affect on goal nodes based on lifecycle state |
| 38 | `GoalPrioritizationPhase` | Composite priority scoring + inactive goal decay detection |
| 40 | `CuriosityRefreshPhase` | Generate curiosity-driven attention signals |
| 45 | `GoalRecognitionPhase` | Scan experience memories for implicit goals, create GOAL nodes |

### 11.2 Attention Signals

**Enum:** `SignalCategory` — 11 categories of cognitive attention signals

| Signal | Description |
|--------|-------------|
| `URGENCY_SPIKE` | Something needs immediate attention |
| `DECAY_DETECTED` | Knowledge becoming stale |
| `GOAL_RECOGNIZED` | New goal detected from experience |
| `BLOCKER_RESOLVED` | Goal dependency cleared |
| `PRIORITY_SHIFT` | Goal priority changed significantly |
| `AFFECT_CHANGE` | PAD delta exceeded threshold (> 0.3) |
| `MERGE_CANDIDATE` | Duplicate nodes detected |
| `EXPERIENCE_GRADUATED` | Raw experience promoted to stable knowledge |
| `DRIVE_SHIFT` | Motivational state changed |
| `RELATIONSHIP_STAGE` | Relationship progressed to new stage |
| `BELIEF_REVISED` | Previously stable belief updated |

**Types:** `AttentionSignal` (principalId, tenantId, category, sourceNodeId, sourceName, significance, reason), `AttentionBriefing` (ranked signal snapshot with urgencyP75)

### 11.3 Graduation

**SPIs:** `GraduationScorer` — scoring worthiness; `GraduationClassifier` — classification into cognitive kinds

| Classification | From | Cognitive kind |
|---------------|------|---------------|
| `observation` | → `belief` | Stable knowledge |
| `action` | → `intention` | Persistent behavioural pattern |
| `outcome` | → `judgment` | Evaluative assessment |

---

## 12. Personality Configuration

The `CognitiveDefaults` record holds all per-agent cognitive configuration — both explicit (YAML) and derived (from disposition).

### 12.1 CognitiveDefaults Fields (18 fields)

**Record:** `CognitiveDefaults`

| Field | Type | Description |
|-------|------|-------------|
| `agentId` | String | Agent identifier |
| `tenantId` | String | Tenant isolation |
| `personality` | `PersonalityWeights` | Per-domain memory weight multipliers |
| `moodBaseline` | `MoodBaseline` | Emotional resting point (PAD) |
| `curiosity` | `CuriosityConfig` | Curiosity signal tuning (11 fields) |
| `temporalFocus` | `TemporalFocusConfig` | Temporal attention weighting |
| `cbrStrategy` | `CbrStrategyDefaults` | CBR retrieval parameters |
| `socialCognition` | `SocialCognitionDefaults` | Trust and conflict behaviour |
| `graphStructure` | `GraphStructureDefaults` | Knowledge graph organisation style |
| `extractionBias` | `ExtractionBiasDefaults` | LLM extraction emphasis |
| `vocabulary` | `MindMapVocabulary` | Pre-registered edge types |
| `services` | `Map<String, String>` | Named SPI selection |
| `traitRules` | `List<DeclarativeTraitRule>` | Declarative trait assignment rules |
| `derivedEdgeRules` | `List<DeclarativeDerivedEdgeRule>` | Forward-chaining edge rules |
| `descriptor` | `DescriptorView` | Eidos identity (disposition axes, profile, goals) |
| `appraisalWeights` | `AppraisalWeights` | SEC dimension sensitivity |
| `habituationConfig` | `HabituationConfig` | Novelty/repetition tuning |
| `personalityDominance` | `Double` | Personality dominance influence |

### 12.2 Derivation Engine

`CognitiveDerivationEngine` derives cognitive defaults from disposition axes — 9 derivation pathways:

| From | To | Method |
|------|----|--------|
| Jungian disposition profile | `PersonalityWeights` | Weighted average over memory domains |
| Disposition axes | `MoodBaseline` | PAD mapping from social/risk/conflict axes |
| Disposition + goals | `CuriosityConfig` | Category weights from interest areas |
| Goals | `TemporalFocusConfig` | Subgraph proximity weights |
| ruleFollowing + riskAppetite | `CbrStrategyDefaults` | minSimilarity, temporal decay |
| socialOrient + conflictMode | `SocialCognitionDefaults` | Trust formation rate, conflict interpretation |
| Disposition profile | `GraphStructureDefaults` | Inference style (CONNECTIVE/CATEGORICAL/BALANCED) |
| Disposition profile | `ExtractionBiasDefaults` | Relationship vs affect extraction emphasis |
| Disposition profile | `AppraisalWeights` | JPAF function→Big Five→appraisal chain |

### 12.3 Habituation

**Record:** `HabituationConfig`

| Field | Default | Description |
|-------|---------|-------------|
| `habituationRate` | 0.2 | How quickly stimuli become familiar |
| `noveltyThreshold` | 0.3 | Below this = habituated |
| `repetitionTolerance` | 5.0 | How many repeats before boredom |
| `domainModulation` | `{}` | Per-domain habituation adjustments |

**Type:** `HabituationState` — immutable observation counts + novelty scores per stimulus hash. `withObservation(hash, novelty)` returns new state.

---

## 13. Case-Based Reasoning (CBR)

CBR enables learning from past experience through structured similarity retrieval and outcome-based adaptation.

**Psychological basis:** Case-Based Reasoning (Kolodner, Aamodt & Plaza) — the R4 cycle: Retrieve, Reuse, Revise, Retain.

### 13.1 Record Hierarchy

**Interface:** `CbrRecord`

| Implementation | What it stores |
|---------------|---------------|
| `CbrFeatureRecord` | Feature-vector cases with typed fields |
| `CbrPlanRecord` | Step-sequence plans (for procedural CBR) |
| `CbrGuidanceRecord` | Guidance steps (for advisory CBR) |

### 13.2 Feature Types

**Sealed:** `FeatureField` — schema for typed case features

| Field Type | Description |
|-----------|-------------|
| `Categorical` | Discrete label values with optional `CategoricalTable` similarity |
| `Numeric` | Continuous values with min/max and optional decay similarity |
| `Text` | Free-text with optional semantic flag |
| `CategoricalList` | List of labels |
| `NumericList` | List of numbers with per-element range validation |
| `NestedObject` | Structured sub-object (one level) |
| `ObjectList` | List of structured sub-objects |
| `TimeSeries` | Ordered observations with timestamp + numeric fields (DTW similarity) |
| `DiscreteSequence` | Ordered list of labels (edit distance similarity) |

**Sealed:** `FeatureValue` — typed feature values

`StringVal`, `NumberVal`, `RangeVal`, `StringListVal`, `NumberListVal`, `StructVal`, `StructListVal`

### 13.3 Similarity Scoring

**Sealed:** `SimilaritySpec` — schema-attached similarity functions

| Spec | Description |
|------|-------------|
| `CategoricalTable` | Lookup table for categorical pairs |
| `GaussianDecay` | Gaussian distance decay for numerics |
| `StepDecay` | Step function decay |
| `ExponentialDecay` | Exponential distance decay |
| `DtwSpec` | Dynamic Time Warping for time series (with `WarpingConstraint`: Unconstrained, SakoeChibaBand, ItakuraParallelogram) |
| `EditDistanceSpec` | Levenshtein edit distance for sequences (with weighted substitution costs) |

### 13.4 Retrieval Modes

**Enum:** `RetrievalMode`

| Mode | Description |
|------|-------------|
| `FEATURE_ONLY` | Pure structural similarity on features |
| `SEMANTIC_ONLY` | Vector embedding similarity on problem text |
| `HYBRID` | Both, fused via RRF/CC |

### 13.5 Temporal and Scope Decay

**Sealed:** `TemporalDecay` — recency weighting

| Type | Description |
|------|-------------|
| `HalfLife(Duration)` | Exponential half-life decay |
| `Linear(Duration zeroAt)` | Linear decay to zero |
| `Step(Duration cutoff, double afterCutoff)` | Step function |

**Sealed:** `ScopeDecay` — hierarchical scope distance weighting

`Exponential(double base)`, `Linear(int maxDepth)`, `Step(double beyondExact)`

### 13.6 Outcome Feedback (Revise)

**Type:** `CbrOutcome` — records whether a retrieved case was successful

| Outcome | Description |
|---------|-------------|
| `SUCCESS` | Case worked perfectly |
| `PARTIAL` | Case partially worked |
| `FAILURE` | Case did not work |

Confidence adjustment via Exponential Moving Average (EMA): `new = (1 - α) * old + α * successRate` (default α = 0.2)

### 13.7 Plan Adaptation

**Enum:** `AdaptationAction` — what happened to each step during reuse

| Action | Description |
|--------|-------------|
| `RETAINED` | Step used as-is |
| `SUBSTITUTED` | Step replaced with alternative |
| `BOOSTED` | Step prioritised higher |
| `SUPPRESSED` | Step deprioritised |
| `ADDED` | New step inserted |
| `REMOVED` | Step eliminated |

### 13.8 Ensemble Analysis

**Type:** `StepAgreement` — consensus across multiple adapted plans

| Agreement | Description |
|-----------|-------------|
| `UNANIMOUS` | All plans agree |
| `CONSENSUS` | Most plans agree |
| `CONTESTED` | Split opinion |
| `MINORITY` | Few plans include this step |
| `UNIQUE` | Only one plan has this step |

---

## 14. Cognitive Profile

Cross-store entity resolution that unifies MindMap, memory, and affect data into a single `EntityKnowledge` record.

**Type:** `CognitiveProfile` — `@ApplicationScoped`, resolves entity knowledge across all cognitive stores

| Method | Description |
|--------|-------------|
| `resolve(CognitiveProfileQuery)` | Unified entity view: node + edges + memories across 6 domains + affect trajectory |
| `compare(query, Set<PrincipalId>)` | Multi-agent perspectival comparison |

### 14.1 Perspectival Resolution

Each agent can have a private overlay on shared knowledge. `PerspectivalMerge` merges shared node + private overlay into a single perspectival node where overlay PAD/confidence/properties win when non-null.

### 14.2 Social Comparison

`SocialComparison` computes multi-agent divergence metrics:
- PAD distance matrix (pairwise Euclidean)
- Signed pairwise differences with canonical `AgentPair` ordering
- 3D slope vector cosine similarity for trajectory alignment

---

## 15. Domain Activation

Cross-subgraph affect correlation — discovers how emotional activity in one knowledge domain (e.g., PERSON) correlates with activity in another (e.g., GOAL).

**Type:** `DomainActivation` — time-bucketed 3D PAD aggregation per subgraph, pairwise DTW similarity with alignment

**Analysis types:**
- **Mood correlation:** DTW between mood trajectory and domain affect, with context partitioning and Sakoe-Chiba banding
- **Event-triggered:** Δ(affect) pre/post experience events per PAD dimension, with bootstrap 95% confidence intervals

**Correlation strength:** `STRONG`, `MODERATE`, `WEAK`, `NONE` (significance guard: p ≥ 0.05 downgrades STRONG/MODERATE to WEAK)

---

## 16. Relationship Model

Per-agent-pair interaction tracking with quality signals and relationship stage progression.

### 16.1 Types

| Type | Description |
|------|-------------|
| `RelationshipEvent` | Record: agentId, otherAgentId, sourceEventType, qualitySignal, self-referential rejection |
| `QualitySignal` | `POSITIVE`, `NEGATIVE`, `NEUTRAL` |
| `StageTier` | Named relationship stage with threshold (e.g., "stranger" at 0.0, "acquaintance" at 0.2, "trusted" at 0.6) |
| `RelationshipStagePhase` | Consolidation phase that computes relationship progression via `BeliefRevisionPhase` |

### 16.2 Trust

**SPI:** `AgentTrustProvider` — current trust score lookup. `TrustWeightingFunction` modulates CBR retrieval scores by source trust authority.

---

## 17. Reflection

Hierarchical self-reflection — synthesised insights from raw experience memories.

| Type | Description |
|------|-------------|
| `ReflectionEvent` | Record: agentId, insight text, level (hierarchical depth), sourceMemoryIds |
| `ReflectionSynthesizer` | `@FunctionalInterface` — produces reflections from source memories |
| `LlmReflectionSynthesizer` | LLM-backed implementation |

Confidence defaults from level: `Math.min(0.3 + level * 0.2, 1.0)` — higher levels = greater confidence.

---

## 18. Gut Feeling

Rapid pre-cognitive intuition — a two-layer probe that fires before full appraisal.

**Psychological basis:** Damasio's somatic marker hypothesis — bodily signals guide rapid decision-making before conscious reasoning.

### 18.1 Types

**Record:** `GutFeeling` — `valence`, `intensity` [0, 1], `resonanceDescription` (nullable)

**Enum:** `GutValence`

| Value | Description |
|-------|-------------|
| `APPROACH` | Positive somatic signal — lean into this |
| `AVOID` | Negative somatic signal — pull away |
| `CAUTIOUS` | Ambiguous signal — proceed carefully |

### 18.2 Two-Layer Probe

1. **Layer 1:** Experience-memory semantic search + PAD aggregation from past similar situations
2. **Layer 2:** PAD mood-distance fallback — Euclidean distance ≥ 0.3 threshold triggers gut signal

---

## 19. Thing / Traits

**Interface:** `Thing` — semantic knowledge representation base

| Method | Description |
|--------|-------------|
| `id()` | Unique identifier |
| `name()` | Display name |
| `type()` | Semantic type |
| `properties()` | Key-value properties |
| `traits()` | Semantic trait tags |
| `is(String trait)` | Check if entity has a trait |
| `as(Class<T>)` | Typed trait access via JDK Proxy |

### 19.1 Trait Interfaces

Trait interfaces define typed property access on MindMap nodes:

| Trait | Properties exposed | Applied to |
|-------|-------------------|-----------|
| `Personable` | role, occupation, age, email, phone | person nodes |
| `Projectlike` | status, deadline, description | project nodes |
| `Organisational` | industry, size, founded | organisation nodes |
| `Eventlike` | date, location, duration | activity nodes |
| `Goallike` | horizon, priority, status, urgency, feasibility, target-date, need-tier | goal nodes |
| `Locatable` | latitude, longitude, address, city, country | place nodes |
| `Reviewable` | rating, review-date, reviewer | reviewed items |
| `Temporal` | start-date, end-date, recurrence | time-bound items |
| `Belieflike` | evidence-count, source, revision-count | cognitive beliefs |
| `Fearlike` | threat-level, coping-strategy | fears/concerns |
| `Evaluative` | stance, certainty | judgments |
| `Predictive` | horizon, expected-outcome, basis | predictions |

### 19.2 Trait Rules

`TraitRule` implementations automatically assign traits based on node properties and subgraph type. Example: `PersonableTraitRule` adds the "personable" trait to PERSON subgraph nodes.

---

## 20. Cognition Pipeline

The cognitive processing pipeline that fires on each interaction turn.

### 20.1 Phases

**Enum:** `CognitionPhase`

| Phase | Order | What runs |
|-------|-------|----------|
| `FOUNDATION` | 1st | Mood orchestrator, drive sources |
| `SOURCE` | 2nd | Mental model, user model |
| `SOURCE_PER_SUBJECT` | 3rd | Per-subject orchestrators |
| `DERIVED` | 4th | Appraisal (CARMA), gut feeling, goals, narrative, strategy, behavioural |
| `TERMINAL` | 5th | Inner life (proactive speech, reflection), consolidation, attention |

### 20.2 Cognitive Subsystems (CognitionConfig)

23 independently toggleable subsystems:

| Subsystem | What it does |
|-----------|-------------|
| `mood` | PAD mood tracking and decay |
| `drives` | 4-axis motivational system |
| `mentalModel` | BDI theory of mind per subject |
| `userModel` | Per-subject interaction profiles |
| `strategy` | Engagement-driven strategy adaptation |
| `narrative` | Identity narrative composition |
| `goals` | Drive→goal formation with escalation |
| `memoryHygiene` | Knowledge gap detection |
| `innerLife` | Proactive speech and reflection |
| `directivePrompts` | Directive prompt sections |
| `characterDrives` | Character-specific drive modulation |
| `needsPyramid` | Need tier rendering |
| `attention` | Attention signal processing |
| `temporalFocus` | Temporal proximity attention |
| `reflection` | Hierarchical reflection synthesis |
| `consolidation` | Background knowledge consolidation |
| `entityKnowledge` | Entity profile resolution |
| `perspectiveComparison` | Multi-agent perspective comparison |
| `domainActivation` | Cross-subgraph affect correlation |
| `appraisal` | CARMA SEC appraisal pipeline |
| `salience` | Perception filtering |
| `behavioral` | CAPS behavioural attractor rendering |
| `subThoughts` | Sub-thought decomposition |

### 20.3 Mental Model (BDI)

**Psychological basis:** Bratman's BDI (Belief-Desire-Intention) model — theory of mind per subject

**Enum:** `BdiDimension`: `BELIEF`, `DESIRE`, `INTENTION`

**Enum:** `EpistemicStatus`: `ESTABLISHED`, `PENDING`, `DISPUTED`

---

## 21. Temporal Marks

**Sealed:** `TemporalMark` — polymorphic time representation

| Type | Fields | Description |
|------|--------|-------------|
| `WallClock` | `instant` | Absolute timestamp |
| `Relative` | `offset`, `anchor` | Relative to now or an anchor point |
| `Ordinal` | `turnId`, `resolved` | Linked to a conversation turn |

---

## 22. Cross-Subsystem Integration Map

```
Experience Events → Memory Store → Consolidation Pipeline → MindMap Knowledge Graph
       ↓                                    ↓                         ↓
  CARMA Appraisal                    Graduation                   Traits
       ↓                                    ↓                         ↓
  OCC Emotions → PAD Projection      Beliefs/Intentions       Entity Knowledge
       ↓                                    ↓                         ↓
  Action Tendencies                  CAPS Behavioural           Cognitive Profile
       ↓                            Synthesis                        ↓
  Mood Update                            ↓                    Social Comparison
       ↓                         Attractors                          ↓
  Drive Modulation                       ↓                    Domain Activation
       ↓                    BEHAVIORAL subgraph
  Goal Formation
       ↓
  Attention Signals → Attention Briefing
```

Key integration points:
- **Appraisal→Mood:** Emotion intensity-weighted PAD averaging updates mood state
- **Mood→Drive:** Mood congruence modulates drive intensity perception
- **Drive→Goals:** Drives above threshold produce goal proposals
- **Goals→MindMap:** Goals live as GOAL subgraph nodes with lifecycle edges
- **Experience→Consolidation:** Raw memories graduate to stable MindMap knowledge
- **Consolidation→CAPS:** Graduated experiences are classified into situations and settled through the CAPS network
- **CAPS→Behavioral:** Settled attractors become persistent BEHAVIORAL subgraph nodes
- **Gut feeling→Behavioral:** Pre-cognitive somatic signals influence prompt rendering alongside crystallised CAPS attractors
- **Confidence→Decay:** All knowledge decays over time via half-life, renewed by retrieval access
- **CBR→Strategy:** Case-based reasoning informs strategy learning via outcome feedback
