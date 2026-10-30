# Era 5: Relational Cognition — WHO Shapes What You Feel

**Status:** ACTIVE — attention competition finding, targeted somatic hypothesis

The cognitive pipeline differentiates WHAT agents feel (Barrett somatic channels) and HOW they express it (drive descriptions, thinking-field calibration). Era 4 brought this to ceiling: quality 3.92, register 3.74, differentiation 3.74 — all exceeding V2 prose baseline on aggregate metrics.

The ceiling is reactive drives. Gloating, gallantry, protection — drives that activate toward SPECIFIC people in SPECIFIC situations — regress because the pipeline is blind to WHO the agent interacts with. The Barrett instruction says "notice your body." The drive description says "warm pressure behind your sternum." Neither says "this activates because Reginald just failed and he is prey."

Era 5 opens with #477 relational schemas, which adds the WHO dimension.

---

## What Era 4 Established

### The hybrid that works

Label + degree + somatic prose. Three components, all required:

| Component | What it provides | Without it |
|-----------|-----------------|------------|
| Label (GLOATING) | Conceptual anchor — LLM knows what the drive IS | Somatic text is ambiguous (warm pressure = joy? pride?) |
| Degree (0.7) | Intensity calibration | All drives feel equally strong |
| Somatic prose | Immersion anchor — keeps character in felt experience | Label triggers metacognitive reasoning (Era 1 P13) |

Somatic prose alone: quality 3.76. Label + degree alone: simulation awareness leaks. Hybrid: quality 3.99, zero leaks.

### Constant vs reactive drives

| Drive type | Examples | Quality | Why |
|-----------|---------|---------|-----|
| Constant | Scheming (5.00), Curiosity (4.67), Suspicion (4.00) | Excellent | Always-on — body state is permanent |
| Reactive | Gloating (2.40), Gallantry (3.67), Protection (3.80) | Below V2 | Needs trigger — WHO failed? WHO is in danger? |

Targeted probes confirmed the code works: HC gloating 4.33, Peter gallantry 5.00 in ideal conditions. Quality degrades under long context + ambiguous triggers + drive competition.

### The immersion architecture

Every text surface the LLM sees was audited and made experiential:
- Section headers renamed (system-speak → lived experience)
- Action results made descriptive (game reports → memories)
- ActionType.verb() centralised (enum names never in text)
- ID rewrite at import (cartoon IDs eliminated from runtime)
- Thinking instruction ("you are inside this moment, not watching it")
- Zero cartoon contamination, zero simulation awareness in 300-tick run

### What's missing: the relational dimension

The Barrett channels (body, attention, time, action readiness) are GENERIC — same instruction every tick regardless of who is present. The drive descriptions are SOMATIC — they describe the body's pattern but not what triggers it.

V2 prose had embedded triggers: "When someone is struggling, your body responds..."

The computed pipeline has the body response but not the trigger. The character has to independently recognise "Clara is in danger" → "gallantry should activate" → "this body pattern applies." Under long context with competing drives, this connection fails.

---

## What #477 Relational Schemas Provides

Five layers, each operating at a different timescale:

### 1. Types + Storage (#716)

`RelationalSchema(targetId, role, trust, intimacy, utility, description)` on perspectival overlay nodes. The description field carries felt prose — "Predictably protective. Too simple to be a real threat." — not system labels.

### 2. Appraisal Wiring (#717)

`AppraisalContext` carries the relational schema and personality facets. The sub-LLM appraisal receives relationship context in natural language: "Relationship with [target]: Role: prey, Trust: 0.1..."

### 3. Two-Channel Prompt Architecture (#718)

`OtherModelPromptSection` renders only the phenomenological layer — empathic-sensing ("a tightness behind the words"), relational-understanding ("the bond is built on shared creative ambition"). The mechanistic layer feeds the appraisal pipeline only — never the prompt.

The agent experiences "I sense tension in them" rather than "my appraisal-sensitivity score for rejection is 0.8."

### 4. Dynamic Evolution (#719)

Trust, intimacy, and role evolve during consolidation, personality-conditioned:
- Callousness asymmetry — dampens trust gain, preserves trust loss
- Avoidance ceiling — dismissive-avoidant agents structurally cannot reach high trust
- Personality-gated role transitions — prey → tool (not ally) when Manipulativeness > 0.5

### 5. CAPS Biasing (#720)

Trust modulates CAPS input activations before appraisal runs. Kindness from prey dampens secure_attachment, boosts rejection. Same observation, different perceptual filtering. The biasing is sub-prompt — it shapes emotion, which shapes behaviour, which shapes text.

---

## Hypothesis: Relational Context Restores Reactive Drive Quality

The missing link between observation and feeling:

```
Era 4 (current):
  "Reginald is here" → Barrett: "notice your body" → Drive: "GLOATING (0.7): warm pressure"
  Connection: character must independently recognise trigger → fails under complexity

Era 5 (with #477):
  "Reginald is here" → Relational: "prey, trust 0.1, you sense his weakness"
                      → CAPS: prey presence dampens empathy, boosts schadenfreude
                      → Barrett: body responds to the relational signal
  Connection: system provides the relational trigger → character's body responds
```

### Expected impact on regressed drives

| Drive | Era 4 issue | #477 mechanism | Expected fix |
|-------|------------|----------------|-------------|
| HC gloating | No connection: failure → schadenfreude | Prey role + dampened empathy = gloating trigger | Gloating activates when prey fails |
| Peter gallantry | No connection: Clara present → protect | High trust + anxious attachment = protective activation | Gallantry activates when Clara is present |
| Peter protection | Same as gallantry | Same mechanism | Protection always-on toward trusted |
| DD greed | No connection: valuable object → desire | Low utility relationship with objects? | May need separate fix |

### What to measure

1. **Reactive drive quality** — HC gloating, Peter gallantry/protection vs V2 and Era 4 baselines
2. **Relational consistency** — does the character treat prey differently from ally?
3. **Drive × relationship interaction** — does gloating toward prey differ from gloating toward rival?
4. **Metacognitive awareness** — does the relational prompt leak system state? (#167 eval)
5. **Session degradation** — do long sessions lose relational grounding?
6. **Temporal evolution** — do trust/role changes manifest in drive expression over 300 ticks?

### Eval methodology

The DriveQualityEvalTest from Era 4 provides targeted probes. Era 5 extends it with:
- **Relational probes** — same drive, different target person (prey vs ally). Quality should differ.
- **Temporal probes** — same scenario at tick 10, 100, 200. Quality should hold or improve.
- **Competition probes** — multiple drives eligible, relationship determines which dominates.

---

## Open Questions

1. Does the relational context alone restore reactive drive quality, or do we also need explicit situational trigger conditions on drives?
2. How does the two-channel prompt architecture interact with the Barrett instruction? Are they additive or competing?
3. Does CAPS biasing create observable drive differentiation, or is it too subtle for the eval to detect?
4. The entity linking design (#721) — should relational references in text use standoff annotation for memory grounding?
5. The tagged block protocol (#486) — should relational context arrive in a separate tagged block with its own interpretation META rules?
6. Performance: relational context adds per-person sections to the prompt. With 5 characters in the room, how much does this increase prompt size and latency?

---

## Finding: Attention Competition (2026-10-29)

### The experiment

Three eval runs with #477 relational schemas active, comparing against Era 4 baseline (quality 3.92, register 3.74, differentiation 3.74):

| Run | Events | Quality | Register | Diff |
|-----|--------|---------|----------|------|
| Era 4 baseline | 300 | **3.92** | **3.74** | **3.74** |
| Era 5 + YAML briefing fallback | 30 | 3.84 | 3.27 | 3.70 |
| Era 5 label+degree only | 30 | 3.88 | 3.34 | 3.61 |
| Era 5 full pipeline (emerged descriptions) | 105 | 3.84 | 3.13 | 3.57 |

Register degraded progressively: 3.74 → 3.27 → 3.13. More relational context = less somatic expression.

### Bugs found and fixed

1. **SessionPipeline not wired in scripted mode.** `consolidationScheduler.setSessionPipeline()` was inside `if (mode == AUTONOMOUS)`. The CognitiveDerivationEngine's drive-description-generation phase silently skipped — characters got YAML briefing prose (or label+degree after we stripped the fallback) instead of emerged Barrett 4-channel descriptions. TDD: test written, red→green.

2. **YAML briefing prose fallback.** When no generated descriptions existed, `CognitionObservationBuilder` fell back to hand-authored YAML descriptions — cognitive narrative that contaminated the somatic Barrett instruction. Fixed to render label+degree only.

3. **ConditionMatcher NPE.** Trigger conditions referenced cartoon IDs that don't exist after GENERIC ID rewrite. Null-safed.

### Root cause: attention is finite and competitive

The LLM prompt is a priming field. Every token competes for attention during generation. Adding a section doesn't just add information — it takes attention away from every other section.

Era 4's prompt had ONE clear directive:
- Barrett 4-channel instruction: "notice your body"
- Drive labels with somatic prose
- Formation memories
- Thinking instruction: "you are inside this moment"

Every section reinforced the same message: feel, don't think.

Era 5 added FIVE cognitive sections alongside this:
1. "How You Feel About Those Present" — relational analysis per person
2. "How You Bond" — attachment style prose
3. Trust sections — trust evolution data
4. OtherModelPromptSection — empathic sensing
5. CAPS-biased drive filtering

Each directs the LLM toward social *analysis* rather than somatic *experience*. The Barrett instruction says "notice your body" but the relational section says "here's what you think about these people." The character tries to do both and does neither well.

The 105-event run confirmed this is not noise — register degraded consistently at 3.13 across 70 evaluated events with N=14 per character.

### Why separate sections fail

The prompt creates a cognitive interruption pattern:

```
== What Pulls at You ==
GLOATING (0.7): [somatic description]          ← somatic

== How You Feel About Those Present ==          ← cognitive interruption
**Reginald:** Prey. Trust 0.1. Predictable.

== How You Bond ==                              ← cognitive interruption
You don't need people. Closeness is vulnerability.
```

The drive doesn't know about Reginald. The relational description doesn't reference the drive. The LLM must independently connect "Reginald is prey" + "gloating activates toward prey" under long-context pressure. That is exactly the connection that fails — the same regression Era 5 was designed to fix.

### Hypothesis: targeted somatic drives

The relational context shouldn't be a separate cognitive section. It should be a directional anchor on the drive itself:

```
GLOATING (0.7) → Reginald: A warm, effervescent pressure rises behind your sternum...
```

The somatic sensation stays the same. It just knows WHERE it's pointed. No separate section. No cognitive interruption. Three characters on each relevant drive: `→ Name`.

This mirrors embodied cognition: you don't feel anxiety in the abstract. You feel anxiety *toward* a specific person. The body's response is always directed.

**Implementation:** At render time, `CognitionObservationBuilder` resolves the relational target for social drives (drives with `context: social`) and annotates the label. Non-social drives stay untargeted. The separate relational/attachment/trust/OtherModel sections are removed.

**What to measure:** Register should recover toward Era 4 levels (3.74) because the prompt returns to one clear directive — feel your body — with the relational information folded into the drive labels rather than competing in a separate section.

---

## Finding: Register Recovery with Targeted Drives (2026-10-30)

### Eval run: targeted somatic drives

After implementing 5-arg `renderInternalState` with `agentNames` and fixing `renderRelationalSchemas` to read evolved overlay data:

| Run | Events | Quality | Register | Diff |
|-----|--------|---------|----------|------|
| Era 4 baseline | 300 | 3.92 | 3.74 | 3.74 |
| Era 5 full pipeline | 105 | 3.84 | 3.13 | 3.57 |
| **Era 5 targeted** | **30** | **3.87** | **3.46** | **3.53** |

Register recovered +0.33 (3.13 → 3.46) from targeted drive wiring alone, even WITHOUT per-role descriptions (0 schemas in MindMap → generation fell back to untargeted).

### Root cause: relational schemas not seeded into MindMap

Server log evidence:
```
DriveDescriptionGenerationPhase: 5 agents, 17 drives, 26 formative memories, 0 relational schemas
RelationalSchemaEvolutionPhase: 0ms (nothing to evolve)
```

`ManorCognitiveSeeder.seedPeople()` creates overlay nodes with only PAD values (pleasure, arousal, dominance). It does NOT include `rel.role`, `rel.trust`, `rel.intimacy`, `rel.utility`, `rel.description` from `socialConfig.relationalSchemas()`. Consequence: the entire relational schema pipeline (P20 evolution, P22 per-role generation) is inert.

### Deep diagnostic: why register dropped (7-layer investigation)

1. **Section structure is identical** between Era 4 and Era 5. All 15 section slots existed in Era 4. The problem is content, not structure.

2. **"What Pulls at You" grows from 9→25 parts** after consolidation. 4 drives + 4 self-standards + 5 CAPS attractors + 6 differentiation texts + 6 priority signatures. But the added content IS somatic (Barrett-quality differentiation), not analytical. Volume dilutes attention but doesn't change register directly.

3. **Action readiness channel drops to zero.** Era 4 early asides: body=2.6, attention=2.2, time=0.3, action=1.9. Era 5: body=3, attention=3, time=0, action=0. The generated descriptions contain action readiness vocabulary (3-4 markers each) — the palette is there, the LLM just doesn't use it.

4. **Ready-made labels short-circuit action readiness.** Trust ("LOW"), relational schemas ("prey"), social awareness ("more cooperative") give the LLM classification shortcuts. Era 4 forced the LLM to construct understanding through body ("my weight shifts when he moves"). Era 5 lets it classify ("he's the bookish type").

5. **Era 4 also degrades over time.** 300-tick baseline: early body=2.6 attn=2.2 action=1.9 → late body=1.4 attn=0.4 action=1.0. Consolidation-added content dilutes register in BOTH eras. Era 5 starts from a worse position.

### Barrett 4-channel selection framework

The 4 channels are NOT a checklist — they're a selection space. Each drive×role×situation activates 1-2 channels:

**Drive → primary/secondary channel:**

| Drive | Primary | Secondary |
|-------|---------|-----------|
| scheming | attention | action-readiness |
| suspicion | attention | body |
| curiosity | attention | action-readiness |
| protection | action-readiness | attention |
| gallantry | action-readiness | body |
| gloating | body | time |
| dominance | body | attention |
| self-preservation | body | attention |
| loyalty | body | action-readiness |
| social-harmony | body | attention |
| greed | body | attention |
| recognition | body | time |
| proving-worth | body | time |
| adventure | body | time |

**Target role → channel boost:**

| Role | Boosts |
|------|--------|
| prey | attention |
| obstacle | action-readiness |
| threat | body + attention |
| ward/beloved | body + action-readiness |
| tool | attention |
| rival | action-readiness + time |
| ally | body |

These are mechanical lookup tables — no LLM needed for selection.

### Body-location differentiation principle

Each drive needs a unique body LOCATION. When two drives share a location, differentiation drops:

- Scheming: hands/periphery (fizzing fingers, attention narrowing)
- Gloating: central-spreading (warmth behind sternum, frame expanding, time stretching)
- Greed: gut-deep (below sternum, hollow, fingers curling downward)
- Recognition: chest-high (spine reaching upward, chest filling)
- Dominance: postural-vertical (spine, shoulders squared, jaw set)
- Self-preservation: armored (peripheral scanning, neck-prickle, constant hum)
- Protection: legs/positioning (weight dropping, interposing)
- Gallantry: warm-reaching (chest opening, hands extending)
- Proving-worth: tense-hands (grip, engine that won't idle, the gap before recognition)

### Trigger-rate principle and its limits

A drive that triggers in 90% of events with D=3 drags the average worse than one that triggers 60% with D=4-5. Reducing trigger rate by letting events be OWNED by fewer drives improves differentiation.

**Limit:** Identity-level drives (scheming for both villains, self-preservation for hooded-claw) cannot be subtracted. Three independent agents confirmed this — subtraction degraded all scores. These drives need improvement through ADDITION (new events with genuinely different content) or REFRAMING (sub-types, per-target variation).

### Golden prose methodology

Development pattern established for cognitive extensions:

1. **Describe** — hand-craft ideal rendered output when the capability is exercised
2. **Test** — run classifier, verify Q/R/D ≥ 4.0
3. **Implement** — build/modify backend to generate equivalent
4. **Iterate** — pipeline converges toward golden target

Golden corpus: 51 events, 5 characters, Q=4.35, R=4.37, D=4.12. Four per-drive scores remain below 4.0 (villain scheming differentiation — see HANDOFF for approaches).

### Scoring principles (from deep analysis agent, effort:max)

1. **Temporal discovery ordering** — "the prickle arrived first, then something different — slower, warmer" (R=4-5) beats co-present description (R=3)
2. **Drives peak when owning events** — pure savoring (one drive) scores R=5; competing with 3 others scores R=3
3. **Desc/thinking alignment** — classifier reads desc for relevance, thinking for register; misalignment penalises both
4. **Suspicion has the highest natural register** (R=4.9) — body-knowing (neck-prickle, gut-knowledge, hands-watching) has no analytical equivalent
5. **Secure attachment is hardest to express** — it's the absence of anxiety; needs positive felt states (body's ease, breath deepening near trusted person)

---

## Immediate Plan

1. ~~Merge #477 to this slot~~ ✅ Done
2. ~~Delete consolidation cache~~ ✅ Done
3. ~~Run 30-event eval with relational schemas active~~ ✅ Done — quality 3.84, register 3.27
4. ~~Compare reactive drive quality against Era 4 baseline~~ ✅ Done — regressed
5. ~~Investigate whether explicit trigger conditions are needed~~ ✅ Done — found attention competition
6. ~~Implement targeted somatic drives (`→ Name` annotation)~~ ✅ Done — register recovered +0.33
7. ~~Create golden prose target~~ ✅ Done — Q=4.35, R=4.37, D=4.12
8. Fix villain scheming differentiation in golden prose (4 per-drive scores below 4.0)
9. Seed relational schemas into MindMap (`seedPeople` fix)
10. Restructure renderCognitiveSections to drive-first integrated blocks
11. Update DriveDescriptionGenerationPhase with channel emphasis hints
12. Run eval — compare pipeline output against golden corpus scores
13. If scores match → 300-tick validation
