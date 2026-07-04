# CBR Types — What They Are and When to Use Them

Case-Based Reasoning retrieves similar past cases to inform decisions on new ones.
CaseHub supports three CBR paradigms, each suited to different reasoning needs.
All three share the same SPI (`CbrCaseMemoryStore`) and the same four-step cycle
(Retain → Retrieve → Reuse → Revise) — they differ in what knowledge they capture
and what signal they provide.

## Textual CBR

**Java type:** `TextualCbrCase` · **Discriminator:** `"textual"`

Pure natural-language similarity. No structured features — the entire reasoning
surface is the text of `problem()` and `solution()`. Retrieval is semantic:
an embedding model encodes the problem text, and similarity is cosine distance
between vectors.

**Input:**

| Field | Type | Purpose |
|-------|------|---------|
| `problem` | String | NL description of the current situation |
| `solution` | String | NL description of what was done |
| `outcome` | String (nullable) | Result label — APPROVED, SAR_FILED, etc. |
| `confidence` | Double (nullable) | Confidence in the outcome, [0, 1] |

**Output from retrieval:** Ranked list of past cases whose `problem()` text is
semantically similar. Score is cosine similarity on embedding vectors.

**What it does:** "Find cases that *sound like* this one." No structural
filtering — the embedding model decides what's similar. Useful when the domain
doesn't have clean categorical features, or when the text itself carries all
the discriminating signal.

**When to use:** Early-stage CBR adoption where structured features haven't been
defined yet, or domains where the problem description is the primary knowledge
carrier (e.g. free-text incident reports).

**Limitation:** No explainability — you can't trace *why* two cases were
considered similar beyond "the embeddings were close."

---

## Feature-Vector CBR

**Java type:** `FeatureVectorCbrCase` · **Discriminator:** `"feature-vector"`

Structured similarity over declared feature schemas. Cases have typed feature
maps — categorical (exact match), numeric (range), text (keyword). Retrieval
filters by feature match first, then optionally ranks by embedding similarity
on `problem()` within the filtered set.

**Input:**

| Field | Type | Purpose |
|-------|------|---------|
| `problem` | String | NL description of the current situation |
| `solution` | String | NL description of what was done |
| `outcome` | String (nullable) | Result label |
| `confidence` | Double (nullable) | Confidence in the outcome, [0, 1] |
| `features` | Map\<String, Object\> | Structured feature map conforming to a registered schema |

Feature types:

| Type | Declared as | Query behaviour |
|------|------------|-----------------|
| Categorical | `FeatureField.categorical("language")` | Exact match — `"JAVA"` matches `"JAVA"`, nothing else |
| Numeric | `FeatureField.numeric("severity", 1, 5)` | Range filter — exact value or `NumericRange.within(3, 0.5)` |
| Text | `FeatureField.text("description")` | Keyword match |

**Output from retrieval:** Ranked list of past cases matching the
categorical/numeric/text filters. When an `EmbeddingModel` is available and
`CbrQuery.problem` is set, results are ranked by cosine similarity on the
problem embedding within the filtered set. Without embeddings, all matching
cases receive a synthetic score of 1.0.

**What it does:** "Find cases that are *structurally comparable* to this one,
then rank by narrative similarity within that set." The schema guarantees
like-for-like comparison — a Java refactor is compared to Java refactors, not
TypeScript bugfixes. The embedding similarity then discriminates within that
structural cohort.

**When to use:** Most applications. Any domain with well-defined categorical
structure — AML investigations (transaction pattern, risk tier, jurisdiction),
clinical adverse events (event type, trial arm, severity grade), PR reviews
(language, change type), IoT situations (device class, room type), contractor
coordination (job type, urgency, property area).

**Strength:** Explainability. Every filter is traceable — you can say *why*
these cases were considered similar: "same transaction pattern, same risk tier,
same jurisdiction."

---

## Plan-Based CBR

**Java type:** `PlanCbrCase` · **Discriminator:** `"plan"`

Everything Feature-Vector does, plus a full execution trace — the ordered
sequence of steps that constituted the plan. This is CHEF-style case-based
planning: retrieve a similar past plan, then adapt it for the current situation.

**Input:**

| Field | Type | Purpose |
|-------|------|---------|
| `problem` | String | NL description of the current situation |
| `solution` | String | NL description of what was done |
| `outcome` | String (nullable) | Result label |
| `confidence` | Double (nullable) | Confidence in the outcome, [0, 1] |
| `features` | Map\<String, Object\> | Structured feature map (same as Feature-Vector) |
| `planTrace` | List\<PlanTrace\> | Ordered execution trace |

Each `PlanTrace` step:

| Field | Type | Purpose |
|-------|------|---------|
| `bindingName` | String | Which binding was activated (e.g. "scout") |
| `capabilityName` | String | Which capability it targeted (e.g. "reconnaissance") |
| `workerName` | String (nullable) | Which worker was selected (e.g. "overlord-scout") |
| `stepOutcome` | String | SUCCESS, FAILURE, SKIPPED, TIMEOUT |
| `priority` | int (>= 0) | Activation priority |
| `parameters` | Map\<String, Object\> | Domain-specific step parameters |

**Output from retrieval:** Ranked list of past cases with full plan traces.
The consumer analyses the traces: which bindings appeared in winning plans,
which workers succeeded at each step, which step sequences led to good outcomes.

**What it does:** "Find cases where the *situation was similar* (via features),
then show me *exactly what was executed* and *what happened at each step*."
Goes beyond "what's the answer?" to "what's the strategy?" — the plan trace
is the reusable knowledge, not just the final outcome.

**When to use:** Engine and planning domains where the *sequence of actions*
matters, not just the final assignment. QuarkMind (StarCraft II) is the
reference implementation — past game plans show which binding sequences led
to wins against specific opponent strategies.

**Strength:** Richest signal. Tells the routing strategy not just *who* to
route to, but *what sequence of actions* to take. "4 out of 5 similar winning
plans started with scout → bunker-up → counter-push. The 1 loss skipped
scouting."

---

## Choosing a Type

| Question | Answer | Type |
|----------|--------|------|
| Do you have structured features? | No — just text | Textual |
| Do you have structured features? | Yes | Feature-Vector |
| Do you also need to capture *how* the case was executed (step by step)? | Yes | Plan-Based |

Most applications start with Feature-Vector. Add plan traces when the execution
strategy is part of the knowledge you want to reuse.

## How They Layer for Routing

The three types provide different levels of routing signal:

| Type | Routing signal | Explainability |
|------|---------------|----------------|
| Textual | "similar-sounding cases went to worker X" | Low |
| Feature-Vector | "structurally comparable cases with these features went to worker X with Y% success" | High |
| Plan-Based | "similar situations used this step sequence with these workers, and 80% won" | Highest |

## Roadmap

Future neocortex phases extend these types without replacing them:

| Phase | What it adds | Enhances |
|-------|-------------|----------|
| Weighted similarity (#82) | Features contribute proportionally, not as binary filters | Feature-Vector, Plan-Based |
| Semantic retrieval (#83) | Bridge CBR + RAG — finds narratively similar cases across category boundaries | All types |
| Plan adaptation (#85) | Transformational adaptation — modify retrieved plans, not just apply them | Plan-Based |
| Temporal trajectory (#88) | Time-series dimensions — "events like this at day 21 progressed to grade 4 by day 45" | Feature-Vector |
| Hierarchical scoping (#93) | Multi-scope retrieval — trial-level patterns from site-level data | All types |

## Examples

See [example-cbr](../../examples/example-cbr/) for runnable demos of all three types
across six domains: AML investigation, clinical adverse events, PR review, contractor
coordination, IoT situation handling, and StarCraft II battle planning.

## Further Reading

- [CBR Integration Guide](README.md) — SPI usage, schemas, backends, Maven dependencies
- [AML guide](guide-aml.md) · [Clinical guide](guide-clinical.md) · [DevTown guide](guide-devtown.md) · [Engine guide](guide-engine.md)
