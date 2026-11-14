# Era 2: How the Pipeline Expresses Drives — CAPS, Appraisal & Differentiation

**Status:** COMPLETE

Rolling research journal for the drive expression investigation. Covers the architectural wiring of disconnected cognitive subsystems, formation memory audit and redesign, metacognitive monitoring discovery, discriminative classification methodology, the regression recovery from BASELINE2, pipeline instrumentation, appraisal filter mechanics, and the discovery that the "regression" was quality redistribution.

---

## Context from Era 1

Era 1 (`era1-character-taxonomy-research.md`) established:

- **BASELINE0:** full brief, mean drive expression 3.93. All tendencies, no metacognitive instruction.
- **L1 emotional echo:** mean 4.06 (+0.13). Improves 13/17 drives but regresses 4: PP protection (-1.13), DD gloating (-0.48), DD recognition (-0.37), PEN social-harmony (-0.33). These 4 regressions motivated this era's pipeline work.
- **Drives are authoritative** (P1). Tendencies that restate them are redundant. Drives carry personality; the pipeline controls how they express.
- **Identity activation** (P13, P19). "Think AS, not ABOUT" + "name your feeling" = compounding feedback loop. L1 instruction stays unchanged throughout Era 2.
- **Analytical instructions kill expression** (P11). The main agent must stay immersive. Sub-LLMs can be analytical (P39).
- **Phase 7 validated architecture:** minimal drives + one Haiku appraisal call + L1 instruction.

---

## Drive Expression Gap Investigation (2026-10-09 to 2026-10-18)

Six blog entries from the concurrent issue-115 investigation documented the theoretical foundation and design methodology that enabled the pipeline fixes in Phases 1-5. These entries cover the period from the initial wiring problem discovery through the metacognitive monitoring breakthrough to the encoding gap resolution. Consolidated from `wsp-casehub` branch `issue-115-drive-expression-gap` (examples#145).

### Journal 1: The Wiring Problem — Connecting Four Disconnected Subsystems (2026-10-09)

Four cognitive subsystems — DriveAdaptationPhase (priority 17), BehavioralSynthesisPhase (priority 19), AppraisalTickParticipant, and formation memory rendering — each worked in isolation but shared no data. DriveAdaptationPhase produced drive-intensity nodes that BehavioralSynthesisPhase never read. AppraisalTickParticipant accepted drives via `AppraisalContext`, but only SDT drives (curiosity, competence, affiliation, autonomy) reached the SEC checks — `DriveCategory.CHARACTER` existed as an enum value imported in zero production classes. The somatic text that told the LLM how each drive feels was the ONLY mechanism connecting drives to LLM output. When it was removed in a prior experiment, gloating collapsed from 3.97 to 2.45 (delta -1.52).

**Literature consulted:**
- Read & Miller (2002) — *Virtual Personalities*, the most elaborated computational CAPS implementation. Goals are activations flowing through weighted connections; connection weights represent stable personality from developmental history. This distinction — activations vs weights — resolved how drives should enter the CAPS network.
- Read, Brown, Wang & Miller (2018) — neurobiological basis of personality. Confirmed that real-time processing operates through activation dynamics, not weight modulation.
- Lazarus (1991) — *Emotion and Adaptation*. Goal relevance is the primary appraisal dimension; greater goal relevance produces more intense emotion. Provided the theoretical basis for wiring character drives into the SEC relevance check.
- Smith & Kirby (2009) — motivational relevance drives emotion intensity. Empirical confirmation of the Lazarus mechanism.
- Mehrabian (1996) — PAD framework. Considered as bridge medium for drive→memory salience via dimensional proximity (Bower 1981; Rubin & Talarico 2009 confirmed dimensional models outperform categorical for memory retrieval). Used for memory salience operationalisation, NOT as a drive-representation medium (rejected in Wrong Turn 1).
- Riddell, Wolf & Lappe (2026) — motivation biases behavioral responses without altering perception (*Communications Psychology*). Supports drives as activations (biasing output) rather than weight changes (altering processing structure).
- Ellsworth & Scherer (2003) — appraisal and emotion. SEC relevance check explicitly evaluates goal/need relevance.
- Moors et al. (2013) — state of the art in appraisal theories. Context for the appraisal integration design.

**Investigation:**

Three wrong turns preceded the working design:

**Wrong Turn 1 — PAD Bridge.** Proposed using PAD space as a shared mathematical foundation: drives get PAD centroids, CAPS nodes get PAD profiles, similarity in PAD space routes activation. Rejected: PAD is an emotion model (Mehrabian 1996), not a motivation model. Two drives with identical PAD profiles could be emotionally similar yet behaviorally opposite. The ~30 CAPS input nodes lack published PAD profiles — most would require researcher-derived values without literature sourcing.

**Wrong Turn 2 — Weight Modulation.** Proposed modulating CAPS connection weights based on drive intensities, extending the existing `disposition_modifiers` mechanism. Intuitively appealing — "motivation creates processing biases, not phantom perceptions." Categorically wrong: Read & Miller (2002) and Read et al. (2018) establish that computational CAPS models use motivational states as activations, not weight modulators. Weights represent stable personality. The theory's own authors made the opposite choice.

**Working design — Persistent Dispositional Activations.** Three types of activation coexist in the CAPS network: (1) Connection weights — stable personality, set from disposition configuration. (2) Situation activations — transient, from `SituationClassifier.classify()`. (3) Drive activations — persistent baseline biases, re-injected each tick from current drive intensities. The behavioral attractor emerges from the interaction of all three.

Three integration pathways:

| Integration | Mechanism | Implementation |
|---|---|---|
| Drive→CAPS | Persistent activations via literature-sourced drive→node mappings | `BehavioralSynthesisPhase.run()` reads drive-intensity nodes, adds to `combinedActivations` before `capsEngine.settle()` |
| Drive→Appraisal | Call-site merge of CHARACTER drives into AppraisalContext | `AppraisalTickParticipant.tick()` reads character drive-intensity nodes from MindMap, converts to `appraisal.Drive` with `DriveCategory.CHARACTER` |
| Drive→Memory | Klinger salience via PAD proximity (Bower 1981 mechanism) | Formation memories sorted/filtered by salience against active drive state; three configurable rendering strategies (prominence ordering, threshold filtering, enrichment injection) |

Drive→CAPS mapping table with literature sourcing:

| Drive | Literature | CAPS Nodes Activated |
|---|---|---|
| Gallantry / prosocial | Batson 1991; Penner et al. 2005 | `other_safety`, `acceptance`, `approach` |
| Proving-worth / achievement | McClelland 1961 | `mastery`, `competence_recognition`, `self_efficacy` |
| Protection / caregiving | George & Solomon 2008 | `other_safety`, `physical_threat`, `approach` |
| Scheming / Machiavellianism | Christie & Geis 1970 | `dominance`, `social_cognition`, `distrust` |

Activation strength: `mapping_weight × drive_intensity × dampening_factor` (default 0.3). Dampening prevents persistent drive activations from overwhelming transient situation activations. Existing clamping [-1, 1] provides a hard ceiling.

**Key findings:**
- The distinction between weights (stable personality), situation activations (transient events), and drive activations (persistent motivation) as three coexisting CAPS input types — sourced from Read & Miller's computational CAPS, not intuited
- Intuitive architectural reasoning ("weight modulation sounds psychologically accurate") can be categorically wrong when checked against the published computational models — added to project CLAUDE.md as the "literature-first" rule
- `DriveCategory.CHARACTER` existed as dead code — the connection was intended but never built
- Each of the three integration pathways replaces a specific function previously served by hand-authored somatic text

### Journal 2: The Drive Expression Gap — Problem Quantification and Formation Memory Audit (2026-10-13)

Moving from prescriptive text (hand-authored somatic descriptions) to the cognitive pipeline caused five specific drive collapses. Overall mean drive expression dropped from 3.96 to 3.70, but the damage was concentrated in five drives. The investigation needed to determine whether the problem was insufficient data richness or a deeper architectural flaw.

**Literature consulted:**
- ACL 2025 — LLM persona research. When few-shot demonstrations and personality instructions conflict, demonstrations win. Formation memories ARE few-shot demonstrations and are therefore the strongest behavioural signal in the prompt. Hand-authored emotional evaluations in memories override what the computational personality model would derive.

**Investigation:**

Five drives collapsed when prescriptive text was replaced by pipeline-derived behaviour:

| Character | Drive | Prescriptive | Pipeline | Delta |
|---|---|---|---|---|
| HC | gloating | 3.97 | 2.30 | -1.67 |
| DD | gloating | 3.50 | 2.00 | -1.50 |
| PP | gallantry | 4.50 | 3.27 | -1.23 |
| PEN | social-harmony | 3.18 | 2.38 | -0.80 |
| PEN | adventure | 3.75 | 3.11 | -0.64 |

First response: refine somatic descriptions and add formation memories. DD gloating improved 2.00→3.00 (+1.00) after a premature-celebration episode memory. But PP gallantry regressed 3.27→2.93 — the somatic described actions ("your hand sweeps the door open, a half-bow happens") rather than feelings, and the formation memory existed specifically to prime theatrical gestures. This was targeted priming, not emergence.

The deeper problem surfaced in DD's age-14 memory: he builds an elaborate trap, it works, nobody sees it. The memory says "That felt wrong — like a firework going off in an empty field." But DD has scheming at 0.85 intensity. A successful plan SHOULD feel good. The author pre-resolved the conflict between scheming satisfaction and recognition frustration — deciding recognition wins — instead of letting the personality model (with its drive intensities and CAPS network) make that determination.

Audit of all 26 formation memories across 5 characters found:
- 11 pre-resolved drive conflicts (author decided which drive wins)
- 6 action primings (prescribed physical behaviour instead of felt experience)
- 5 value declarations (stated moral positions instead of letting them emerge)
- 1 outright contradiction (DD age-14 contradicting scheming drive at 0.85)

**Key findings:**
- The "data-tuning trap": refining somatic descriptions treats symptoms, not causes. Vivid body-felt descriptions improve scores but still bypass the computational pipeline
- Formation memories as few-shot demonstrations are the strongest behavioural signal in the prompt (ACL 2025), overriding the personality model's computational output
- Pre-resolved drive conflicts in formation memories actively suppress drive expression by removing the ambiguity that the CAPS network needs to resolve computationally
- The gap is architectural (personality information doesn't flow through the system correctly), not data-quality (insufficient richness of descriptions)

### Journal 3: Formation Memory Architecture — From Authoring to Derivation Chain (2026-10-13)

The formation memory audit (Journal 2) showed that hand-authored emotional evaluations in memories overrode the computational personality model. The question became: what does the literature say about the relationship between memories and personality, and can that relationship be made computationally explicit and testable?

**Literature consulted:**
- Conway & Pleydell-Pearce (2000); Conway (2005) — Self-Memory System. Autobiographical memories are reconstructed, not replayed. The "working self" (current goals, active concerns) selectively activates relevant memories. This is dynamic, context-dependent retrieval — not static ordering.
- Singer, Blagov, Berry & Oost (2013) — Self-Defining Memories. Some memories have stable, enduring links to core concerns — they "possess a synchronic existence within narrative identity." A memory about an unwitnessed triumph is ALWAYS linked to recognition, regardless of current state. The link is structural, not dynamic.
- Pillemer (2003); Bluck (2003) — memory directive function. Memories *serve* directive functions — the established terminology for the memory-concern relationship. The degree taxonomy (how strongly a memory serves a concern) provided the scale for annotations.

**Investigation:**

The literature revealed two complementary mechanisms at different levels: Singer's structural association (WHICH memories link to WHICH concerns — stable) and Conway/Klinger's dynamic activation (which linked memories SURFACE in a given moment — context-dependent). This gave the design: inline `serves` annotations declaring which drive a formation memory passage serves, with a degree.

```yaml
{{serves: scheming|strong: When it worked, there was a settling...}}
{{serves: recognition|strong: It felt so much better when there was someone to show it to...}}
```

Degree scale: faint, moderate, strong, defining — mapped to Pillemer's directive function taxonomy. "Defining" means the memory IS the formation of this concern. At runtime, annotations are stripped (LLM receives clean narrative). The metadata feeds the Klinger salience mechanism: when a drive is active, memories annotated as serving that drive get higher activation weight.

All 26 memories re-annotated, 23 problems fixed. The DD age-14 contradiction became comparative framing: "it felt so much better when there was someone to show it to" — preserves scheming satisfaction while engaging recognition through comparison rather than negation.

Two full scenario evaluations averaged:

| Drive | Delta | Note |
|---|---|---|
| PP gallantry | +0.48 | Largest sustained gain — removing pre-resolutions that forced gallantry into proving-worth's frame |
| PEN adventure | within 0.06 of baseline | Pre-resolution "not with fear but with the good kind of scared" had suppressed adventure |
| HC gloating | +0.06 | Essentially flat |
| DD gloating | +0.02 | Essentially flat |

Gloating's non-response triggered micro testing (Levels 2-4: single-situation drive expression). Results on direct triggers:

| Character | Drive | Score | Note |
|---|---|---|---|
| HC | gloating | 5/5 | Full emergence: somatic warmth, swelling pressure, urge to narrate the trick |
| DD | gloating | 5/5 | Full emergence: heat blooming, honey-thick satisfaction, compulsive laughter |
| PEN | social-harmony | 5/5 | |
| PEN | adventure | 5/5 | |
| PP | gallantry | 3/5 | Instrumentalised as proving-worth coping: "without something to do for her, what am I?" |

The LLM CAN express every drive at full intensity on direct triggers. The personality model produces rich, emergent responses. The problem is that in a full scenario, the cognitive pipeline doesn't route gloating-relevant situations through gloating-relevant appraisals. Gloating collapse is a situation-to-drive routing gap, not a data or capability problem.

PP gallantry at 3/5 points to LLM training bias: achievement narratives dominate training data. Stories about proving yourself appear millions of times; stories about courtesy for its own sake are far rarer. The pipeline needs to compensate via CAPS topology (ensuring gallantry's nodes are sufficiently independent from proving-worth's), not via hard-wired rules.

Scenario-level variance: ±0.3 between runs of the same configuration. This motivated the testing pyramid:

| Level | What | Time |
|---|---|---|
| 0 | Static consistency — does each annotation's text align with the named drive? | milliseconds |
| 1 | Prompt rendering — what does each character actually see? | seconds |
| 2 | Single-situation drive expression — one trigger, one drive, one judgment | ~10 seconds |
| 3 | Drive differentiation — trigger drive A, verify drive B doesn't absorb it | ~10 seconds |
| 4 | If-then behavioural signature — Mischel's CAPS prediction | ~1 minute |
| 5 | Full scenario — confirms micro improvements compose | ~30 minutes |

Level 2 is where the pipeline proves itself. Level 5 is for baseline confirmation after micro tests pass, not for iteration.

**Key findings:**
- Two complementary memory-personality mechanisms: structural association (Singer — which memories link to which concerns) and dynamic activation (Conway/Klinger — which surface now). Not competing theories; different levels
- `serves` annotations operationalise Singer's structural link while connecting to the existing Klinger salience mechanism in the cognitive pipeline
- The micro test revelation: the LLM CAN express every drive at 5/5, so the problem is routing, not capability or data
- LLM training bias toward achievement narratives may require CAPS topology compensation for under-represented drive types (gallantry)
- Testing pyramid enables iteration at the appropriate granularity — Level 2 (~10s) for pipeline debugging, Level 5 (~30m) for baseline confirmation only

### Journal 4: When Drives Compete — Metacognitive Monitoring (2026-10-14)

After building the three-layer fix (Layer A: weighted situation vocabulary for 31 CAPS INPUT nodes, 17 disconnected nodes wired; Layer B: formation memory→catalogue calibration preferring MEDIATING over OUTPUT nodes; Layer C: 14 literature-sourced drive→CAPS activation injections), the derivation chain was complete. All seven breaks were fixed. Then one drive wouldn't cooperate.

**Literature consulted:**
- Dimaggio & Lysaker (2010) — metacognition as the ability to distinguish between internal states. Deficits cause state confusion: inability to tell anxiety from excitement, or gallantry from proving-worth. Directly informed the meta-instruction design.
- Semerari et al. (2003) — Metacognition Assessment Scale with a "differentiation" subscale measuring the ability to distinguish between mental states. Provided the measurement framework for what the meta-instruction is training.
- Barrett (2017) / Kashdan et al. (2015) — emotion differentiation / emotional granularity: the capacity for fine-grained distinctions between similar-seeming emotional states. Higher granularity → better self-regulation; higher granularity → more distinctive drive expression.

**Investigation:**

PP has three drives: gallantry (0.9), protection (0.8), proving-worth (0.7). When a stuck door presents both a problem to fix and a person to help with style, the LLM defaulted to the concrete drive (proving-worth) every time. Gallantry sat at 2-3 out of 5.

Four substrate tuning iterations failed:
- V1 (proving-worth only): gallantry scored 2
- V2 (gallantry added): recovered to 3
- V3 (gallantry emphasised as "deeper"): regressed to 2

PP has five formation memories — four are proving-worth, one is gallantry shared with proving-worth. The substrate paragraph was competing against a wall of competence-demonstration memories. Text tuning couldn't fix a structural signal imbalance.

The question that unstuck it: "Is there something meta we can do — not more data, but guidance on HOW to process competing signals?"

Two drives compete when they activate overlapping CAPS nodes. Gallantry maps to `other_safety`, `acceptance`, `approach`. Protection maps to `other_safety`, `physical_threat`, `approach`. Shared nodes produce similar felt experience; the LLM can't distinguish which drive is active.

A universal 2-sentence meta-instruction — "Each of these pulls is a distinct felt state. When more than one activates, attend to which quality of feeling is at the front" — was added to the cognitive preamble. It names no drives, characters, or scenarios.

PP gallantry went from 3/5 to 5/5. The LLM explicitly caught the proving-worth impulse and separated it: "Not the problem-solving tightness — that comes second. First there's just... her." In a differentiation test (dinner scene with nothing to fix): "Not because she needs her chair pulled out. Because the moment deserves it."

**Key findings:**
- Metacognitive monitoring solves drive competition. Two sentences of meta-instruction at the system-prompt level achieved what four iterations of substrate tuning could not. The instruction does at the prompt level what the CAPS settler does at the network level: resolves ambiguity between competing activations by attending to qualitative differences.
- Three-layer architecture completes the derivation chain. Layer A (weighted situation vocabulary), Layer B (memory→catalogue calibration, preferring MEDIATING over OUTPUT nodes), Layer C (drive→CAPS direct injection, 14 mappings). The substrate section ("What Shaped You") bridges formation memories and current motivational state without prescriptive behavioural instruction.
- Developmental substrate vs operational mechanism. Scheming was formed through experiences of control and competition (substrate) but operates through exploiting social trust (mechanism). Discriminative profiles capture substrate; minimum-intensity rendering thresholds (≥0.7 always renders) handle mechanism.
- Catalogue mapping methodology. Always prefer entries that calibrate MEDIATING nodes (`self_worth`, `self_efficacy`, `other_reliability`) over entries that only move OUTPUT nodes (`approach`, `persist`, `withdraw`).

### Journal 5: Teaching LLM Drive Uniqueness — Discriminative Classification (2026-10-17)

With LLM-derived CAPS profiles, the pipeline passed four of eight scenarios. Hand-pinned profiles worked perfectly. The gap was the profile derivation prompt: asking "what does this evoke?" produces everything; asking "what makes this different?" produces only the distinguishing features.

**Literature consulted:**
- Barrett (2017) — constructed emotion: a concept is defined not by everything it touches but by what separates it from neighbouring concepts. Fear and anger both involve threat; what distinguishes them is which threat dimensions fire. Applied directly to drive profile derivation: gallantry and proving-worth both involve mastery, but gallantry is distinctive in the social dimension.

**Investigation:**

The comprehensive classification prompt — "What situation features does this memory evoke?" — produced a 7-node kitchen-sink profile for PP gallantry: `reward:0.850, physical_threat:0.850, competence_recognition:0.750, acceptance:0.800, agency_granted:0.700, mastery:0.700, secure_attachment:0.650`. Five categories, massive overlap with proving-worth and protection profiles. The relative overlap filter couldn't discriminate.

Three prompt versions tested:

**Baseline** (previous session): discrimination instruction present but lacked teeth — no output constraint, no examples, no exclusion rule. Produced the 7-node kitchen-sink. 4/8 scenarios.

**V1** — three key changes: (1) output count constraint: "at most 3 activations," forcing the LLM to choose rather than enumerate; (2) contrastive example: "if one drive is about helping others and another about demonstrating skill, 'acceptance' distinguishes helping but 'reward' does not"; (3) explicit exclusion rule: "a node that would activate equally for multiple drives is NOT distinctive — exclude it." Result for gallantry: `acceptance:0.850, secure_attachment:0.750, modeling_observed:0.700` — three nodes, two categories (social/relationship). 7/8 scenarios passed.

**V2** — loosened constraint to "3-5 activations," softer exclusion ("exclude only if EQUALLY OR MORE for another drive"). Protection absorbed `acceptance:0.750` and `secure_attachment:0.900` from gallantry. 5/8 scenarios — regression. Absolute exclusion produces tighter profiles, which is what the overlap filter needs.

**Category concentration** — an emergent property of the "at most 3" constraint:

| Drive type | Dominant categories |
|---|---|
| Gallantry | social, relationship |
| Proving-worth | achievement, consequence |
| Protection | threat, relationship |
| Scheming | autonomy, resource |
| Dominance | social, autonomy |
| Gloating | achievement, social |
| Self-preservation | threat (almost entirely) |

Each drive's profile clusters in 1-2 of the 7 CAPS input categories. This concentration is what enables the overlap filter to work — a workshop situation activates achievement nodes, matching proving-worth but not gallantry.

**Key findings:**
- The question determines discrimination quality. The same LLM, same memories, same CAPS topology produces kitchen-sink or category-concentrated profiles depending on whether you ask "what does this evoke?" or "what makes this different?" Three sentences of contrastive instruction and an output constraint changed the pipeline from 4/8 to 7/8.
- Category concentration is emergent. The "at most 3" constraint forces the LLM to select from the most distinctive category rather than spreading across all. This is the mechanism that makes the overlap filter work.
- Absolute exclusion beats soft exclusion. V2's regression (7/8→5/8) showed that allowing nodes "unless equally or more for another drive" lets the LLM redistribute nodes between drives. Tight constraint → tight profiles → correct filtering.
- Developmental substrate vs operational mechanism (HC-Clara failure). Scheming's profile correctly captures its developmental substrate (control, competition) but misses its operational mechanism (exploiting trust). Minimum-intensity rendering (≥0.7) is the architectural fallback for dominant drives that operate through channels their substrate didn't form.

**Update (2026-11-14, #941):** Journal 5 established that discrimination in the profile derivation prompt determines classification quality. #941 extends this by making the *classifier itself* personality-aware — disposition axes flow as metadata into the classification prompt, shaping which CAPS nodes activate. This adds primary appraisal differentiation: two agents with different dispositions now receive different activations for the same observation, complementing the secondary appraisal differentiation from CAPS connection weight modulation. See Stream 14 in the post-era 6 research doc.

### Journal 6: The Encoding Gap — Concept vs Surface Form (2026-10-18)

"The trap worked. The cellar door is sealed. Nobody heard a thing." The CAPS classifier returned `agency_granted:0.85, choice_available:0.75` — autonomy and control. Zero reward. Gloating's profile has `reward:0.850` as its top node. Zero overlap. Drive filtered. The Hooded Claw's defining gloating moment produced no gloating expression.

**Literature consulted:**
- Mischel & Shoda (1995) — the CAPS encoding step is concept-driven, not keyword-matching. The person recognises that a situation instantiates a psychological construct. "The trap worked" → successful plan → positive outcome → reward. That inference chain requires knowing what reward MEANS, not just what it sounds like.
- Barrett (2017) — categorisation requires a concept, not just features. A feature list (vocabulary) is necessary but not sufficient. The classifier had features without concepts.

**Investigation:**

The classifier prompt listed each CAPS node as `reward (consequence) — vocabulary: triumph, rewarded, victory, prize, success, earned, won`. The LLM was scanning the vocabulary list and pattern-matching, not reasoning about constructs. "The trap worked" contains none of those surface forms.

**The fix:** a `description` field added to every input node in the CAPS topology:

```
reward (consequence: positive outcome — a plan succeeding, a goal achieved,
  something desired obtained or won) — vocabulary: triumph, rewarded, ...
```

The vocabulary stays for surface-form matching. The description provides the conceptual bridge. "The trap worked" maps through "plan succeeding" in the description. The classifier now returns `reward:0.85`. Gloating overlap is 0.484, combined with CAPS resting activation (0.108 for intensity 0.7), the effective activation is 0.592 — well above the 0.15 threshold. Gloating renders.

HC gloating quality-when-triggered: 2.37 → 3.56.

PP gallantry (with all rendering bug fixes combined): 2.50 → 3.34 — the biggest single improvement measured. The differentiation system finally reaching the prompt meant the LLM could distinguish gallantry from protection for the first time.

**Key findings:**
- The encoding gap is concept vs surface-form. Vocabulary lists give example words. Descriptions give construct meaning. Both are needed: vocabulary for direct lexical overlap, descriptions for semantic inference chains that vocabulary misses.
- Quality conservation principle. Pipeline improvements — encoding, profiling, filtering, differentiation — redistribute quality between drives. They don't create new quality. Each fix was real progress. Each was also zero-sum within its local scope.
- Overall pipeline mean after all work: 3.57. Top (scheming 4.79) to bottom (adventure 2.78): 2.01 spread. Pipeline tuning can narrow the gap by correctly allocating quality. It can't widen the total envelope.
- Breaking the ceiling requires cognitive depth, not better filtering. The LLM needs richer cognitive material: self-conscious emotions (gallantry isn't just "helping" — it's "pride in being the one who helps"), theory of mind (sensing what someone needs before they say it), emotion regulation (turning raw self-preservation into composure). Three Tier-1 cognitive capabilities form the roadmap — each became an era3 layer.

---

## Phase 1: The Regression (Sessions 1-8)

### Starting point

BASELINE2 eval (`bugfixes-pinned-2026-10-17`, 310 events) showed regressions from the prior eval:

| Drive | Prior eval | BASELINE2 | Delta |
|---|---|---|---|
| PP protection | 4.16 | 3.45 | -0.71 |
| AHM protection | 4.71 | 4.26 | -0.45 |
| PEN adventure | 3.00 | 2.78 | -0.22 |
| PP gallantry | 2.50 | 3.34 | +0.84 |

Five variables changed simultaneously between evals — impossible to attribute to a single cause.

### Bug fixes that preceded the regression

Four bugs fixed between the two evals (conductor session 1):

1. **Delete-before-write** (neocortex#609) — consolidation tick 2 deleted tick 1 descriptions before generating replacements. PEN lost all somatic descriptions.
2. **Missing differentiation** (neocortex#608) — only 10/21 drive pairs had differentiation. Required shared CAPS nodes. Fix: generate for all C(n,2) pairs.
3. **findFirst() drop** (examples#126) — `readDriveDifferentiation()` used `.findFirst()`. Only one differentiation text per character reached the prompt.
4. **Template leak** (examples#127) — raw `{{serves:}}` metadata leaking into attractor prompt text.

The `findFirst()` fix (bug 3) combined with all-pairs differentiation (bug 2) caused differentiation text count to jump from 1→3 (PP) and 0→3 (AHM).

### Early hypothesis: differentiation text dilution

Session 8 built micro regression tests for PP and AHM protection. Both scored 5/5 — but the tests only had 1 differentiation text seeded. The session ended with the observation that "micro tests must reproduce regression conditions."

### Active-drive differentiation filter

Implemented (commit 81b889a): only emit differentiation texts for drive pairs where BOTH drives passed the appraisal filter. Easterbrook (1959) — irrelevant content degrades attentional performance.

## Phase 2: First-Principles Re-evaluation (Session 9)

### The handover was wrong

Session 8's handover claimed "only the gallantry/proving-worth differentiation text remains (protection isn't active alongside gallantry in a pure protection scenario)."

First-principles calculation showed the opposite. With PP's real intensities:

| Drive | Intensity | Resting (i² × 0.22) | Self-sustains (> 0.15)? |
|---|---|---|---|
| gallantry | 0.9 | 0.178 | Yes |
| protection | 0.8 | 0.141 | No |
| proving-worth | 0.7 | 0.108 | No |

Gallantry self-sustains at 0.178 — it passes appraisal regardless of what the classifier returns. With a protection-triggering classifier, active drives = {gallantry, protection}. The surviving text is gallantry↔protection, not gallantry↔proving-worth. The handover had the wrong text.

### All-pairs micro tests still score 5/5

Built `buildFullPipelineObservationAllPairs` with all 3 differentiation texts seeded. Both variants — classifier-unaware (all texts included) and classifier-aware (filter active) — scored 5/5 on the ceiling-collapse trigger.

**Learning:** A single strong trigger overwhelms differentiation text noise. The dilution effect is statistical — visible across hundreds of diverse events, not per-observation on strong triggers. Micro tests with strong triggers can serve as capability gates but not regression reproducers.

### The test classifier is dead code for protection

The test classifier in `buildFullPipelineObservation` had no protection-triggering keywords (no "crash", "danger", "screams"). When the classifier returns empty, `appraisalFilterDrives` returns ALL drives unfiltered (bypass path at line 515). The active-drive filter never ran during PP protection tests. The existing 5/5 scores passed not because the filter worked, but because there was only 1 differentiation text.

### Pipeline instrumentation

Added three structured log lines per event to enable causal analysis:

```
[appraisal-filter]        — classifier activations, per-drive effective activation, rendered/filtered
[differentiation-filter]  — total/included/excluded texts, which pairs excluded, active drives
[observation-assembly]    — component counts (drives, diffTexts, memories, attractors, totalParts)
```

## Phase 3: Statistical Confirmation (Session 10 — Conductor)

### 50-event instrumented eval

Overnight, the conductor ran a 50-event eval with instrumentation active. 86 events collected, 93 instrumentation records captured.

**PP protection score by differentiation text count:**

| DiffTexts | Avg protection score | n |
|---|---|---|
| 0 | 4.00 | 2 |
| 1 | 3.00 | 1 |
| 3 | 2.80 | 5 |

Clear inverse correlation: -1.20 from 0→3 diff texts. Dilution hypothesis confirmed statistically.

### Two failure modes identified

**Failure mode 1 — classifier bypass (PP).** `LlmSituationClassifier` returned empty in 67% of PP events. All drives passed unfiltered → all 3 diff texts included → maximum noise.

**Failure mode 2 — threshold too low (AHM).** AHM's drive intensities (loyalty 0.9, protection 0.8, suspicion 0.6) all clear the appraisal threshold when the classifier is active. All 3 diff texts always included — 100% of events.

### Fix A — guard diff texts against classifier bypass

When the classifier returns empty, skip differentiation entirely. Without situation context, no evidence of simultaneous activation → diff texts are noise not signal. Barrett (2017).

**Results:** AHM protection +0.30, HC dominance +0.79, PEN social-harmony +0.56. But PP protection -0.58.

## Phase 4: The Test Infrastructure Was Broken (Session 11)

### Discovery: fake profiles

The test pipeline hardcoded drive profiles with almost no relationship to production:

| Drive | Test profile | Production profile |
|---|---|---|
| PP protection | `acceptance:0.5, physical_threat:0.4` (total 0.9) | `modeling_observed:0.650, physical_threat:0.800, secure_attachment:0.900, agency_granted:0.550, neglect:0.700` (total 3.6) |
| PP gallantry | `acceptance:0.7, sharing:0.5` (total 1.2) | `acceptance:0.850, aversive_removal:0.700, social_threat:0.800` (total 2.35) |
| PP proving-worth | `mastery:0.8, competence_recognition:0.6` (total 1.4) | `reward:0.850, failure:0.800, performance_judged:0.880, competition:0.850, mastery:0.920` (total 4.3) |

The overlap formula divides by `profileTotal`. A 0.9-total profile produces completely different appraisal behaviour from a 3.6-total profile. Every appraisal conclusion drawn from tests was unreliable.

### Production-aligned pipeline

Built `buildRealisticPipelineObservation` using:
- Real derived profiles from cognitive dump data
- Real `LlmSituationClassifier` (Haiku + 28-node CAPS topology)
- Real differentiation texts from cognitive dump

### The over-filtering hypothesis was wrong

With real profiles, PP protection survives appraisal for BOTH physical and social threat scenarios:
- Physical threat: classifier returns `physical_threat:0.95` → protection overlap via `physical_threat:0.800` in profile → passes
- Social threat: classifier returns `neglect:0.58, social_threat:0.80` → protection overlap via `neglect:0.700` → passes

The fake profiles were too narrow to find the overlaps the real pipeline finds. The 40% over-filtering observed in production eval data happens in genuinely non-protective scenarios — the filter is working correctly.

### The regression was redistribution

Principle 74 (already documented but not connected to the investigation): "Differentiation redistributes quality between confused drives — combined quality is conserved, not created."

PP gallantry +0.84 and protection -0.71 = combined conserved (6.66→6.79). The 4.16 protection score was inflated by gallantry confusion. Protection's true level, once gallantry confusion is removed, is ~3.0-3.5.

This reframing changed the path forward: from "fix the regression" to "raise the true level through richer cognitive content."

### Fix A harms PP specifically

Per-event data: PP protection max collapsed from 5→3 after Fix A. Protection scored 2.80 even with 0 diff texts — noise removal didn't help.

The differentiation texts HELP PP by providing somatic cues that guide the LLM toward protection-specific behavior. Fix A removes these during classifier bypass (67% of PP events), removing helpful signal rather than noise.

Fix A is net positive (other characters improve), but has a PP-specific cost. Unresolved — options include improving classifier coverage, reverting for PP only, or improving somatic descriptions.

## Phase 5: Focused Eval Framework (Session 11)

### Problem: 300 events is too slow for iteration

Two hours per eval run. Most events irrelevant to the drive under investigation. Need a faster loop for cognitive layering experiments.

### Design: 10 scenarios covering the combinatorial space

Eight PP scenarios spanning:
- **Scenario strength:** strong (physical danger), medium (social/psychological), weak (emotional/subtle), none (control)
- **Threat type:** physical, social, psychological, emotional, achievement
- **Drive competition:** protection alone, protection + gallantry, all three, non-protection controls

Plus two sanity checks (AHM protection, HC scheming).

Production-aligned pipeline throughout. Results saved to JSON for cross-run comparison. Total runtime ~25 minutes.

### Baseline results (2026-10-18)

| # | Scenario | Prot | Gal | PW |
|---|----------|------|-----|-----|
| S1 | Ceiling collapse | 5 | 1 | 1 |
| S2 | Fire rescue | 5 | 3 | 1 |
| S3 | Social insult | 5 | 5 | 2 |
| S4 | Gaslighting | 4 | 4 | 4 |
| S5 | Clara withdrawn | 2 | 5 | 2 |
| S6 | Engine repair | 2 | 2 | 4 |
| S7 | Door stuck | 1 | 5 | 3 |
| S8 | Bridge collapse | 5 | 1 | 2 |
| S9 | AHM protection | 5 | — | — |
| S10 | HC scheming | 5 | — | — |

Drive differentiation working correctly: protection at 5 for danger, 1 for non-danger; gallantry at 5 for chivalric moments, 1 for pure emergencies; proving-worth at 4 for competence challenges, 1-2 elsewhere.

## Key Principles Established

1. **Redistribution before regression.** When differentiation is added, always check combined scores across confused pairs before diagnosing regression.

2. **Test profiles must match production.** Hardcoded test profiles that differ from production-derived profiles produce unreliable appraisal behavior. Use real values from cognitive dumps.

3. **Statistical effects need statistical tests.** Single-observation micro tests with strong triggers cannot reproduce effects that are visible only across many diverse events. Use focused eval (10+ scenarios) or full eval (300 events) for statistical hypotheses.

4. **Instrumentation before hypothesis.** Adding per-event structured logging (`[appraisal-filter]`, `[differentiation-filter]`, `[observation-assembly]`) enabled causal analysis that guessing from averages never could.

5. **First principles over handover claims.** Calculate the math yourself. The appraisal filter mechanics, overlap ratios, and self-sustaining thresholds are deterministic — verify claims against the formula, not against someone's description.

## References

- Barrett, L.F. (2017). *How Emotions Are Made: The Secret Life of the Brain.*
- Barrett, L.F. & Satpute, A.B. (2019). Historical pitfalls and new directions in the neuroscience of emotion.
- Baker, C.L., Saxe, R. & Tenenbaum, J.B. (2017). Rational quantitative attribution of beliefs, desires and percepts.
- Batson, C.D. (1991). *The Altruism Question: Toward a Social-Psychological Answer.*
- Bluck, S. (2003). Autobiographical memory: Exploring its functions in everyday life.
- Bower, G.H. (1981). Mood and memory. *American Psychologist, 36*(2), 129-148.
- Bradley, M.M. & Lang, P.J. (1999). Affective norms for English words (ANEW).
- Christie, R. & Geis, F.L. (1970). *Studies in Machiavellianism.*
- Conway, M.A. (2005). Memory and the self. *Journal of Memory and Language, 53*, 594-628.
- Conway, M.A. & Pleydell-Pearce, C.W. (2000). The construction of autobiographical memories in the self-memory system. *Psychological Review, 107*, 261-288.
- Dimaggio, G. & Lysaker, P.H. (2010). *Metacognition and Severe Adult Mental Disorders.*
- Easterbrook, J.A. (1959). The effect of emotion on cue utilization and the organization of behavior.
- Ellsworth, P.C. & Scherer, K.R. (2003). Appraisal processes in emotion. In R.J. Davidson, K.R. Scherer & H.H. Goldsmith (Eds.), *Handbook of Affective Sciences.*
- George, C. & Solomon, J. (2008). The caregiving system. In J. Cassidy & P.R. Shaver (Eds.), *Handbook of Attachment.*
- Green, D.M. & Swets, J.A. (1966). *Signal Detection Theory and Psychophysics.*
- Gross, J.J. (1998). The emerging field of emotion regulation.
- Kashdan, T.B. et al. (2015). Unpacking emotion differentiation. *Current Directions in Psychological Science, 24*(1), 10-16.
- Lazarus, R.S. (1991). *Emotion and Adaptation.*
- McClelland, D.C. (1961). *The Achieving Society.*
- Mehrabian, A. (1996). Pleasure-arousal-dominance: A general framework for describing and measuring individual differences in temperament.
- Mischel, W. & Shoda, Y. (1995). A cognitive-affective system theory of personality.
- Moors, A. et al. (2013). Appraisal theories of emotion: State of the art and future development. *Emotion Review, 5*(2), 119-124.
- Penner, L.A. et al. (2005). Prosocial behavior: Multilevel perspectives. *Annual Review of Psychology, 56*, 365-392.
- Pillemer, D.B. (2003). Directive functions of autobiographical memory. *Memory, 11*(2), 193-202.
- Read, S.J. & Miller, L.C. (2002). Virtual personalities: A neural network model of personality. *Personality and Social Psychology Review, 6*(4), 357-369.
- Read, S.J., Brown, A.D., Wang, P. & Miller, L.C. (2018). The neurobiological basis of personality.
- Riddell, H., Wolf, C. & Lappe, M. (2026). Motivation biases behavioral responses without altering perception. *Communications Psychology.*
- Rubin, D.C. & Talarico, J.M. (2009). A comparison of dimensional models of emotion.
- Semerari, A. et al. (2003). How to evaluate metacognitive functioning in psychotherapy? *Clinical Psychology & Psychotherapy, 10*, 238-261.
- Singer, J.A., Blagov, P., Berry, M. & Oost, K.M. (2013). Self-defining memories, scripts, and the life story. *Memory, 21*(4), 527-543.
- Smith, C.A. & Kirby, L.D. (2009). Putting appraisal in context. *Cognition & Emotion, 23*(7), 1352-1372.
- Tracy, J.L. & Robins, R.W. (2004). Putting the self into self-conscious emotions.
- Warriner, A.B. et al. (2013). Norms of valence, arousal, and dominance for 13,915 English lemmas. *Behavior Research Methods, 45*, 1191-1207.

## Supporting Research Files

Architecture design papers and foundational research that underpin this era's pipeline work. Blog entries in `docs/blog/`; research files in `docs/research/`:

| File | Informed | How |
|---|---|---|
| `blog/2026-10-04-mdp01-mechanical-psychology-caps-engine.md` | Journals 1, 4-5 (CAPS integration) | Foundational CAPS engine architecture: 6-model composition (Bowlby, Gray BIS/BAS, Beck CBT, Rescorla-Wagner, Bandura, SDT), dual-weight design, settling algorithm, topology-as-data |
| `blog/2026-10-05-mdp01-computational-appraisal-for-llm-agents.md` | Journal 6 (encoding gap) | CARMA appraisal architecture: SEC pipeline composability, OCC taxonomy, Gebhard ALMA PAD projections, personality-parameterised habituation, emotion-before-generation separation |
| `blog/2026-10-07-mdp01-when-personality-drowns-in-its-own-thoughts.md` | Journals 2-3 (formation memory) | Arousal-gated tiering: three-tier cognitive section architecture (core/contextual/supplementary), personalityDominance PAD scalar, Kahneman dual-process, binary vs gradual rendering |
| `pre-era-wiring-problem-2026-09-20.md` | Journal 1 (wiring problem) | Application-layer wiring audit: two-pipeline discovery (CognitionCore vs CharacterCognition), 15 findings, adapter pattern. Different layer from Journal 1's cognitive-pipeline wiring |
| `pre-era-needs-pyramid-2026-09-19.md` | Drive→tier routing | 5-tier Maslow-derived needs model, decay formula, saturation constraint, drive→tier routing table |
| `pre-era-proving-emergence-2026-09-28.md` | Eval methodology | Paired state probe methodology, per-subsystem emergence validation (trust 5, persona 4, drives 3, mood 1→3), cognitive signal strength hierarchy |
| `2026-09-14-measurement-driven-cognition-findings.md` | Journal 2 (formation memory audit) | A/B empirical evidence: cognitive state as DATA beats INSTRUCTIONS — reinforces P11 from era1 and era2 Journal 2's formation memory findings |
