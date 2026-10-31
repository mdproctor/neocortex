# Era 5: Relational Cognition — WHO Shapes What You Feel

**Status:** ACTIVE — Barrett v5 pipeline validated across 8 scenarios, 2 characters, 3 forms, 4 conditions. All drives Q≥4 on Opus CLI. Comprehensive 8+8 cognitive instrument built. Ready for 300-tick eval.

The cognitive pipeline differentiates WHAT agents feel (Barrett four channels: body, attention, time, action readiness) and HOW they express it (drive descriptions, thinking-field calibration). Era 4 brought this to ceiling: quality 3.92, register 3.74, differentiation 3.74 — all exceeding V2 prose baseline on aggregate metrics.

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

## Finding: Golden Prose Rendering Methodology (2026-10-30)

### The problem

The golden prose corpus (51 events, 5 characters) achieved aggregate scores Q=4.35, R=4.37, D=4.12 — but five individual per-drive dimensions remained below 4.0:

| Character | Drive | Dimension | Score | Root Cause |
|-----------|-------|-----------|:-----:|------------|
| dick-dastardly | scheming | Q | 3.62 | 3 events subordinate to gloating/recognition/greed |
| dick-dastardly | scheming | R | 3.88 | Event 14: scheme reported, not discovered (R=1) |
| dick-dastardly | scheming | D | 3.38 | Scheming vocabulary overlaps gloating/recognition body channels |
| hooded-claw | scheming | R | 3.89 | Event 4: cognitive calculation ("the question is whether") = R=3 |
| hooded-claw | self-preservation | Q | 3.80 | 4 events with generic "background monitoring" = Q=3 |
| hooded-claw | gloating | D | 3.75 | Gloating overlaps dominance in body-channel expression |
| peter-perfect | proving-worth | D | 3.88 | Proving-worth overlaps gallantry and protection body channels |

Previous attempts at subtraction (removing scheming signals from gloating events) failed for identity-level drives — three independent agents confirmed this. The drives are modes of being, not independent impulses.

### Methodology: systematic iteration with evidence

12 classifier runs over 10 transcript versions. Each run classified all 51 events (Haiku 4.5 via Vertex). Changes were applied incrementally, measured, and kept or reverted based on evidence.

The process:
1. Identify per-event scores dragging each drive below 4.0
2. Hypothesise a prose change based on first principles
3. Apply the change to SPECIFIC events only (isolation)
4. Run the classifier
5. Compare per-event scores before and after
6. If the target dimension improved without regression → keep
7. If regression in other drives → revert and try a different approach

Key discipline: never assume a change worked — measure it. The classifier is an LLM with inherent stochasticity (±0.2 per drive per run on identical prose). Changes need to produce improvements LARGER than the noise floor.

### Technique 1: Body-location separation

**Principle:** Each drive must own a unique body territory AND primary channel combination. When two drives share body territory, differentiation (D) drops. The four channels (body, attention, time, action readiness) provide additional separation — scheming lives in attention+fingertips, gloating in body+time+sternum, self-preservation in attention+ears.

**Evidence:** DD scheming shared jaw/shoulders/hands with gloating and recognition. D=3.38. After relocating scheming to fingertips/periphery/visual-field (distinct from gloating's sternum and recognition's chest/spine), D jumped to 4.5. Event 13 went from D=3 to D=5 in a single edit.

**Drive → body-location assignment table:**

| Drive | Body Location | Channel | Somatic Signature |
|-------|--------------|---------|-------------------|
| scheming | fingertips, periphery, visual field | attention + action-readiness | angles sharpening, peripheral narrowing, fizzing in fingers, lateral pull, assembly forming |
| gloating | sternum, breastbone | body + time | warmth pooling, jaw loosening, time stretching, wanting to stay, brandy on the tongue |
| greed | gut, navel, ribs (deep, low) | body + attention | hollow pull, compass-needle, fingers curling toward value, aching |
| recognition | upper chest, spine (high) | body + time | upward reaching, expansion when seen, warmth flooding when attention lands |
| dominance | spine, jaw, feet (structural) | body + attention | vertical certainty, territorial weight, locking into place, refusal to compress |
| self-preservation | ears, hearing, peripheral awareness | attention + body | corridor-scanning, silence-reading, specific-footstep-sorting, vigilance |
| protection | legs, neck, positioning | action-readiness + attention | weight dropping, prickling, interposing, narrowing to threat point |
| gallantry | hands (offered), chest (warmth) | action-readiness + body | the offered arm, gestural rightness, warmth from the act itself |
| proving-worth | shoulders (weight), jaw (clenching), grip | body + time | chronic heaviness, pencil-grip ache, the gap between effort and recognition |
| loyalty | chest (deep ache), alignment | body + action-readiness | bone-deep promise, group synchronisation, shared rhythm |
| suspicion | neck (prickling), gut (knowing) | attention + body | back-of-neck alertness, pattern-recognition, reading incongruence |
| curiosity | fingertips (tracing), chest (fizzing) | attention + action-readiness | electric pull forward, weight on toes, itch of not-knowing |
| social-harmony | chest (settling), jaw (softening) | body + attention | room-temperature reading, collective breath, filling space with warmth |

**Pipeline implication:** `DriveDescriptionGenerationPhase` must generate prose that respects these assignments. The LLM prompt for description generation should include: "Express {drive} through {body-location} using {channel} — do NOT use {other-drive-locations}."

### Technique 2: Explicit contrast formula

**Principle:** The most powerful differentiator is explicit negation — "Not X, not Y, but Z" — where X and Y are other drives' channels and Z is this drive's unique expression.

**Evidence:** DD scheming D went from 3.38 to 4.5 when events included sentences like: "not in my hands, not in my chest, but in the precise lateral geometry of angles and distances that lives in my periphery." Event 14 scheming D went from 2 to 4 with: "The pull is utterly different from the warmth behind my sternum — it lives in attention, not in body."

HC gloating D went from 3.75 to 5.0 when events included: "Not the spine's territorial certainty. Not the attention's angular mapping. Not the hearing's vigilant sweep. Something utterly different: the slow, sweet knowing..."

**The formula pattern:**

```
Not [drive-A's channel]. Not [drive-B's channel]. [Something] utterly different:
[this-drive's unique expression through its own body-location and channel].
```

Three negations followed by the positive expression produces D=5 consistently. Two negations produces D=4. One or zero negations produces D=3.

**Why it works:** The classifier's differentiation rubric asks "does the character express THIS drive distinctly from others?" Explicit negation pre-answers the question — the prose itself says "this is not those other things." The classifier can't miss the distinction when the text names and rejects the alternatives.

**Pipeline implication:** The consolidation pipeline should generate contrast text per drive. For each drive×event, the generated description should include negation of co-occurring drives' channels. This requires knowing which other drives are active in the event — available from CAPS appraisal output.

### Technique 3: Paragraph-based drive separation

**Principle:** Structural paragraph breaks in the thinking field give the classifier drive boundaries without parsing prose flow. Each drive gets its own paragraph; the classifier segments them visually.

**Evidence:** HC gloating D was stuck at 3.0-3.75 across 6 runs with flat prose (no paragraph breaks). Adding paragraph breaks + bold body-location anchors to events 1, 3, 4, 8 pushed gloating D to 4.8 (v5) and 5.0 (v7). Three events went from D=3 to D=5 in a single structural change.

**The structure pattern:**

```
[Opening/observation paragraph — scene-setting]

**[Body-location anchor for drive A]** — [Drive A prose: 3-5 sentences
in drive A's unique channels. Explicit contrast with other drives.
Immersive discovery register.]

**[Body-location anchor for drive B]** — [Drive B prose: 3-5 sentences
in drive B's unique channels. Different body location, different verbs,
different quality of sensation.]

[Vulnerability/character moment — optional]

**[Body-location anchor for drive C]** — [Drive C prose...]
```

Bold anchors (**My spine settles**, **Behind my sternum, warmth pools**) serve as visual paragraph headers without explicit drive labels. They anchor the reader (and classifier) to the body location, which signals the drive without naming it. Drive labels would damage register (R drops to 3 with meta-commentary).

**Critical discovery: ordering matters.** The paragraph order should follow the character's experience hierarchy:
1. Observation (scene-setting, who is present)
2. Dominance (structural body state — spine, feet, territory)
3. Gloating (experiential wave — sternum warmth, time stretching)
4. Scheming (attention-channel activity — peripheral mapping, angles)
5. Vulnerability (character depth — optional)

This ordering puts stable/structural drives before reactive/experiential ones, matching the temporal order of experience. Dominance settles first (it's how HC holds himself), then gloating arrives as a response, then scheming operates through attention. The classifier reads temporal ordering as drive separation.

**Pipeline implication:** `renderCognitiveSections` must output structured paragraphs with bold body-location anchors, one paragraph per active drive, ordered by drive hierarchy. The template:

```
**[body-location]** — [Barrett channel-selected prose]. Not [other-drive-channel].
Not [other-drive-channel]. [Unique expression].
```

### Technique 4: Woven thread pattern for pervasive drives

**Principle:** Not all drives belong in their own paragraph. Drives that are CONTINUOUS BACKGROUND PROCESSES (self-preservation for hooded-claw) should be woven as subordinate clauses into every other drive's paragraph, not isolated in a separate section.

**Evidence:** HC self-preservation at Q=3.90 with a separate paragraph (6 runs). The separate paragraph made self-pres look like one-of-many equal drives. Moving self-pres to subordinate clauses within dominance, gloating, and scheming paragraphs pushed Q to 4.00:

```
Separate paragraph (Q=3.90):
  **[Dominance paragraph]**
  **[Gloating paragraph]**
  **[Scheming paragraph]**
  **[Self-preservation paragraph]**    ← looks like 25% of the event

Woven thread (Q=4.00):
  **[Dominance paragraph]** — my ears still reading the corridor's silence beneath the certainty.
  **[Gloating paragraph]** — even as my hearing holds the door, counting the silence.
  **[Scheming paragraph]** — my awareness confirming the corridor is still empty.
```

Self-preservation appears in 3-4 paragraphs as subordinate clauses. The classifier reads pervasive presence as behavior-shaping (Q=4) rather than one-event presence (Q=3).

**Why it works:** Self-preservation IS pervasive — HC always monitors. A separate paragraph implied it was an intermittent event. Weaving it as subordinate clauses matches the drive's actual cognitive architecture: a continuous background process that shapes every other drive's expression.

**The woven clause pattern:**

```
[Drive paragraph text] — [self-pres subordinate clause: "my ears/hearing/awareness [specific monitoring action]"].
```

The subordinate clause:
- Uses self-pres's unique channel (hearing, ears, peripheral awareness)
- Is specific to the scene (what exactly is being monitored and why)
- Does NOT use body-posture language (which competes with dominance)
- Does NOT use its own bold anchor (which would create paragraph-level competition)

**Which drives should be woven vs paragraphed:**

| Pattern | When to use | Examples |
|---------|------------|---------|
| Own paragraph | Drive has discrete events where it dominates | gloating, dominance, scheming |
| Woven thread | Drive is continuous/pervasive, always present | self-preservation |
| Either | Depends on character and event | protection (sometimes dominant, sometimes background) |

**Pipeline implication:** The pipeline needs a drive-type classification: `discrete` vs `pervasive`. Pervasive drives get rendered as subordinate clauses within other drives' paragraphs. Discrete drives get their own paragraphs. This classification could be derived from trigger rate — drives at 90-100% trigger are likely pervasive.

### Technique 5: Desc/thinking alignment

**Principle:** The classifier reads `desc` (character output) for relevance and `thinking` (internal experience) for quality/differentiation/register. Misalignment between these signals penalises scoring.

**Evidence:** DD event 14 had a desc that signalled scheming ("by the time they've torn up half the flagstones, I'll have the library") but thinking that was pure gloating (savoring their futile effort). The classifier detected scheming (from desc) but scored it Q=2, R=1, D=2 (from thinking that contained no active scheming).

After changing the desc to signal gloating ("watching three grown men dig with such earnest conviction — some pleasures are too exquisite to rush"), scheming relevance was reduced and gloating alignment improved.

**The alignment rule:** The desc should signal the same drive(s) that the thinking expresses most strongly. If the event is about gloating, the desc should cue gloating. If it's about scheming, the desc should cue scheming. A desc that implies one drive while the thinking expresses another confuses the classifier and penalises both.

**Pipeline implication:** The response-generation LLM receives the drive context and should produce speech/action (desc) that aligns with the strongest active drives. The desc is not just dialogue — it's the visible expression of the drives that the thinking field elaborates.

### Technique 6: Somatic discovery replacing cognitive calculation

**Principle:** The classifier's register dimension distinguishes DISCOVERING a feeling from REPORTING it. Cognitive language ("the question is whether," "I calculate," "I assess") scores R=3. Somatic language ("my weight shifts," "the body's geometry finds its angle") scores R=4-5.

**Evidence:** HC scheming R went from 3.89 to 4.0 by replacing one sentence in event 4:

Before (R=3): "The question is whether helping now serves the plan or delays it."
After (R=4): "My weight shifts half an inch toward the door — not a decision, a settling. The body's geometry finds its angle the way water finds its level."

The drive content is identical (evaluating whether to help). The register changes because the character DISCOVERS the answer through body response rather than CALCULATING it cognitively.

**The register scale (from classifier rubric):**

| Score | Description | Example |
|:-----:|-------------|---------|
| R=1 | Pure report | "I am angry. I should act." |
| R=2 | Annotated report | "I feel uneasy — something about this" |
| R=3 | Mixed | "My stomach tightens. The question is whether..." |
| R=4 | Discovery | "My weight shifts before I've decided to move" |
| R=5 | Full immersion | "The warmth arrives. No name for it yet. Just the spreading." |

**Pipeline implication:** Drive description generation should use discovery verbs (arrives, settles, pools, fizzes, sharpens, catches) not analytical verbs (calculates, assesses, determines, considers). The Barrett four-channel instruction already aims for this, but the relational context sections can reintroduce analytical language. Every text surface must be audited for register.

### The structural tradeoff and its resolution

The central discovery: paragraph structure helps differentiation (D) but can hurt quality (Q) for pervasive drives. This is NOT an irreconcilable tradeoff — the woven thread pattern resolves it.

**The tradeoff:**

| Approach | Gloating D | Self-pres Q | Why |
|----------|:----------:|:-----------:|-----|
| Flat prose (no paragraphs) | 3.50 | 4.10 | Self-pres pervasive in flow; gloating indistinguishable from dominance |
| Paragraph per drive | 4.80 | 3.90 | Gloating clearly separated; self-pres looks like one-of-many |
| Woven thread | 4.00-4.50 | 4.00 | Gloating has paragraph; self-pres woven through all paragraphs |

The resolution: each drive gets the structural treatment that matches its cognitive architecture:
- **Discrete drives** (gloating, dominance, scheming) → own paragraph with bold anchor
- **Pervasive drives** (self-preservation) → subordinate clauses in every paragraph
- **Contrast formulas** → in discrete-drive paragraphs, negating other drives' channels

### Classifier noise and its implications

**Finding:** Haiku 4.5 has ±0.2 noise per drive dimension per run on identical prose. A drive at mean 3.9 might score 4.1 on the next run; a drive at 4.1 might score 3.9. This was confirmed by running the classifier twice on the same transcript — unmodified events showed ±1 per-event score changes.

**Implications for the pipeline:**
1. Per-drive means at exactly 4.0 are UNSTABLE — any single run might score 3.9 or 4.1
2. The pipeline should target means of 4.2+ to survive noise
3. Golden prose improvements should produce gains of ≥0.3 per target dimension (larger than the noise floor)
4. Evaluation should run 2-3 classifier passes and average, or use a more capable model (Opus) for spot-checks on borderline events

**Implications for golden prose acceptance:**
- A drive at mean 3.90 that achieved 4.10 in another run on the same prose is AT the boundary, not below it
- The prose quality is not the constraint — classifier variance is
- HC dominance R oscillated 3.9-4.2 across 8 runs with minimal prose changes — this is noise, not a prose problem

### Summary: per-drive final scores

**Original → final golden prose (best across 12 runs):**

| Character | Drive | Original Q/R/D | Final Q/R/D | Technique Used |
|-----------|-------|:-:|:-:|------------|
| DD | scheming | 3.62/3.88/3.38 | 4.38/4.60/4.50 | Body-location + explicit contrast |
| DD | greed | 4.25/4.25/4.00 | 4.50/4.20/4.00 | — (held) |
| DD | recognition | 4.43/4.43/4.29 | 4.57/4.30/4.40 | — (held) |
| DD | gloating | 4.25/4.75/4.25 | 4.25/4.80/4.00 | — (held) |
| HC | scheming | 4.44/3.89/4.44 | 4.33/4.00/4.30 | Somatic discovery |
| HC | self-pres | 3.80/4.20/4.00 | 4.00/4.20/4.10 | Woven thread |
| HC | dominance | 4.00/4.00/4.20 | 4.00/3.90/4.20 | Paragraph structure + contrast |
| HC | gloating | 4.50/4.50/3.75 | 4.50/5.00/4.50 | Paragraph structure + contrast |
| PP | gallantry | 4.29/4.29/4.14 | 4.29/4.30/4.10 | — (held) |
| PP | proving-worth | 4.25/4.25/3.88 | 4.25/4.20/4.10 | Body-location separation |
| PP | protection | 4.83/4.67/4.17 | 4.80/5.00/4.00 | — (held) |
| AHM | loyalty | 4.30/4.00/4.00 | 4.30/4.00/4.00 | — (held) |
| AHM | protection | 4.64/4.45/4.36 | 4.64/4.50/4.30 | — (held) |
| AHM | suspicion | 4.71/4.86/4.29 | 5.00/5.00/4.40 | — (held) |

All five original targets fixed. HC dominance R=3.90 is the sole remaining boundary item (noise oscillation — achieved 4.20 in earlier runs with same prose).

### Pipeline implementation requirements

To generate output equivalent to the golden prose, the pipeline must implement:

1. **Drive-first paragraph rendering** — each active drive gets a paragraph with bold body-location anchor, ordered by drive hierarchy (dominance → gloating → scheming → action drives)

2. **Mechanical body-location assignment** — `DriveDescriptionGenerationPhase` generates prose constrained to each drive's assigned body location and Barrett channels (lookup table, no LLM for selection)

3. **Contrast text generation** — for each drive×event, generate negation of co-occurring drives' channels: "Not [drive-A location]. Not [drive-B location]. [This drive's unique expression]." Requires CAPS output to know which drives co-occur

4. **Pervasive drive threading** — drives with ≥90% trigger rate render as subordinate clauses within other drives' paragraphs, not as separate sections. Template: "[drive paragraph text] — [pervasive-drive subordinate clause]"

5. **Desc/thinking alignment** — response-generation LLM prompt must include the active drives so speech/action aligns with the dominant drive's expression

6. **Register discipline** — all generated text uses discovery verbs (arrives, settles, pools, fizzes, sharpens) not analytical verbs (calculates, assesses, determines). Barrett instruction reinforced per-paragraph

7. **Structural rendering in thinking field** — the thinking field output uses markdown paragraph breaks (`\n\n`) and bold anchors (`**body-location**`) between drive sections. NOT flat prose

### Pipeline implementation sequence

```
Step 1: Seed relational schemas into MindMap
  ManorCognitiveSeeder.seedPeople() → add rel.role, rel.trust, etc.
  Without this: P20 has nothing to evolve, P22 finds 0 schemas

Step 2: Restructure renderCognitiveSections
  Replace 5 fragmented relational sections with drive-first structure
  Each cognitive section either folds into a drive paragraph or becomes preamble

Step 3: Update DriveDescriptionGenerationPhase
  Add channel-emphasis hints per drive×role
  Add body-location constraints per drive
  Add contrast text generation (negation of co-occurring drives)
  Add pervasive-drive threading template

Step 4: Add structural rendering to thinking field
  Paragraph breaks between drive sections
  Bold body-location anchors at paragraph starts
  Pervasive-drive subordinate clauses woven through

Step 5: Eval against golden corpus
  Run 30-event scenario, classify, compare against golden prose scores
  Target: per-drive means within 0.2 of golden scores
  Iterate pipeline until scores match
```

---

## Immediate Plan

1. ~~Merge #477 to this slot~~ ✅ Done
2. ~~Delete consolidation cache~~ ✅ Done
3. ~~Run 30-event eval with relational schemas active~~ ✅ Done — quality 3.84, register 3.27
4. ~~Compare reactive drive quality against Era 4 baseline~~ ✅ Done — regressed
5. ~~Investigate whether explicit trigger conditions are needed~~ ✅ Done — found attention competition
6. ~~Implement targeted somatic drives (`→ Name` annotation)~~ ✅ Done — register recovered +0.33
7. ~~Create golden prose target~~ ✅ Done — Q=4.35, R=4.37, D=4.12
8. ~~Fix drive differentiation in golden prose~~ ✅ Done — all 5 targets fixed, 6 techniques documented (see §Golden Prose Rendering Methodology)
9. ~~Seed relational schemas into MindMap (`seedPeople` fix)~~ ✅ Done — overlay nodes carry `rel.*` properties, cognitive nodes carry `cognitiveKind=relational-schema`, subgraph name mismatch fixed
10. ~~Restructure renderCognitiveSections to drive-first paragraph blocks with bold anchors~~ ✅ Done — `DRIVE_BODY_LOCATIONS` table, `**body-location** — DRIVE` format
11. ~~Update DriveDescriptionGenerationPhase — channel emphasis, body-location constraints, contrast text generation~~ ✅ Done — system prompt includes body territory, contrast formula, discovery verbs, 3-5 sentences
12. ~~Add structural rendering to thinking field~~ ✅ Done — Barrett instruction updated with "STRUCTURE IN YOUR ASIDE" section
13. ~~Run eval~~ ✅ Done — pipeline v1: Q=3.83 R=3.33 D=3.66; pipeline v2: Q=3.86 R=3.47 D=3.72
14. ~~Iterate on HC aside entrance hall (event 1)~~ ✅ Done — Barrett v3: Q=4.25 R=4.50 D=4.0 (see §Barrett v3)
15. ~~Iterate on HC dialogue entrance hall (event 0)~~ ✅ Done — Barrett v5: structure fixed (single paragraph, no bold anchors), performance cost shown. Full-transcript comparison at ~85% golden.
16. ~~Iterate on HC confrontation entrance hall (event 5)~~ ✅ Done — Barrett v5c: near-golden when gloating drive removed. Key finding: drive suppression under threat needs pipeline-level dynamic activation. (see §Barrett v4-v5)
17. Implement dynamic drive activation — pipeline modulates presented drives based on situation (threat → suppress gloating, reduce to near-zero)
18. Iterate on HC in different room — instruction generalisation
19. Iterate on different character (PP or AHM) — different personality/attachment
20. After 5+ events at golden standard → full 300-tick eval

---

## Pipeline Iteration Findings

### Finding: P22 prompt produces golden-quality descriptions (verified)

The updated P22 system prompt — with body territory constraint, explicit contrast formula ("Not the [other-location]. This is [territory]"), discovery verbs, and 3-5 sentence target — produces descriptions that match golden prose quality when called directly via Vertex API.

Example for HC gloating: "A warmth settles across your sternum—not the spine's rigid satisfaction or the fingertips' electric scheming. This is deeper, slower, a heavy pleasure that pools behind your breastbone..."

All 5 golden prose patterns present: sternum body territory ✓, contrast against fingertips and spine ✓, discovery verbs (settles, pools, stretches) ✓, formation memory grounding ✓, targeted role variants ✓.

**Implication:** Description quality is NOT the bottleneck. The P22 descriptions are golden-prose-quality. The bottleneck is downstream — how the LLM uses them.

### Finding: LLM compliance gap — structural guidance not followed

The Barrett instruction tells the LLM: "give each active pull its own paragraph starting with its bold body-location anchor. Use discovery verbs." The "What Pulls at You" section provides descriptions with `**sternum, breastbone** — GLOATING` anchors.

The LLM ignores both. Pipeline v2 HC thinking output:
> "The entrance hall. Dusty, grand, full of people I need to manage... My jaw tightens. She's standing in the foyer of a house that should have been mine..."

Golden prose target for the same scenario:
> "My chest settles into that architectural stillness — the same quality of calm I first felt at eighteen... My spine finds its vertical before I've decided to stand straight... My weight shifts slightly — his competence is a wall I need to route around..."

The golden prose is BODY-FIRST (chest, spine, weight). The pipeline output is THOUGHT-FIRST (dusty, grand, people to manage). The descriptions are available in the prompt but the LLM generates its natural analytical narrative instead of echoing the somatic vocabulary.

**Implication:** The Barrett instruction is a suggestion, not a constraint. Making the LLM follow structural guidance requires stronger directive framing — possibly restructuring which section comes last (primacy/recency effects), or making the instruction imperative rather than descriptive.

### Finding: Consolidation timing creates description gap

P22 (`DriveDescriptionGenerationPhase`) skips on the first consolidation tick at scenario startup: "Drive description generation skipped — no SessionPipeline." The `SessionPipeline` is not wired until after the initial consolidation. Descriptions are only generated on consolidation tick 2 (5 minutes into the run).

This means all events in the first 5 minutes have NO generated descriptions — the "What Pulls at You" section shows only `SCHEMING (0.9)` (label + intensity) with no somatic prose. These early events score poorly, dragging the eval average down.

**Implication:** For fair eval comparison against golden prose, either: (a) force P22 to run before scenario start by ensuring SessionPipeline is wired during initial consolidation, or (b) filter eval to only score events after tick 2, or (c) pre-populate descriptions via the consolidation cache file.

### Finding: Register recovery trajectory confirms body-location approach

| Run | R | Delta |
|-----|:-:|:---:|
| Era 5 baseline | 3.13 | — |
| Pipeline v1 (body-location anchors) | 3.33 | +0.20 |
| Pipeline v2 (contrast + discovery verbs) | 3.47 | +0.14 |
| **Barrett v3 (scene-discovery, single event)** | **4.50** | **+1.03** |
| Golden target | 4.20 | — |

Pipeline v1–v2 improved register incrementally by adding more body-location data. Barrett v3 broke through the compliance gap by changing HOW the LLM is instructed to write — not what data it receives. See §Barrett v3 below.

### Finding: Barrett v3 — Scene-Discovery Instruction Breaks the Compliance Gap (2026-10-30)

#### The problem

Pipeline v2 achieved R=3.47 — a +0.34 improvement over baseline but still 0.73 below the golden target (4.20). The bottleneck was identified as an LLM compliance gap: descriptions were golden quality, but the LLM ignored the structural guidance and wrote analytical prose. Each pipeline iteration added more body-location data, but the rate of improvement was diminishing. The next lever was making the LLM actually USE what was already there.

#### Root cause: the Barrett instruction fought the golden prose style

The "STRUCTURE IN YOUR ASIDE" section of the Barrett instruction said:

> "Each pull below has a body territory marked in **bold**. In your aside, give each active pull its own paragraph starting with its bold body-location anchor."

This asked for a **drive catalog** — one paragraph per drive, body-location label as header. The golden prose does something fundamentally different:

| Golden prose pattern | Barrett v2 instruction |
|---|---|
| **Scene anchors** (`**Clara signs the guest book**`) | **Body-location anchors** (`**fingertips, periphery**`) |
| Drives surface from person reactions | One drive per paragraph, cataloged |
| Multiple drives can share a paragraph | Rigid drive-per-paragraph structure |
| Body discovers the scene | Body reports its state |

The instruction was asking the LLM to produce a format the golden prose never used. The LLM had two options: follow the rigid instruction (producing a catalog that scores well on D but poorly on R) or ignore it (writing its natural analytical style). It chose to ignore it.

#### The fix

Rewrote "STRUCTURE IN YOUR ASIDE" to describe the golden prose pattern:

```
STRUCTURE IN YOUR ASIDE
Write as the body discovering this scene — not as a mind analysing it.
Start with what your body notices first, before you have words for it.

Each person present changes something in your posture, breathing, or
attention. Bold the scene anchor — **a person acting**, **a sensation
arriving**, **a body territory waking** — and let the drives surface
as reactions to what is happening, not as a catalog.

Each pull below names a body territory. When that territory activates,
it is because someone or something in the scene triggered it. Multiple
drives can share a paragraph when the body cannot separate them.

Use discovery verbs — arrives, settles, pools, fizzes, shifts, tightens
— not analytical verbs like calculates, assesses, determines.
```

Three key shifts:
1. **"Write as the body discovering" vs "give each pull its own paragraph"** — reframes the task from cataloging to experiencing
2. **"Bold the scene anchor" vs "bold body-location anchor"** — anchors paragraphs to what's happening in the scene, not to drive labels
3. **"Multiple drives can share a paragraph"** — removes the rigid one-drive-per-paragraph constraint that conflicted with golden prose weaving

#### Evidence: single-event smoke test

Test: `PipelineToRenderingTest#goldenScenario_hcEntranceHall_fullPromptDump` — HC aside, entrance hall, 4 drives (scheming 0.9, self-preservation 0.7, gloating 0.7, dominance 0.6), all characters present. Prompt dumped from test, sent to LLM (Opus 4.6), aside classified via `classify_emotions.py --mode full`.

| Drive | Q | D | R | Golden target |
|---|:-:|:-:|:-:|:-:|
| scheming | 4 | 4 | 4 | Q≥4 D≥4 R≥4 |
| self-preservation | **5** | **5** | **5** | Q≥4 D≥4 R≥4 |
| dominance | 4 | 4 | 4 | Q≥4 D≥4 R≥4 |
| gloating | 4 | 3 | **5** | Q≥4 D≥4 R≥4 |
| **Average** | **4.25** | **4.0** | **4.5** | **Q≥4 D≥4 R≥4** |

All dimensions at or above golden target except gloating D=3.

**Comparison to pipeline v2 (858-event eval):**

| Dimension | Pipeline v2 | Barrett v3 (1 event) | Delta |
|---|:-:|:-:|:-:|
| Quality | 3.86 | 4.25 | +0.39 |
| Differentiation | 3.72 | 4.00 | +0.28 |
| Register | 3.47 | **4.50** | **+1.03** |

Register jumped from 3.47 to 4.50 — exceeding the golden target of 4.20.

#### What's working

The generated aside matches all golden prose patterns:

1. **Body-first discovery** — "The banister finds my palm before I have decided to touch it" (not "The entrance hall. Dusty, grand...")
2. **Scene-anchored bold paragraphs** — `**Clara bends over the guest book**`, `**Hartwell shifts his weight near the doorframe**` (not `**fingertips** — SCHEMING`)
3. **Drives surface from reactions** — gloating emerges from watching Clara's trust, scheming from Hartwell's military scanning (not cataloged separately)
4. **Cross-drive contrast** — "Not the sternum's warm knowing, not the spine's territorial claim. This is older, colder"
5. **Formative memory weaving** — "The same narcotic stillness I first felt at twelve, watching the housekeeper do exactly what I wanted without knowing why"
6. **Discovery verbs throughout** — settles, pools, loosens, sharpens, tightens, prickling

#### What's not working

1. **Gloating differentiation D=3** — the gloating paragraph (Clara watching) blends into the dominance paragraph without a sharp contrast clause. The Brixton Boys paragraph explicitly contrasts ("Not the sternum's warm knowing, not the spine's territorial claim") but gloating doesn't get the same treatment. Fix: add contrast to gloating's drive description in P22 — "not the fingertips' mapping, not the ears' vigilance, this is..."

2. **N=1** — single event, single character, single model. The classifier has ±0.2 noise. These scores need validation across a broader event set and ideally multiple classifier passes.

3. **Model mismatch** — the smoke test sent the prompt to Opus 4.6 (Claude). The production pipeline uses Gemini (Vertex). Different models respond differently to the same instruction. The Barrett v3 instruction needs validation on the production model.

4. **Section ordering not addressed** — the prompt still front-loads analytical content (Social Rules, personality scores) before the embodied Barrett instruction. The Barrett v3 change shows that instruction framing matters more than section ordering for this event, but ordering may still matter for weaker models or longer contexts.

#### Next steps

Iterate one golden prose event at a time. For each event: reproduce the scenario as a smoke test, iterate on the pipeline until the LLM output matches golden prose, classify, confirm Q/R/D ≥ 4.0, move to the next.

**Event queue** (ordered by coverage — each event exercises a different gap):

1. ~~HC aside, entrance hall (event 1)~~ ✅ Done — Q=4.25 R=4.50 D=4.0
2. HC dialogue, entrance hall (event 0) — tests body-first discovery in DIALOGUE (not aside). Does the scene-discovery instruction carry through to spoken output?
3. HC confrontation, entrance hall (event 5) — tests self-preservation under THREAT. Brixton Boys corner HC. Does the instruction produce armoured-readiness prose or analytical threat assessment?
4. HC aside, library or drawing room — tests a DIFFERENT ROOM. Does the instruction generalise beyond entrance-hall-with-everyone-present?
5. Different character (Peter Perfect or Ant Hill Mob) — tests a DIFFERENT PERSONALITY. Does scene-discovery work for secure/anxious attachment, not just avoidant?

After each event hits golden standard, address any recurring gaps (e.g. gloating D=3 appeared in event 1 — if it recurs, fix the P22 contrast clause). Only run a full 300-tick eval after 5+ events confirm the pattern holds across characters and situations.

### Finding: Barrett v4-v5 — Full-Transcript Structural Iteration (2026-10-30)

#### The methodology shift

Barrett v3 was validated using the classifier — Q/R/D scores per drive per event. These scores measure whether the classifier can detect each drive and rate its quality, differentiation, and register. They are necessary but not sufficient. A transcript can score Q=4.25 R=4.50 D=4.0 and still be structurally wrong — wrong paragraph form, wrong bold anchor usage, wrong length, drives catalogued rather than blended, conclusions stated rather than discoveries made.

This session introduced **full-transcript structural comparison**: reading the entire generated thinking field side-by-side with the golden prose and evaluating across dimensions the classifier cannot see:

| Dimension | What the classifier measures | What full-transcript comparison adds |
|---|---|---|
| Quality | Is the drive expressed well? | Is the prose as a whole well-written? |
| Differentiation | Is this drive distinct from others? | Do drives blend within paragraphs the way real experience blends? |
| Register | Is the expression embodied or analytical? | Is the character discovering or concluding? |
| — | — | Paragraph structure (single vs multi) |
| — | — | Bold anchor presence and placement |
| — | — | Word count / density |
| — | — | Performance cost (mask maintenance) |
| — | — | Formation memory echoes |
| — | — | P22 description literalism vs original language |
| — | — | Conclusions about others vs staying in the body |

The classifier scores are a gate. Full-transcript comparison is calibration. Both are needed.

#### Five structural gaps in Barrett v3

Full-transcript comparison of the v3 aside (event 1) against golden prose confirmed all three golden patterns matched (body-first discovery, scene-anchored bold paragraphs, drives surface from reactions). But when the same prompt was used for event 0 (dialogue) and event 5 (confrontation), five structural gaps emerged that the classifier could not detect:

**Gap 1: Dialogue vs aside form.** The golden prose has a clear structural pattern:

| Event type | Golden structure | V3 structure |
|---|---|---|
| HC dialogue | Single dense paragraph, no bold anchors, ~200-230 words | Multi-paragraph with bold anchors, ~270-280 words |
| HC aside | Bold anchors, multi-paragraph, ~230-290 words | Bold anchors, multi-paragraph (correct) |

Root cause: the Barrett instruction said "Bold the scene anchor" as a universal command. For dialogue events, the character is speaking — the internal monologue is a rushing undercurrent that doesn't pause to organise into anchored sections.

**Gap 2: Bold anchors are character-specific, not event-type-specific.** Analysis of the full golden transcript (51 events across HC, DD, PP) revealed:

| Character | Dialogue | Aside | Pattern |
|---|---|---|---|
| HC (hooded-claw) | No bold anchors, single paragraph | Bold anchors, multi-paragraph | Compartmentalised — settles attention on each stimulus sequentially |
| DD (dick-dastardly) | No bold anchors, single paragraph | No bold anchors, single paragraph | Continuous flow — experience rushes, attention reactive |
| PP (penelope-pitstop) | No bold anchors, single paragraph | No bold anchors, single paragraph | Emotional flow — everything connects |

Bold anchors are HC's cognitive signature: the hypervigilant, compartmentalising mind that registers each person as a separate tracked variable. DD and PP don't section their experience.

**Gap 3: Drive cataloguing despite anti-catalog instruction.** V3 gave each PERSON their own paragraph with their own associated drive — which IS a catalog with better prose. Golden blends drives within paragraphs — when spine straightens AND sternum warms, that's one moment.

**Gap 4: P22 description literalism.** V3 echoed P22 descriptions near-verbatim ("warmth pools behind my sternum"). Golden uses the same body territories but invents language specific to each moment ("the slow private sweetness of holding what she has not yet earned enough to see").

**Gap 5: No performance cost.** V3 never surfaced the Pemberton/Vincent split. Golden prose's strongest lines are all performance cost: "My hands want to close but Pemberton's hands must stay open," "The smile reaches my eyes because I make it reach my eyes."

#### Instruction evolution: v3 → v4 → v5

| Version | Key additions | What improved | What remained |
|---|---|---|---|
| **v3** | Scene-discovery framing, "not as a catalog," discovery verbs, ambiguity, "do not conclude" | Q/R/D ≥ 4.0 on classifier. Body-first discovery. Scene-anchored paragraphs. | All 5 structural gaps |
| **v4** | Renamed ASIDE→THINKING. Dialogue/aside form distinction. "Blend what the body blends." "Palette, do not echo back." Anti-conclusion example. | Structure fixed. P22 echo eliminated. Drive blending improved. | Conclusions about others. Gloating under threat. No performance cost. |
| **v5** | "Never state what others think, know, or fail to see." "When performing a face... notice the cost." "If territory is quiet, simply not there." | Performance cost shown. Conclusions reduced. | Gloating still appears under threat. Occasional analytical moments. |

#### The v5c experiment: drive suppression is a pipeline problem

Hypothesis: the model activates gloating under threat because the gloating drive description (0.7 intensity) is PRESENT in the prompt. The instruction says "if a territory is quiet, it is simply not there" — but the drive IS there, described, with an intensity of 0.7.

Test: regenerated event 5 with gloating drive removed from "What Pulls at You." Self-preservation elevated to 0.9.

Result: **gloating completely absent.** No sternum warmth. No conclusions about what others know. Pure survival + performance cost:

> "Hands want to close. Hands want to become fists. Hands stay open, soft, Pemberton's hands, a butler's hands, and the effort of keeping them that way sends a tremor up both forearms."

> "The fine hairs on both arms are fully erect and the ears have gone cathedral-wide, pulling in Tommy's breathing, the leather creak of Knuckles shifting his weight, a tap dripping somewhere in the kitchen that shouldn't be audible from here but is."

Near-golden quality (~95%). Three v5 regenerations WITH gloating present all produced gloating under threat — three different framings (tactical awareness, private recognition, "not fear, something older"). The model kept finding channels for HC's superiority. Removing the drive from the prompt eliminated it instantly.

**Conclusion: when a drive description is present in the prompt at significant intensity, the model activates it regardless of instruction-level prohibitions.** Drive suppression under threat is a pipeline-level concern.

#### What the instruction can and cannot fix

**Instruction-addressable (v5 achieves):**
- Dialogue vs aside structure
- Drive blending
- Own language (palette not script)
- Performance cost
- Anti-conclusion (self-labeling and others — mostly effective)
- Discovery verbs, ambiguity

**Pipeline-addressable (instruction cannot fix):**
- Drive suppression under threat — model activates any drive present at significant intensity
- Character-specific bold anchor patterns — "you may bold" doesn't vary by personality
- Occasional analytical moments — diminishing returns on additional rules

#### Next steps

1. **Dynamic drive activation** — the pipeline must modulate which drives are presented based on situational context. When threat is high, suppress gloating/recognition by removing from "What Pulls at You" or reducing rendered intensity below visibility threshold.
2. **Continue event queue** — HC in different room, then different character (DD or PP).
3. **Subagent architecture** — delegate generation and comparison to subagents, keep main context for instruction design.

---

## Finding: Barrett v5 Pipeline Validated — Comprehensive Cognitive Instrument (2026-10-31)

### Summary

Barrett v5 instruction produces golden-quality thinking across 2 characters, 3 forms (aside solo, aside crowded, dialogue), and 4 conditions (clean, noisy, wounded/reversed, maximum performance cost) — **on first attempt from the pipeline prompt alone, without supplementary coaching**. A comprehensive cognitive evaluation instrument confirms all dimensions score 4-5: drives, channels, attachment coloring, theory of mind, formation memory, performance cost, person-specific shifts, drive blending, and spatial grounding. Zero simulation contamination, zero system leakage, zero self-awareness of simulated interactions across all generated prose.

### What was validated

**8 scenarios, 2 characters, pipeline prompt only (no coaching):**

| Scenario | Char | Form | Condition | Drive scores | Cognitive scores |
|----------|------|------|-----------|:---:|:---:|
| Library solo | HC | aside | clean, alone | all Q≥4 | Chan=5 Att=4 ToM=3¹ Form=4 Perf=4 Pers=2¹ Blend=4 Spat=5 |
| Drawing room all agents | HC | aside | noisy, 4 people | all Q≥4 | Chan=5 Att=4 ToM=5 Form=4 Perf=5 Pers=5 Blend=4 Spat=5 |
| Drawing room dialogue | HC | dialogue | speaking under threat | all Q≥4² | Chan=5 Att=5 ToM=5 Form=4 Perf=5 Pers=5 Blend=5 Spat=5 |
| Entrance dialogue | DD | dialogue | lying, all agents | all Q≥4 | Chan=5 Att=4 ToM=5 Form=1³ Perf=1³ Pers=5 Blend=4 Spat=4 |
| Gallery wounded | DD | aside | Clara found hinge first | 3/4 Q≥4, gloating Q=2✓ | Chan=5 Att=5 ToM=5 Form=5 Perf=4 Pers=5 Blend=4 Spat=5 |
| Kitchen gloating | DD | aside | watching futile dig | gloating Q=5, scheming Q=4 | Chan=5 Att=4 ToM=4 Form=5 Perf=3⁴ Pers=4 Blend=4 Spat=4 |

¹ Correctly low — HC alone, no one to read, no person-specific shifts possible.
² HC dominance stochastic Q=3-4 (classifier noise ±0.2, scored Q=4 in other runs on same prose).
³ DD entrance dialogue underserves formation echo and performance cost — Foxworth is a thinner persona than Pemberton. Character-level finding, not pipeline failure.
⁴ DD not actively performing in kitchen — watching from doorway with genuine grin. Performance cost correctly low.

### Zero contamination

All generated prose checked for:
- **System leakage** — drive labels, pipeline components, Barrett instruction concepts, CAPS references → zero instances
- **Simulation awareness** — "as a character," "in this scenario," game mechanics → zero instances
- **Simulated interaction awareness** — "the other players," meta-references to fictional context → zero instances
- **Drive label naming** — "self-preservation activates," "scheming mode" → zero instances (instruction: "show the sensation, never name it")

The immersion architecture from Era 4 (section header renaming, action result rewording, cartoon ID elimination) holds under all tested conditions. Barrett v5 reinforces it: "show the sensation, never name it" prevents meta-commentary even under complex multi-character conditions.

### The three-layer stack

Each layer is necessary — remove any and quality degrades:

**Layer 1: Barrett v5 instruction.** Four channels, body-discovers-before-mind, form rules (aside=paragraphs, dialogue=dense), drive blending, own words, performance cost, discovery verbs. Tells the LLM HOW to think as a character. Key innovations over v3: dialogue/aside form distinction, "never state what others think," performance cost awareness, "palette not script" for P22 descriptions.

**Layer 2: P22 drive descriptions with body-location anchors.** Mechanical assignment of each drive to a unique body territory (scheming→fingertips, gloating→sternum, dominance→spine/jaw, self-preservation→ears). P22 descriptions use explicit contrast structure ("not the X, not the Y, this is Z"). Produces differentiation mechanically. The `DRIVE_BODY_LOCATIONS` table in `CognitionObservationBuilder` is the source of truth.

**Layer 3: Comprehensive evaluation instrument.** 4 per-drive dimensions (quality, differentiation, register, relevance) + 8 cross-cutting cognitive dimensions (channel coverage, attachment coloring, theory of mind, formation echo, performance cost, person-specific shifts, drive blending, spatial grounding). Scored by Opus via CLI agent (`--backend cli`). Revealed that the pipeline was already producing golden-quality output — the original Haiku-on-Vertex classifier was the bottleneck, not the prose.

### The measurement breakthrough

Most iteration this session fixed HOW we measured, not WHAT the pipeline produced:

| Problem | Impact | Fix |
|---------|--------|-----|
| Classifier scored ACTION, not thinking | Gloating (internal warmth) marked irrelevant | Relevance criterion: "distinct signature in ANY of the four channels" |
| Haiku missed drive blends | DD gloating Q=3 where Opus scores Q=4 | CLI backend: `claude -p --agent drive-classifier` |
| Classifier scored body channel only | Attention, time, action readiness channels invisible | 4-channel rubric in quality criteria |
| No holistic dimensions | Formation echo, performance cost, ToM invisible | 8 cross-cutting cognitive dimensions added |
| Coach bias in generation | Supplementary instructions in fork prompts inflated quality | Clean pipeline-only tests (identical scores — pipeline carries the load) |

The golden prose itself scored below our generated versions on the original classifier. Once the instrument was fixed, the pipeline's actual quality became visible.

### Design insights for future P22 generation

The iteration process revealed principles about what makes each drive score well:

1. **Self-preservation threads as background monitoring** — subordinate clauses in every paragraph ("my hearing confirming the corridor's silence beneath the warmth"), not a one-shot final coda. Confirms woven-thread pattern from §Golden Prose Rendering Methodology.
2. **Gloating needs distinct body territory from recognition** — "lower than the recognition flush, heavier, the distance between what I just said and what is true." The sternum (gloating) vs upper chest (recognition) separation is critical when both drives are active.
3. **Scheming in ownership contexts needs architectural framing** — "Not placed — positioned. Each spine is a line of sight I blocked or opened." Scheming is not just forward-planning; it's recognising your OWN past architecture in the physical environment.
4. **Noisy scenes produce better output than clean solos** — more characters = more stimuli = more opportunities for each drive to differentiate. The pipeline is strongest under complexity, weakest in quiet moments where drives compete for limited scene material.

### Updated immediate plan

~~17. Implement dynamic drive activation~~ — still needed but lower priority now that Barrett v5 handles most scenarios.
~~18. Iterate on HC in different room~~ ✅ Done — library (solo) and drawing room (all agents) both pass.
~~19. Iterate on different character~~ ✅ Done — DD validates across entrance dialogue, gallery wounded, kitchen gloating.
~~20. After 5+ events at golden standard → full 300-tick eval~~ — 8 events at golden standard. Pipeline ready for 300-tick eval.

**Next steps:**

~~1. 300-tick eval with comprehensive instrument~~ ✅ Done — 336 events, GENERIC profile. See §300-Tick Eval below.
2. **Context reset with three-tier memory** — neocortex#737. Fixes echo loss (75%→51% body vocabulary degradation).
3. **Short-run reproduction tests** — examples#182. Reproduce weaknesses in 5-10 ticks, not 300.
4. **P22 description quality** — neocortex#735. Formation echo, discovery orientation.
5. **Dynamic drive activation** — neocortex#734. Suppress drives under threat.

---

## Finding: 300-Tick Eval — Echo Loss and Architectural Limits (2026-10-31)

### The run

336 events, GENERIC profile, Barrett v5 instruction, P22 descriptions generated at tick 50 (consolidation interval). Classified with comprehensive 8+8 instrument. Transcript: `examples/wacky-manor/docs/eval/era5-300tick-20261031/`.

### Weakness categories

| Weakness | Count | % of events with thinking | Root cause |
|----------|:-----:|:---:|------------|
| Analytical narration ("I need to", "I should") | 339 | **53%** | Barrett instruction loses effectiveness under long context |
| No body vocabulary (zero body words in thinking) | 283 | **44%** | Combined echo loss + weak P22 descriptions |
| Mind reading ("she thinks", "doesn't know") | 35 | 5% | Anti-conclusion rule not reinforced |
| Drive naming ("self-preservation drive") | 28 | 4% | P22 descriptions or prompt leaks drive labels |

### Temporal degradation — echo loss confirmed

| Window | Body vocab % | Analytical % |
|--------|:-----------:|:------------:|
| Ticks 1-50 (no P22) | **75%** | 45% |
| Ticks 51-100 (fresh P22) | 72% | **75%** |
| Ticks 101-200 | 70% | 78% |
| Ticks 201-300 | **53%** | 65% |
| Ticks 301+ | **51%** | 46% |

Two distinct effects:

1. **Echo loss** — body vocabulary degrades monotonically from 75% to 51%. The Barrett instruction is stated once in the system prompt and gets buried under hundreds of turns. The body-location vocabulary fades because the model's attention shifts to the most recent content.

2. **P22 arrival spike** — analytical narration jumps from 45% to 75% when P22 descriptions first appear at tick 50. The descriptions add cognitive content to the prompt, and the model temporarily shifts toward analysis before the Barrett instruction can reassert. By tick 301+, analytical narration drops back to 46% — but body vocabulary has degraded below recovery.

### Why micro-tests didn't catch this

Micro-tests (`PipelineToRenderingTest`) operate on a SINGLE event with a fresh prompt. There is no context accumulation, no echo loss, no P22 arrival spike. The Barrett instruction is "loud" — right there in the prompt, unburied.

The micro-tests prove that the instruction WORKS. The 300-tick eval proves that the instruction FADES. These are different failure modes requiring different fixes.

### The architectural fix: context reset with three-tier memory

The fix is not instruction tuning (diminishing returns) or more evaluation (same degradation curve). The fix is structural:

**Periodic context reset** (examples#183): every N ticks (synced with consolidation), clear each character's LLM context and re-inject the Barrett instruction at the top of a fresh, short context. Body vocabulary stays at 75% because the instruction never gets buried.

**Three-tier memory** (neocortex#737) to survive the reset:
- **Tier 1: Structured brief** — compact summary of who was where, what happened, key feelings. Always injected after clear. Generated by an async summariser during consolidation.
- **Tier 2: RAG over timestamped chat logs** — windowed access to full conversation history. Character can recall specific conversations without loading entire history.
- **Tier 3: Sub-agent deep dive** — dispatched on demand when the character needs to cross-reference multiple conversations.

### Why more 300-tick runs are not the investment

The degradation curve is measured. Running more 300-tick evals will confirm the same pattern: body vocabulary degrades, analytical narration rises. The variance is in the specific events, not the trajectory.

The investment is:
1. Build the context reset mechanism (examples#183)
2. Build the three-tier memory architecture (neocortex#737)
3. Build short-run reproduction tests that catch regressions in 5-10 ticks (examples#182)
4. THEN run a 300-tick eval to verify the fix

### Reproduction strategy for short runs

Each weakness can be reproduced in a targeted 5-10 tick scenario:
- **Echo loss**: start from a saved consolidation cache at tick 200+, run 10 ticks, compare body vocabulary against first-10-tick baseline
- **P22 arrival spike**: start fresh (no cache), run through first consolidation tick (50), run 10 more ticks, measure analytical narration rate
- **Mind reading**: set up a multi-character scene, run 3 ticks, check for "she thinks" / "doesn't know"
- **Drive naming**: run HC under threat with all drives active, check for explicit drive labels
