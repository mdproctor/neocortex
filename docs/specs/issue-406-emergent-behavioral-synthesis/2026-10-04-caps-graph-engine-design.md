# CAPS Graph Engine — Implementation Design

**Issue:** casehubio/neocortex#408
**Parent epic:** casehubio/neocortex#406 — Emergent Behavioral Synthesis
**Date:** 2026-10-04
**Research spec:** docs/specs/issue-407-psychology-cause-effect-models/2026-10-02-psychology-cause-effect-models-design.md

## 1. Overview

The CAPS (Cognitive-Affective Processing System) graph engine provides
mechanical behavioral inference for the neocortex cognitive stack. It
encodes six psychological models (Attachment, BIS/BAS, CBT, Trauma,
Operant Conditioning, Social Learning) as a spreading activation network
with pre-defined, psychologically-grounded connection weights. Per-agent
weight overlays personalise the network based on experience history and
personality disposition.

The engine is a computational structure separate from MindMap. It owns
the CAPS network topology, per-agent weight state, settling algorithm,
and weight update rules. Its output — behavioral attractor nodes — is
written to the MindMap BEHAVIORAL subgraph during consolidation.

**Scope:** §5.9 requirements contract from the research spec, minus
topology migrations (deferred — see casehubio/neocortex#415).
Plus: BehavioralSynthesisPhase (ConsolidationPhase @Priority 19),
CognitiveEmergenceTest framework, and data-driven cognitive distortion
mechanics.

## 2. Module Structure

```
caps-api/               — SPIs, topology types, value objects
caps-engine/            — Settling, weight storage, SQLite
caps-testing/           — Contract tests, in-memory backend, CognitiveEmergenceTest
caps-llm-classifier/    — Optional: LLM situation classification fallback
```

BehavioralSynthesisPhase lives in `cognition` (not caps-engine), following
the same pattern as BeliefRevisionPhase, DriveAdaptationPhase, and
RelationshipStagePhase. This avoids a dependency from caps-engine to
mindmap-intelligence and keeps caps-engine at the same dependency level
as mindmap-intelligence.

### 2.1 Dependency Graph

```
caps-api → mindmap-api, cognitive-api
caps-engine → caps-api, sqlite-support, jackson-dataformat-yaml
caps-testing → caps-api, caps-engine, memory-seeding (BackstorySeeder),
               mindmap-inmem, memory-inmem
caps-llm-classifier → caps-api (SituationClassifier SPI),
                       casehub-platform-agent-api (AgentProvider)
cognition → caps-engine, mindmap-intelligence (ConsolidationPhase SPI),
            memory-api (ExperienceEvent, CaseMemoryStore)
            [BehavioralSynthesisPhase lives here]
```

caps-engine is at the same dependency level as mindmap-intelligence:
it depends on mindmap-api and cognitive-api but NOT on cognition or
mindmap-intelligence. BehavioralSynthesisPhase in cognition bridges the
two by depending on both caps-engine (CapsEngine SPI) and
mindmap-intelligence (ConsolidationPhase SPI, MindMapStore).

### 2.2 Maven Coordinates

| Element | artifactId |
|---|---|
| CAPS API | `casehub-neocortex-caps-api` |
| CAPS Engine | `casehub-neocortex-caps-engine` |
| CAPS Testing | `casehub-neocortex-caps-testing` |
| CAPS LLM Classifier | `casehub-neocortex-caps-llm-classifier` |
| Root Java package | `io.casehub.neocortex.caps` |

## 3. caps-api — Types and SPIs

### 3.1 Topology Types

```java
// Species-level CAPS network topology — loaded from YAML, shared by all agents
public record CapsTopology(
    int version,
    Map<String, CapsNode> nodes,          // nodeId → node
    List<CapsConnection> connections,
    Map<String, DispositionModifier> dispositionModifiers,  // axis → modifier
    List<DistortionDefinition> distortions,
    WeightUpdateParameters weightUpdate
) {}

public record CapsNode(
    String id,
    NodeType type,        // INPUT, MEDIATING, OUTPUT
    NodeRange range,      // BIPOLAR [-1,1], UNIPOLAR [0,1]
    String category,      // e.g., "self_model", "threat", "conflict"
    List<String> sourceModels,
    List<String> keywords // for SituationClassifier (INPUT nodes only; empty for others)
) {}

// NodeRange convention: mediating nodes specify type/range explicitly in the
// topology YAML. Input and output nodes default to UNIPOLAR [0,1]:
//   - Input nodes represent stimulus activation (presence/intensity of a situation)
//   - Output nodes represent behavioral tendency strength, consistent with
//     BehavioralAttractor.strength being [0, 1]
// CapsTopologyLoader assigns UNIPOLAR when no explicit range is specified.

public record CapsConnection(
    String id,            // deterministic: "from__to"
    String from,
    String to,
    double defaultWeight,
    WeightProvenance provenance,  // EMPIRICAL, CONSENSUS, ESTIMATED
    String source,        // citation
    List<String> tags     // for disposition modulation resolution
) {}

public enum WeightProvenance { EMPIRICAL, CONSENSUS, ESTIMATED }

// Disposition modulation for a single axis (e.g., "socialOrient")
// Maps axis values (e.g., "cooperative") to their modifier sets
public record DispositionModifier(
    Map<String, Map<String, Double>> valueModifiers  // axisValue → {modifierName → multiplier}
) {}

// Weight update and convergence parameters — loaded from topology YAML
public record WeightUpdateParameters(
    double alphaMin,          // 0.01 — minimum learning rate
    double alphaMax,          // 0.25 — maximum learning rate
    double betaMin,           // 0.1 — minimum outcome intensity
    double betaMax,           // 0.5 — maximum outcome intensity
    double temporalDiscount,  // 0.95
    Map<String, Double> scheduleModifiers,  // reinforcement schedule → multiplier
    double vicariousDiscount, // 0.20
    int maxIterations,        // 100
    double epsilon,           // 0.001
    double[] effectiveWeightCap,     // [-2.0, 2.0]
    double[] compoundDistortionCap,  // [0.1, 3.0]
    double saturationWarningThreshold // 0.60
) {}
// Note: activation_clamp is NOT configurable — clamping is per-node-type
// (BIPOLAR [-1,+1], UNIPOLAR [0,+1]) and algorithmic, not a parameter.
```

### 3.2 Per-Agent Weight State

```java
// Per-agent overlay on a single connection
public record ConnectionWeight(
    double excitatory,        // learned association weight
    double inhibitory,        // extinction overlay (decays faster)
    double decayResistance,   // how slowly this connection decays (default 1.0)
    double precision          // learning rate modulator (high = resist change)
) {
    public double effectiveWeight() {
        return excitatory - inhibitory;
    }
}

// Sparse storage: SQLite stores only connections that have been modified by
// disposition modulation or experience learning. For connections with no
// per-agent entry, the topology's per-connection defaultWeight is used
// (excitatory = defaultWeight, inhibitory = 0.0, decayResistance = 1.0,
// precision = provenance default). The comparison target is always
// topology-relative, not a global constant.

// Per-agent node state
public record NodeState(
    double activationThreshold,  // lowered by trauma sensitization
    double restingActivation     // disposition-derived starting point for each settling cycle
) {
    public static final NodeState DEFAULT = new NodeState(0.5, 0.0);
}

// Complete per-agent CAPS state
public record AgentCapsState(
    String agentId,
    String tenantId,
    long generation,          // monotonic counter for two-store consistency
    Map<String, ConnectionWeight> weights,   // connectionId → weight
    Map<String, NodeState> nodeStates        // nodeId → state
) {}
```

### 3.3 Settling Result

```java
// Result of CAPS parallel constraint satisfaction settling
public record SettlingResult(
    Map<String, Double> activations,    // nodeId → final activation (last iteration)
    int iterations,                      // convergence iterations used
    ConvergenceType convergence,         // CONVERGED, OSCILLATION, MAX_ITERATIONS
    double saturationRatio,              // fraction of nodes at ceiling
    List<BehavioralAttractor> attractors // derived output
) {}
// During OSCILLATION: activations contains the last computed state (iteration N).
// The dual attractor states from iterations N-1 and N are captured in the
// attractors list (two sets with relative strength 0.5 each). Diagnostics
// and test assertions should use attractors, not activations, when
// convergence == OSCILLATION.

public enum ConvergenceType { CONVERGED, OSCILLATION, MAX_ITERATIONS }

public record BehavioralAttractor(
    String category,        // output node category
    String nodeId,          // output node that won
    double strength,        // activation level [0, 1]
    boolean highSaturation, // >60% mediating nodes saturated
    long sourceGeneration   // CAPS generation that produced this
) {}
```

### 3.4 Distortion Definitions

```java
// Cognitive distortion — data-driven, defined in topology YAML
public record DistortionDefinition(
    String id,                  // e.g., "all_or_nothing"
    double baseThreshold,       // activation threshold (0.4–0.8)
    double multiplierMin,       // e.g., 1.5
    double multiplierMax,       // e.g., 2.0
    DistortionEffect effect,    // MULTIPLICATIVE or ADDITIVE
    double negativeWeight,      // polarity weighting (default 1.0)
    double positiveWeight,      // polarity weighting (default 0.3)
    List<String> targetCategories // mediating node categories this distortion applies to
                                  // e.g., ["self_model", "world_model", "response_outcome"]
) {}

public enum DistortionEffect { MULTIPLICATIVE, ADDITIVE }
```

### 3.5 SituationClassifier SPI

```java
// Maps experience descriptions to CAPS input node activations
@FunctionalInterface
public interface SituationClassifier {
    List<SituationActivation> classify(String description,
                                        Map<String, String> metadata);
}

public record SituationActivation(
    String nodeId,       // CAPS input node
    double confidence    // [0, 1] — below 0.3 discarded
) {}
```

### 3.6 CapsEngine SPI

```java
// Core engine interface — topology + per-agent weight management + settling
public interface CapsEngine {
    CapsTopology topology();

    // Per-agent state
    AgentCapsState loadState(String tenantId, String agentId);
    void saveState(AgentCapsState state);  // atomic write
    AgentCapsState initializeAgent(String tenantId, String agentId,
                                   DispositionAxes disposition);

    // Settling
    SettlingResult settle(AgentCapsState state,
                          Map<String, Double> inputActivations);

    // Weight update (Rescorla-Wagner + Pearce-Hall)
    // schedule_modifier resolved internally from topology's schedule_modifiers
    // table using the reinforcementSchedule parameter.
    // disposition_modifier is NOT applied per-update — disposition modulation
    // affects initial weights (§4.6) and resting activations, not the learning
    // rate per update. The precision field on ConnectionWeight serves the same
    // role: provenance-based precision anchors resist change proportionally.
    AgentCapsState updateWeights(AgentCapsState state,
                                 Map<String, Double> inputActivations,
                                 double outcomeIntensity,
                                 double outcomeValence,
                                 double salienceMultiplier,
                                 String reinforcementSchedule);
}
```

## 4. caps-engine — Implementation

### 4.1 Topology Loading

`CapsTopologyLoader` loads the species-level topology from
`caps-topology.yaml` on the classpath (a versioned copy of
`docs/specs/2026-10-02-caps-topology.yaml`). Jackson YAML parsing
consistent with `CatalogueLoader` in memory-seeding.

The topology YAML is extended with:
- `tags` field on connections (for disposition modulation)
- `distortions` section (data-driven distortion definitions)

### 4.2 Settling Algorithm

Synchronous parallel update with convergence detection. Activations
clamped per node type after each step: BIPOLAR [-1, +1], UNIPOLAR [0, +1].
From #407 spec §2.1:

```
initialize all nodes to restingActivation (from NodeState)
apply inputActivations to input nodes (override resting values)

for each iteration (max 100):
    for each MEDIATING and OUTPUT node (INPUT nodes are clamped):
        input = Σ(connected_node_activation × effective_weight)
        apply distortion modifiers (§4.3)
        new_activation = activation_function(input - threshold)
        clamp per node range: BIPOLAR to [-1, +1], UNIPOLAR to [0, +1]
    check convergence: max |Δactivation| < ε (0.001)
    check oscillation: activation vector matches N-2 within ε
    if converged: extract single attractor state, stop
    if oscillating: extract dual attractor states (see below), stop
if max iterations: take most-settled state from last 10
```

**Input node clamping:** INPUT nodes maintain their externally-set
activations throughout settling — they are NOT updated by the iteration
loop. This is standard PCS behavior (Shoda, LeeTiernan & Mischel, 2002;
PCSinR's `pcs_settle()` takes a `source` parameter for clamped nodes).
Input nodes have no incoming connections in the topology (all connections
go FROM input nodes TO mediating nodes), so recomputing them would
produce σ(0 - threshold) ≈ 0.38, destroying the classified signal.
Convergence and oscillation checks also exclude input nodes — they are
constants, not variables, in the settling computation.

**Activation function per node type:**
- UNIPOLAR nodes: standard logistic σ(x) = 1/(1 + e^(-kx)),
  output [0, 1]. k = 1.0 (gain parameter).
- BIPOLAR nodes: tanh(kx), output [-1, +1]. k = 1.0.

The PCSinR reference uses a similar per-type approach. The gain
parameter k controls steepness; 1.0 is the standard default.
Calibration may adjust k per node category.

**Oscillation → peak-attractor extraction:** When oscillation is
detected, attractors are extracted from peak activations (the maximum
each output node achieves across the oscillation window) rather than
the averaged state. The averaged state dilutes oscillating outputs
below the attractor threshold, producing zero attractors. Peak-based
extraction captures what the network activates during each oscillation
phase — psychologically valid per Mischel & Shoda (1995): oscillation
between competing tendencies IS a meaningful cognitive-affective state.

Three detection paths exist:
1. **Period-2** (activation vector at iteration N matches N-2 within ε)
2. **Near-convergence** (window variance < ε×50 after 20+ iterations)
3. **Multi-cycle at MAX_ITERATIONS** (#1027: when output node amplitude
   exceeds ε×10 at the iteration limit, reclassify as OSCILLATION)

Attractor nodes from oscillation carry `convergence-type=oscillation`
metadata. `BehavioralPromptSection` renders these as tension ("competing
pull") rather than stable tendencies ("deeply ingrained"). The property
is cleared when subsequent settling converges normally.

### 4.3 Distortion Application (During Settling)

Distortions are applied generically from `DistortionDefinition` data.
Each distortion fires only on mediating nodes whose `category` matches
the distortion's `targetCategories` — e.g., catastrophizing targets
`["self_model", "world_model", "response_outcome"]` but not
`["affect_systems", "social_cognition"]`. This reflects CBT: cognitive
distortions amplify specific schema domains, not arbitrary nodes.

**Polarity-weighted threshold formula:** For each candidate mediating
node (category matches), compute the effective threshold:

```
effective_threshold = baseThreshold × (1 − negativeWeight × max(0, −activation)
                                         − positiveWeight × max(0, activation))
```

Where `negativeWeight` (default 1.0) and `positiveWeight` (default 0.3)
produce asymmetric activation: a strongly negative node (activation = -0.8,
baseThreshold = 0.6) yields effective_threshold = 0.6 × (1 - 1.0 × 0.8) =
0.12, triggering easily. A strongly positive node (activation = +0.8)
yields 0.6 × (1 - 0.3 × 0.8) = 0.456, much harder to trigger. This
asymmetry reflects Beck's model: distortions primarily maintain negative
schemas.

The distortion fires when `|activation| > effective_threshold`.

**Multiplier interpolation:** When a distortion fires, the actual
multiplier is linearly interpolated between `multiplierMin` and
`multiplierMax` based on how far above threshold the activation is:

```
t = (|activation| − effective_threshold) / (1.0 − effective_threshold)
multiplier = multiplierMin + t × (multiplierMax − multiplierMin)
```

At exactly threshold → `multiplierMin`. At |activation| = 1.0 →
`multiplierMax`.

**Application steps:**
1. For each mediating node with matching `targetCategories`, check if
   `|activation| > effective_threshold` (polarity-weighted, as above)
2. For active distortions with MULTIPLICATIVE effect: multiply
   outgoing connection weights by the interpolated multiplier
3. For active distortions with ADDITIVE effect: add temporary
   connections at the specified weight
4. Cap compound multiplicative factor to [0.1, 3.0]
5. Cap effective weights to [-2.0, +2.0]

The engine reads distortion definitions from the topology — it doesn't
know about "catastrophizing" or "mental filtering" by name. Adding
distortions from new psychological models requires only topology YAML
changes, not engine code changes.

### 4.4 Weight Update (Rescorla-Wagner + Pearce-Hall)

From #407 spec §5.1:

```
ΔW = (base_α / precision) × β_outcome × (λ − Σ_k W_kj × a_k)
     × schedule_modifier × disposition_modifier
```

Where `precision` modulates the effective learning rate:
- High precision (empirical weights): slow to change
- Low precision (estimated weights): fast to adapt
- Precision increases when prediction error is small (confirming)
- Precision decreases when prediction error is large (surprising)

**Deviation from research spec:** The research spec §5.1 uses Pearce-Hall
`α_i` (dynamic salience) directly. This implementation replaces `α_i` with
`base_α / precision`, which is functionally equivalent (surprising events
→ lower precision → higher effective α). The provenance-based initial
precision (§4.6 step 5: EMPIRICAL=10.0, CONSENSUS=5.0, ESTIMATED=1.0)
is a novel extension that anchors the learning rate to empirical confidence
in the initial weight value — empirically-grounded weights resist change
10× more than estimated weights. This prevents casual experience from
overwriting well-established effect sizes while allowing estimated weights
to adapt quickly to evidence.

Precision update:
```
if |prediction_error| < ε_confirm:
    precision += precision_growth_rate    // evidence confirms
else:
    precision = max(0.1, precision × precision_decay_factor)  // surprised
```

### 4.5 Dual-Weight Architecture

Every connection carries excitatory + inhibitory overlay + decay
resistance (§5.2). Inhibitory overlays decay faster via:

```
inhibitory_new = inhibitory_old × (1 − base_decay_rate / effective_resistance)
effective_resistance = 1.0 + (decay_resistance − 1.0) × 0.5
```

This models spontaneous recovery of extinguished behaviors.

### 4.6 Agent Initialization

New agents get weights from disposition modulation (§5.4):

1. Load species-level topology with default weights
2. Apply `DispositionWeightMapper` — resolve tagged connections,
   multiply by disposition modifiers
3. Set initial activation thresholds to species defaults
4. Set resting activations from disposition (e.g.,
   socialOrient=cooperative → other_reliability +0.2)
5. Set initial precision from provenance: EMPIRICAL=10.0,
   CONSENSUS=5.0, ESTIMATED=1.0
6. All inhibitory overlays = 0.0, decay resistance = 1.0

### 4.7 SQLite Storage

Tables via Flyway migration:

```sql
-- Per-agent connection weights (sparse — only non-default)
CREATE TABLE caps_connection_weights (
    tenant_id    TEXT NOT NULL,
    agent_id     TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    excitatory   REAL NOT NULL,
    inhibitory   REAL NOT NULL,
    decay_resistance REAL NOT NULL DEFAULT 1.0,
    precision    REAL NOT NULL DEFAULT 1.0,
    PRIMARY KEY (tenant_id, agent_id, connection_id)
);

-- Per-agent node state (sparse — only non-default thresholds/resting activations)
CREATE TABLE caps_node_states (
    tenant_id    TEXT NOT NULL,
    agent_id     TEXT NOT NULL,
    node_id      TEXT NOT NULL,
    activation_threshold REAL NOT NULL,
    resting_activation   REAL NOT NULL DEFAULT 0.0,
    PRIMARY KEY (tenant_id, agent_id, node_id)
);

-- Per-agent generation counter and experience cursor
CREATE TABLE caps_agent_state (
    tenant_id    TEXT NOT NULL,
    agent_id     TEXT NOT NULL,
    generation   INTEGER NOT NULL DEFAULT 0,
    experience_cursor TEXT,  -- memoryId of last processed experience (null = no experiences processed)
    PRIMARY KEY (tenant_id, agent_id)
);
```

Atomic writes: all three tables updated in a single SQLite transaction.
On failure, entire update discarded — previous state persists.

### 4.8 SituationClassifier — Rule-Based Implementation

**Deviation from research spec:** The research spec §3.1 specifies an
ONNX model with binary cross-entropy loss per label (sigmoid per label,
threshold-based). This implementation uses a rule-based classifier as
the primary approach, with LLM fallback (§6). Rationale: an ONNX model
requires labelled training data that does not yet exist. The staged
approach is:
1. Rule-based + LLM fallback (works immediately, no training corpus)
2. Collect classified experience data during operation
3. Train ONNX model on accumulated corpus (see casehubio/neocortex#416)

FormativeExperiences carry pre-classified `situationTypes` and bypass
the classifier entirely. The rule-based limitation primarily affects
runtime experiences (Observation, Action, Outcome), where the LLM
fallback catches cases that keyword matching misses.

`RuleBasedSituationClassifier` in caps-engine:
- Keyword→nodeId mappings defined in the topology YAML as a `keywords`
  field on each input node (e.g., `secure_attachment: [secure, warmth,
  reliable, responsive, consistent]`). Populated from catalogue
  narratives and clinical terminology during topology authoring.
- Multi-label: each input node gets an independent confidence score
  based on keyword overlap with experience description + metadata
- Scores below 0.3 discarded
- Pre-classified bypass: when FormativeExperience provides explicit
  `situationTypes`, or when `TriggerSpec` provides explicit node IDs,
  skip classification entirely

### 4.9 BehavioralSynthesisPhase

`BehavioralSynthesisPhase` implements `ConsolidationPhase` at
@Priority(19). Lives in `cognition` module (not caps-engine — see §2).

**Agent discovery:** BehavioralSynthesisPhase discovers agents via
scan-based enumeration, following the same pattern as
ExperienceConsolidationPhase:
1. Scan the experience memory store for memories in the `experience`
   domain for the tenant, using a global minimum cursor (the earliest
   per-agent cursor across all known agents in `caps_agent_state`)
2. Group results by `subject.id()` (= agentId, since ExperienceEvents
   stores `Subject.of("agent", agentId)`)
3. For each agent: load per-agent cursor from `caps_agent_state` →
   filter to experiences after that cursor → process
No separate "discover agents" API is needed.

**Experience cursor:** Stored per-agent in `caps_agent_state` table
(§4.7) alongside the generation counter. The cursor is the memoryId
of the last processed experience for that agent. New agents (first
experience) have null cursor (process all experiences).

**Runtime experience parameter defaults:** Observation, Action, and
Outcome lack explicit CAPS parameters. Defaults:
- `situationTypes`: derived by SituationClassifier from `description()`
  + `metadata()`. Pre-classified bypass for FormativeExperience.
- `salienceMultiplier`: default 1.0 (normal salience)
- `reinforcementSchedule`: default CONTINUOUS
- `outcomeValence` (λ): derived from experience type:
  - FormativeExperience → derived from PAD `pleasure()` value directly
  - Outcome → derived from situation classification. The classified input
    nodes carry inherent directional semantics:
    **Positive nodes** (λ contribution > 0): reward, mastery,
    competence_recognition, acceptance, abundance, agency_granted,
    secure_attachment, aversive_removal, sharing, choice_available
    **Negative nodes** (λ contribution < 0): punishment, failure,
    rejection, exclusion, scarcity, powerlessness, physical_threat,
    social_threat, psychological_threat, betrayal, abandonment, neglect,
    reward_removal, unpredictable_danger, controlled
    Formula: `λ = Σ(confidence_i × polarity_i)` where polarity is +1
    or -1 per the node lists above. This reuses the SituationClassifier
    output — no separate sentiment analysis needed.
  - Observation/Action → λ = 0 (no direct reinforcement; weight
    update occurs only if OCC emotions are present — see #417)
- `outcomeIntensity` (β_outcome): default 0.3 (moderate). FormativeExperience
  derives from `abs(pleasure)`. Outcome derives from max classified
  confidence: `β = max(confidence_i)` clamped to betaRange from
  WeightUpdateParameters.

Per-cycle processing:
1. Discover agents with unprocessed experiences (scan-based, as above).
   Order by unprocessed experience count desc.
2. For each agent:
   a. Load per-agent CAPS state from SQLite (includes cursor, generation)
   b. Check generation vs MindMap attractor source_generation —
      regenerate if stale (settle from current weights with resting
      activations only, no new input — re-derives attractors)
   c. For each new experience (after this agent's cursor):
      - Classify to situation types (SituationClassifier or pre-classified)
      - Activate input nodes with classified situation confidences
      - Run CAPS settling from resting activations + input activations
      - Update weights via Rescorla-Wagner with experience parameters
   d. Apply inhibitory decay to all connections
   e. Write updated state + advanced cursor + generation++ atomically
      to SQLite (single transaction)
   f. Write behavioral attractor nodes to MindMap BEHAVIORAL subgraph
3. Per-tenant time budget: 30 seconds. Deferred agents logged.

**Two-store consistency (corrected):** The experience cursor advances
atomically with the SQLite write (step 2e), NOT after the MindMap write.
If the MindMap write (step 2f) fails:
- SQLite has updated weights, advanced cursor, incremented generation
- MindMap attractors are stale (source_generation < generation)
- Next cycle: step 2b detects staleness → regenerates attractors from
  current CAPS state (no experience re-processing needed)
- No double weight update: cursor was already advanced past the
  processed experiences

This differs from the research spec's "replay" model (§5.2) which
assumes the engine write can be replayed. Replaying would cause double
weight updates because the starting weights have already changed. The
corrected design treats MindMap attractors as a derived projection of
CAPS state — stale attractors are regenerated, not re-derived from
experiences.

### 4.10 Disposition Weight Mapper

`DispositionWeightMapper` resolves disposition modulation:

1. Parse `disposition_modifiers` section from topology YAML
2. Build tag→connections index from `tags` field on each CapsConnection
3. For each DispositionAxes dimension + value, look up modifier entries
4. Resolve modifier names to concrete targets using four resolution types
   (tried in order — first match wins):
   a. **Direct connection reference** (name contains `_to_`):
      split on `_to_`, construct connection_id as `{from}__{to}`.
      E.g., `mastery_to_self_worth` → connection `mastery__self_worth`.
      If the constructed connection_id does not exist in the topology,
      falls through to unresolvable (the connection may not be in the
      starter topology yet — e.g., `acceptance_to_self_worth`)
   b. **Tag-based group reference** (name ends in `_connections` or `_pathways`):
      use the prefix as a tag query against the tag index.
      E.g., `BIS_connections` → all connections tagged `bis`
   c. **Node threshold modulation** (name ends in `_threshold`):
      apply multiplier to the named node's `activationThreshold`.
      E.g., `threat_sensitivity_threshold` → NodeState.activationThreshold
      for `threat_sensitivity` node
   d. **Bare node name** (name matches an existing node ID):
      multiply all connections terminating at that node (for OUTPUT nodes)
      or originating from it (for INPUT nodes).
      E.g., `cautious_approach: 1.3` → all connections with
      `to = cautious_approach` multiplied by 1.3
5. Apply modifiers multiplicatively per target
6. Unknown axis values → ×1.0 (no modulation) + warning log +
   disposition_coverage health metric
7. Unresolvable modifier names (no matching connection or tag) → ×1.0
   + warning log. Expected for modifier names that reference nodes/connections
   not yet in the starter topology (e.g., `norm_violation_to_guilt`,
   `schema_rigidity` — these become active when the CBT model's
   full connection set is added)

**Tag vocabulary and connection assignments:**

| Tag | Applied to connections | Purpose |
|-----|----------------------|---------|
| `bis` | BIS_activation→cautious_approach, BIS_activation→withdraw, BIS_activation→threat_sensitivity | BIS pathway group |
| `bas` | BAS_activation→approach, reward→BAS_activation, reward→reinforcement_expectation | BAS pathway group |
| `compliance` | powerlessness→fawn, controlled→comply (when added) | Compliance/submission pathways |
| `self_direction` | agency_granted→self_direct (when added) | Self-direction pathways |
| `fawn_accommodate` | powerlessness→fawn | Fawn/accommodate output |
| `fight_assert` | FFFS_activation→fight (when added: assert connections) | Fight/assert output |
| `flight_freeze_withdraw` | FFFS_activation→flight, FFFS_activation→freeze, escape_assessment→freeze | Flight/freeze/withdraw output |

Tags are defined on each connection in the topology YAML's `tags` field
(already present in the CapsConnection record, §3.1). The starter topology
(caps-topology.yaml) will be updated with these tag assignments.

## 5. caps-testing — Test Infrastructure

### 5.1 In-Memory Backend

`InMemoryCapsEngine` — volatile ConcurrentHashMap storage for tests.
Implements `CapsEngine` SPI. No SQLite dependency.

### 5.2 CapsEngineContractTest

Abstract base class with tests for:
- Topology loading (all nodes and connections present)
- Agent initialization (disposition modulation applied)
- Settling convergence (known inputs → expected outputs)
- Weight update (Rescorla-Wagner prediction error)
- Dual-weight (extinction + spontaneous recovery)
- Atomic writes (generation counter increments)
- Precision modulation (empirical weights resist change)
- Distortion application (compound cap, polarity weighting)

### 5.3 CognitiveEmergenceTest

Integration test framework from #402. Base class provides:

```java
public abstract class CognitiveEmergenceTest {
    // Seed a character from catalogue entries
    protected void seed(BackstoryProfile profile) { ... }

    // Run consolidation (one or more cycles)
    protected void consolidate(int cycles) { ... }

    // Assert behavioral attractors
    protected void assertAttractor(String category, String expectedNode,
                                    double minStrength) { ... }

    // Assert mediating node activation direction
    protected void assertMediating(String nodeId,
                                    ActivationDirection direction) { ... }
}
```

Test cases derived from catalogue entries:
- `SecureAttachmentEmergenceTest` — seed secure attachment → assert
  positive self_worth, trust, flexible approach
- `AnxiousAttachmentEmergenceTest` — seed anxious attachment → assert
  proximity_seek, negative other_reliability
- `HighBISEmergenceTest` — seed threat experiences + conservative
  disposition → assert cautious_approach, withdraw
- Composite: anxious attachment + high BIS → assert interaction effects
  through shared nodes

## 6. caps-llm-classifier — Optional LLM Fallback

Classpath-activated module. When present, provides
`LlmSituationClassifier` that wraps the rule-based classifier:

1. Run rule-based classification first
2. If all confidence scores < 0.3 (unclassified), invoke LLM via
   `AgentProvider` with the situation vocabulary and experience text
3. Parse LLM response into `SituationActivation` list

`@Alternative @Priority(1)` displaces the rule-based classifier
when on the classpath.

## 7. BEHAVIORAL Subgraph Schema

Node types written to MindMap by BehavioralSynthesisPhase:

- `attractor` — stable behavioral tendency. Properties: `strength`,
  `category`, `decay_rate`, `composition_trace` (JSON — see below),
  `source_generation`. Traits: `CapsGenerated`.
- `situation_trigger` — situation pattern. Properties: `situation_type`,
  `match_threshold`. Traits: `CapsGenerated`.

Edge types:
- `TRIGGERS` (situation_trigger → attractor): `activation_strength`
- `INHIBITS` (attractor → attractor): `inhibition_strength`
- `COMPETES` (attractor ↔ attractor): bidirectional competition

**`composition_trace` JSON structure:**

```json
{
  "outputNode": "withdraw",
  "category": "approach_avoidance",
  "mediatingContributions": {
    "BIS_activation": 0.82,
    "threat_sensitivity": 0.65,
    "self_worth": -0.40
  },
  "inputNodes": ["physical_threat", "rejection"],
  "convergenceType": "CONVERGED",
  "iterations": 34
}
```

- `outputNode`: the output node this attractor represents (identity key)
- `category`: output node category from the topology
- `mediatingContributions`: mediating nodes with |activation| > 0.3 at
  convergence, with their final activation values (diagnostic — explains
  WHY this attractor formed)
- `inputNodes`: which input nodes were active (> 0.3) during this settling
- `convergenceType`: CONVERGED, OSCILLATION, or MAX_ITERATIONS
- `iterations`: number of settling iterations used

**Attractor identity:** An attractor node in the BEHAVIORAL subgraph is
identified by `nodeId` (= output node) + `category`. When new settling
results arrive:
- If an attractor for the same output node exists: UPDATE its properties
  (strength, composition_trace, source_generation)
- If an output node in the new result has no existing attractor: CREATE
- If an existing attractor's output node is absent from the new result
  (strength below threshold): mark strength = 0 (decayed, not deleted —
  allows recovery if the attractor re-emerges in a later cycle)

`composition_trace` is diagnostic metadata — it changes each cycle to
reflect the current mediating state. It does NOT determine attractor
identity; the output node does.

`CapsGenerated` trait identifies CAPS-generated nodes. MergeDetectionPhase
currently only skips nodes with the `Summary` trait — it does NOT filter
`CapsGenerated` nodes. To prevent MergeDetectionPhase from merging
behavioral attractor nodes by name similarity, it must be modified to
skip BEHAVIORAL subgraphs entirely (subgraph-type exclusion, not
per-node trait filtering). This is cleaner than coupling
MergeDetectionPhase to CAPS-specific traits — it uses subgraph type
as the discrimination boundary, consistent with how subgraphs
architecturally separate concerns.

**Prerequisite:** `SubgraphTypes.BEHAVIORAL` constant must be added to
`mindmap-api/.../SubgraphTypes.java` (currently defines: PERSON, PROJECT,
RESEARCH_AREA, ORGANISATION, CONCEPT, GENERAL, TYPE_SYSTEM, COGNITIVE,
GOAL — no BEHAVIORAL). This is a cross-module change in mindmap-api,
listed in §8 Batch 1.

## 8. Implementation Order

Based on #407 spec §7.2 and dependency structure:

**Batch 1: Foundation**
- caps-api: all types from §3 (including tags on CapsConnection)
- caps-engine: topology loading, in-memory weight storage
- caps-testing: InMemoryCapsEngine, CapsEngineContractTest
- mindmap-api: add `SubgraphTypes.BEHAVIORAL` constant

**Batch 2: Settling + Weight Update**
- Settling algorithm with convergence/oscillation detection
- Per-node-type activation functions (logistic for UNIPOLAR, tanh for BIPOLAR)
- Per-node-type clamping (UNIPOLAR [0,1], BIPOLAR [-1,1])
- Oscillation dual-attractor extraction
- Rescorla-Wagner + Pearce-Hall weight update
- Precision-modulated adaptive learning
- Dual-weight architecture + inhibitory decay
- Distortion application (generic, data-driven)

**Batch 3: Storage + Persistence**
- SQLite backend (SqliteCapsEngine)
- Atomic writes with generation counter + experience cursor
- Agent initialization with disposition modulation + resting activations
- DispositionWeightMapper with tag resolution (§4.10)

**Batch 4: Classification + Integration**
- Rule-based SituationClassifier
- BehavioralSynthesisPhase in `cognition` (ConsolidationPhase @Priority 19)
- Agent discovery via memory scan (§4.9)
- BEHAVIORAL subgraph writes
- Two-store consistency (cursor advances with SQLite write)
- MergeDetectionPhase: add BEHAVIORAL subgraph exclusion
- Runtime experience parameter defaults (§4.9)

**Batch 5: Testing + Validation**
- CognitiveEmergenceTest framework
- Per-catalogue-entry emergence tests
- Composite emergence tests (interaction effects)
- Saturation monitoring validation

**Batch 6: Optional**
- caps-llm-classifier module

## 9. Deferred Items (GitHub Issues)

| Item | Rationale | Issue |
|------|-----------|-------|
| Topology migrations (Flyway-style node rename/split/add with weight remapping) | Pre-release, no installed base — no agents to migrate yet. Required before any topology YAML changes after first production deployment. | casehubio/neocortex#415 |
| ONNX SituationClassifier | Requires labelled training corpus. Rule-based + LLM fallback serves as data collection phase. | casehubio/neocortex#416 |
| OCC emotion → CAPS reinforcement mapping | EmotionType → λ sign + pathway mapping (research spec §2.4). One-cycle delay means it can be added after core engine is working. | casehubio/neocortex#417 |

## References

- docs/specs/issue-407-psychology-cause-effect-models/2026-10-02-psychology-cause-effect-models-design.md — research spec (authoritative)
- docs/specs/2026-10-02-caps-topology.yaml — species-level topology
- memory-seeding/src/test/resources/catalogue/ — catalogue entries
- mindmap-intelligence/.../consolidation/ConsolidationPhase.java — SPI
- cognitive-index/.../DispositionAxes.java — disposition record
- cognitive-index/.../CognitiveDerivationEngine.java — existing derivation
- sqlite-support/.../SqliteDataSourceFactory.java — shared SQLite infra
- Neal (1992) "Connectionist Learning of Belief Networks" — Bayesian weight justification
- Lake (2025) BIML — Bayesian priors in networks
- Mischel & Shoda (1995) — CAPS theory
- Shoda, LeeTiernan & Mischel (2002) — CAPS as PCS network
- PCSinR R package — PCS reference implementation
- casehubio/neocortex#406 (parent epic)
- casehubio/neocortex#407 (research — closed)
- casehubio/neocortex#402 (CognitiveEmergenceTest)
- casehubio/neocortex#397 (behavioral attractor synthesis — downstream)
