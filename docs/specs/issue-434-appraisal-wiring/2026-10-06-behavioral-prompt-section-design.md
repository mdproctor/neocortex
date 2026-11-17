# BehavioralPromptSection Design — #410

## Overview

Render crystallized behavioral attractors from the BEHAVIORAL subgraph into
natural-language prompt sections for cognitive agents. The LLM consumer receives
evocative, second-person descriptions of established behavioral patterns with
qualitative strength, trend direction, source depth, and situational activation
context.

## Scope

- Crystallized attractors only (established personality patterns from CAPS
  settling). Transient gut signals (#409) are out of scope.
- Enrichment of BehavioralSynthesisPhase to persist metadata needed for
  rendering: source experience count, previous strength (for trend detection).
- New `behavioralEnabled` flag in CognitionConfig.
- DirectiveSection mapping for behavioral framing.

## Architecture

### Data Flow

```
BehavioralSynthesisPhase (consolidation)
  → BEHAVIORAL subgraph nodes (MindMap)
    → BehavioralPromptSection (render-time query)
      → natural-language prompt text
        → LLM agent
```

### Module Placement

`BehavioralPromptSection` lives in the `cognition` module
(`io.casehub.neocortex.cognition.prompt` package), alongside all other
`CognitionPromptRenderer` implementations. It injects `MindMapStore` directly
— the same pattern used by `CharacterDrivePromptSection` and
`NeedsPyramidPromptSection`.

## Component Design

### 1. BehavioralSynthesisPhase Enrichment

Two new properties on attractor nodes in the BEHAVIORAL subgraph:

| Property | Type | Set when | Purpose |
|----------|------|----------|---------|
| `previous-strength` | String (double) | Each EMA update | Trend detection: compare to `strength` |
| `source-count` | String (int) | Node creation + each update | Depth indicator: how many graduated experiences fed this attractor |

**Trend classification** from `strength` vs `previous-strength`:
- `strengthening`: current > previous + 0.05 (dead-band to avoid noise)
- `fading`: current < previous - 0.05
- `stable`: within ±0.05

**Source count** increments on each consolidation pass that processes
experiences relevant to the attractor (via CAPS input node matching). Stored
as a property on the attractor node.

**Source node tracking:** When creating or strengthening an attractor, record
the names of the graduated cognitive nodes that contributed to the CAPS
settling result. Store as a comma-separated `source-names` property, capped
at the 5 most recent. These provide situational activation context for
rendering ("rooted in task abandonment and deadline pressure").

### 2. BehavioralPromptSection

Implements `CognitionPromptRenderer`. Constructor takes `MindMapStore`.

**`render(CognitionRenderContext context)`:**

1. Query BEHAVIORAL subgraph for nodes with `agent-id` matching
   `context.agentId()` and trait `CapsGenerated`.
2. Filter to nodes with `strength` > 0.1 (below this threshold, the pattern
   is too weak to influence behavior).
3. Sort by activated-drive priority, then strength descending.
4. For each attractor, render an evocative sentence:
   - **Strength** → qualitative word: >0.7 "deeply ingrained", >0.4
     "noticeable", >0.2 "emerging"
   - **Trend** → modifier: "and strengthening", "but fading", omitted
     if stable
   - **Source context** → "rooted in [source-names]" when available
6. Return null if no attractors found.

**Example output:**

```
You have a deeply ingrained completionist drive that's been strengthening —
rooted in task abandonment and deadline pressure. You have a noticeable
wariness around authority that's been stable — rooted in criticism encounters
and hierarchical interactions. You have an emerging resource anxiety but it's
fading — rooted in early scarcity experiences.
```

**Strength-to-language mapping:**

| Range | Label | Behavioral weight |
|-------|-------|-------------------|
| >0.7 | "deeply ingrained" | Strong default behavior |
| >0.4 | "noticeable" | Moderate tendency |
| >0.2 | "emerging" | Weak but present |
| ≤0.2 | filtered out | Below rendering threshold |

**Trend-to-language mapping:**

| Condition | Phrasing | Implication for LLM |
|-----------|----------|---------------------|
| current > previous + 0.05 | "and strengthening" | Apply more firmly |
| current < previous - 0.05 | "but fading" | Apply with flexibility |
| within ±0.05 | (omitted) | Stable default |

### 3. CognitionConfig Changes

Add `behavioralEnabled` as the 23rd field. Update `all()`, `none()`,
`withDirectives()`, `with(String, boolean)`, `without(String...)`.

### 4. CognitionCore Integration

In `promptSections()`, after the appraisal/characterDrives block and before
attention:

```java
if (config.behavioralEnabled() && mindMapStore != null) {
    sections.add(new BehavioralPromptSection(mindMapStore));
}
```

### 5. DirectiveSection Mapping

Add a `BehavioralPromptSection` entry to the `DIRECTIVES` map:

```
"BehavioralPromptSection",
"These are your established behavioral patterns — deep tendencies shaped by "
+ "accumulated experience. They are not rules but dispositions. Strong patterns "
+ "should color your responses naturally; fading patterns can be overridden by "
+ "current context:"
```

### 6. CognitionDefaultBeans

Add `behavioralEnabled` to the default config producer. Default: `false`
(opt-in, like appraisalEnabled).

### 7. Oscillation Rendering (#1027)

When a CAPS settling result has `convergence-type=oscillation`, the attractor
represents a competing tendency the network couldn't commit to. The rendering
uses tension framing: "You feel a competing pull toward X — your mind hasn't
settled on this" rather than strength-based labels (deeply ingrained/noticeable/
emerging). Source context is included but trend analysis is omitted — oscillation
attractors don't have meaningful trend data.

The `convergence-type` property is set by `BehavioralSynthesisPhase` and cleared
when subsequent settling converges normally. This prevents stale oscillation
metadata from persisting on stabilised attractors.

## Testing

### BehavioralPromptSection tests

- Null/empty: no BEHAVIORAL nodes → returns null
- Single attractor: renders with correct strength label and source context
- Multiple attractors: sorted by activated-drive priority then strength descending, all above-threshold rendered
- Trend rendering: strengthening, fading, stable each produce correct phrasing
- Threshold filtering: strength ≤ 0.1 excluded
- Missing source-names: renders without "rooted in" clause
- Agent isolation: only renders attractors for the requesting agent
- Oscillation rendering: `convergence-type=oscillation` → "competing pull" framing, no strength labels

### BehavioralSynthesisPhase enrichment tests

- New attractor: `source-count` = number of contributing nodes
- Updated attractor: `source-count` increments, `previous-strength` = old strength
- Source names: stored correctly, capped at 5

### CognitionCore integration tests

- `behavioralEnabled=true` + mindMapStore present → section in list
- `behavioralEnabled=false` → section absent
- `behavioralEnabled=true` + mindMapStore null → section absent

## References

- BehavioralSynthesisPhase.java:204-241 — attractor node storage
- AppraisalPromptSection.java — evocative rendering pattern
- CharacterDrivePromptSection.java — MindMapStore injection pattern
- CognitionCore.java:457-525 — prompt section assembly
- CognitionConfig.java — config flag pattern
- DirectiveSection.java — directive map
- SubgraphTypes.java:15 — `BEHAVIORAL = "behavioral"`
- GitHub issue #410 — requirements
- GitHub issue #409 — gut feeling (deferred, out of scope)
- decisions-410.md — D1-D8 design decisions
