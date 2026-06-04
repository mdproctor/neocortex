# AI Fusion Platform — Hybrid Fact Space and Multi-Paradigm Worker Architecture

**Date:** 2026-06-03  
**Status:** Strategic direction — pre-implementation brief  
**Context:** Derived from platform gap analysis against PLATFORM.md and all foundation deep-dives

---

## What This Is Solving

The CaseHub platform orchestrates multi-agent AI workflows. Its current architecture excels at **routing and sequencing** — pick an agent, run a step, move to the next. Even sophisticated patterns (the debate loop in drafthouse, commitment-based messaging in qhorus, trust-weighted routing in engine-ledger) are fundamentally sequential message passing between agents of the same kind: LLM agents.

This is not enough for a regulated, production AI Fusion platform.

In practice — AML investigations, clinical trial coordination, enterprise compliance, software review workflows — you need to combine fundamentally different AI paradigms in the same workflow:

- **Rule-based reasoning** that encodes regulatory typologies, compliance mandates, and domain expert knowledge with full explainability
- **Probabilistic reasoning** (LLM) that handles ambiguity, natural language, context, and judgment that cannot be codified as rules
- **Deterministic workflow orchestration** that transforms data, calls external systems, and executes multi-step pipelines without AI involvement
- **Human judgment** as a governance layer with documented accountability

The platform already supports all four as distinct execution paradigms. What it does not have is a first-class way for them to reason *together* — to share typed knowledge claims across paradigms and synthesise them into coherent decisions.

---

## The AI Fusion Thesis

**AI Fusion is not model selection.** Choosing Claude vs GPT-4 vs a cheaper model for cost efficiency is an optimisation and governance concern — it does not unlock new capability.

**AI Fusion is combining AI systems that reason differently.** The emergent capability comes from orchestrating paradigms that complement each other's weaknesses:

| Paradigm | Strength | Weakness |
|----------|----------|----------|
| LLM (Claude, OpenClaw agents) | Context, ambiguity, natural language, judgment | Probabilistic, unexplainable, inconsistent |
| Rule engine (Drools) | Deterministic, explainable, regulatory encoding | Brittle, cannot generalise beyond rules |
| Workflow engine (QuarkusFlow) | Data transformation, integration, deterministic orchestration | No reasoning capability |
| Human (casehub-work) | Judgment, authority, accountability | Bottleneck, expensive, slow |

Each covers the weaknesses of the others. A clinical trial workflow that runs regulatory rules (Drools), gathers LLM reasoning about edge cases, executes structured data steps (QuarkusFlow), and gates on human medical judgment (casehub-work) produces decisions that none of the four could reach alone.

This is AI Fusion: **qualitatively different decisions emerging from the combination**, not just parallel execution.

---

## The Worker Ecosystem — Where the Parts Land

The platform's `WorkerProvisioner` SPI (defined in `casehub-engine-api`) is already the right abstraction. Each AI paradigm maps to a Worker type:

| Worker | Repo | AI Paradigm | Native Representation |
|--------|------|-------------|----------------------|
| Claude CLI sessions | `claudony` | LLM (generative) | Text |
| OpenClaw agents | `casehub-openclaw` | LLM (agentic, MCP-tool-using) | Text + MCP tool calls |
| Drools | TBD (new integration) | Symbolic AI — rule-based, deterministic | Typed facts (Java objects) |
| QuarkusFlow workflows | `flow` (mdproctor/flow) | Deterministic workflow — HTTP, functions, transforms | Structured data (JSON/Avro) |
| Human | `casehub-work` | Human judgment | WorkItem outcome + resolution |

The WorkerProvisioner SPI is the boundary. Each worker type:
1. Receives a case step dispatch (prompt, context, commitment lifecycle)
2. Executes in its native paradigm
3. Returns a result that closes the commitment

This is already proven by claudony and casehub-openclaw. QuarkusFlow and Drools extend the same pattern.

**Quarkus-flow as Worker:** QuarkusFlow (Serverless Workflow 1.0 engine) implements `WorkerProvisioner`. A case step dispatches to it; QuarkusFlow executes arbitrary non-LLM logic — HTTP calls, data transforms, conditional branching, parallel function execution — and returns structured results. This addresses all deterministic integration and data pipeline needs without touching an LLM.

**Drools as Worker:** Drools implements `WorkerProvisioner`. A case step dispatches a fact set to it; Drools evaluates its rule base and returns typed conclusions. The case step gains a deterministic, fully explainable, regulation-encodable AI participant. No LLM involved; conclusions are traceable to specific rules.

---

## The Missing Primitive — Typed Fact Space

Here is the problem that the WorkerProvisioner SPI does not solve on its own.

### What happens without it

When Drools fires as a Worker and concludes "transaction matches typology 4B — SAR required," that conclusion enters the case as an untyped text blob in the case context JSON. When an LLM agent subsequently needs to reason about it, it receives text. The platform has no way to signal that this conclusion is:

- **Deterministic** (not probabilistic)
- **Rule-derived** (traceable to specific rules, not inference)
- **100% confidence** (unlike the LLM's own outputs)
- **Typed** (it is a `SARDecision(typologyId="4B", confidence=1.0, mandatedBy="FinCEN-2024-3")`, not free text)

Similarly, when an LLM agent concludes "I believe this is high risk, but there is ambiguity in the counterparty relationship," the LLM's confidence, its uncertainty, and which facts it relied on are all implicit in prose. Drools cannot consume this as a fact. QuarkusFlow cannot transform it structurally. The next step gets the same untyped blob.

Three conclusions — from Drools (deterministic), LLM (probabilistic), human WorkItem outcome (authoritative) — sit side-by-side as raw text. Epistemically indistinguishable.

### What is NOT this primitive

The platform already has several knowledge-bearing stores. None of them is what is needed:

| Existing | What it is | Why it is not the fact space |
|----------|-----------|------------------------------|
| `casehub-engine BlackboardRegistry` | PlanItem state coordination — tracks which workflow steps are RUNNING/DELEGATED/DONE | Plan coordination, not domain knowledge. Knows "the Drools step completed"; does not know what Drools concluded. |
| `casehub-platform CaseMemoryStore` | Queryable text memory per case | Text-oriented, not typed. Good for LLM context; cannot be consumed natively by Drools or QuarkusFlow as structured facts. |
| `casehub-ledger EventLog` | Immutable audit record | Write-once, compliance-oriented. Not a live working memory. Not designed for inter-step reasoning. |
| `casehub-eidos AgentGraphStore` | Agent task history graph | Agent-centric (who did what, with what outcomes). Not case-scoped domain knowledge. |
| Case context (JSON payload) | Untyped JSON blob passed between steps | Unstructured. No epistemic metadata. Drools cannot assert into it natively. LLM cannot distinguish rule-derived from probabilistic. |

### What it is

A **case-scoped typed fact space** — a true AI blackboard in the original sense (Hayes-Roth, 1985):

- **Live:** facts are asserted and retracted during case execution, not just logged
- **Typed:** each fact has a schema (Java record or similar) — `SARDecision`, `RiskAssessment`, `ComplianceFinding`, `LLMJudgment` with explicit `confidence`, `basis`, and `paradigm` metadata
- **Multi-paradigm readable:** Drools can assert and query typed facts natively; QuarkusFlow can read/write structured data; LLM prompts are compiled from fact summaries with epistemic metadata injected; human outcomes are recorded as typed facts with authority metadata
- **Case-scoped:** isolated per case instance, garbage-collected at case completion
- **Versioned:** fact assertions are timestamped and attributed to the worker that made them — traceability without immutability

This is distinct from the current `BlackboardRegistry` because it holds **domain knowledge claims**, not plan coordination state. It is the shared epistemic workspace for the case.

---

## How It Works Together

A concrete example — AML investigation case:

```
Case: AML investigation — suspicious wire transfer

Step 1: QuarkusFlow worker
  → Fetches transaction history, counterparty data, jurisdiction records
  → Asserts into fact space:
       CounterpartyRecord(entityId="X", jurisdiction="OFAC-listed", confidence=1.0)
       TransactionPattern(frequency="burst", amount=950_000, currency="USD")

Step 2: Drools worker
  → Receives fact space (CounterpartyRecord + TransactionPattern as typed Java facts)
  → Fires rules: Rule 47 ("burst pattern + OFAC jurisdiction = SAR mandatory")
  → Asserts into fact space:
       SARDecision(typologyId="47", mandatory=true, tracedRules=["Rule47"], confidence=1.0)

Step 3: LLM worker (Claude via claudony)
  → Receives compiled prompt: fact space summarised with epistemic metadata injected
     ("The following are rule-derived facts [confidence=1.0, deterministic]: ...")
     ("The following are available for your contextual analysis: ...")
  → Reasons about edge cases, counterparty relationship context, mitigating factors
  → Asserts into fact space:
       LLMJudgment(summary="High risk; counterparty structure unusual but not conclusive",
                   confidence=0.72, basis="contextual", uncertainties=["counterparty UBO unclear"])

Step 4: Human worker (casehub-work)
  → WorkItem presented to compliance officer with full fact space rendered
  → Officer reviews SARDecision (mandatory, Rule 47) + LLMJudgment (high risk, 0.72)
  → Records outcome:
       HumanDecision(approved=true, notes="Filed SAR — confirmed Rule 47 applies", authority="CO-7")

Step 5: QuarkusFlow worker
  → Reads HumanDecision from fact space
  → Executes SAR filing API call, updates regulatory system
  → Case completes
```

At every step, each participant reads and writes to the same typed fact space. The LLM knows which facts are rule-derived (treat as certain) vs. which are its own prior outputs (treat as uncertain). Drools operates on typed Java objects it can natively process. The human sees a structured summary, not a wall of text. The audit ledger records the complete fact provenance.

---

## The Epistemic Layer — Making Heterogeneous AI Coherent

The fact space is not enough alone. The content of facts must carry **epistemic metadata** — so that downstream participants know how to weight what they receive:

```
interface CaseFact {
    UUID id;
    Instant assertedAt;
    UUID assertedByWorker;
    AIParadigm paradigm;           // RULE_BASED | PROBABILISTIC_LLM | DETERMINISTIC_WORKFLOW | HUMAN
    double confidence;             // 0.0–1.0
    ConfidenceBasis basis;         // RULE_DERIVED | MODEL_INFERENCE | HUMAN_JUDGMENT | CALCULATED
    List<UUID> derivedFrom;        // fact IDs this was derived from (traceability chain)
    boolean retractable;           // some facts are authoritative (human decisions) and cannot be retracted by other workers
}
```

This metadata is what transforms the fact space from a shared data store into a shared **epistemic workspace**. An LLM agent receiving a `SARDecision(paradigm=RULE_BASED, confidence=1.0, basis=RULE_DERIVED)` knows to treat it as a hard constraint, not a suggestion. A Drools rule receiving an `LLMJudgment(confidence=0.72, basis=MODEL_INFERENCE)` can choose to fire or not fire based on the confidence threshold the rule encodes.

---

## What This Enables

**For regulated domains (AML, clinical, enterprise compliance):**
- Drools encodes the regulations. LLMs handle the edge cases regulations cannot anticipate. Humans govern the final call. Each layer knows what the others concluded and with what authority.
- Full audit trail: every fact is traced to the worker that asserted it, the facts it derived from, and the rule or inference that produced it.

**For AI quality:**
- Drools acts as a hard constraint layer — if a rule fires with certainty, an LLM cannot override it without human escalation. This prevents LLM reasoning from contradicting encoded compliance mandates.
- LLMs act as a generalisation layer — they handle cases Drools rules were never written for, then flag uncertainty for human review.
- The combination produces decisions that are simultaneously explainable (Drools rules fired) and contextually sensitive (LLM reasoning applied to what rules cannot see).

**For platform architecture:**
- The WorkerProvisioner SPI remains the right abstraction. No new execution interface is needed.
- The fact space is a new module alongside the existing blackboard — same case lifecycle, different purpose.
- Existing modules (qhorus, ledger, work) are unaffected. The fact space is an internal case-execution primitive, not a cross-repo concern.

---

## Where It Lands

| Component | Module | Notes |
|-----------|--------|-------|
| Typed fact space SPI (`CaseFact`, `FactStore`, `FactQuery`) | `casehub-engine-api` | Pure Java, no CDI — consumers depend on this without pulling engine runtime |
| Default in-memory `FactStore` | `casehub-engine-persistence-memory` | For `@QuarkusTest` isolation |
| JPA `FactStore` | `casehub-engine-persistence-hibernate` | Durable, case-scoped, indexed by type |
| Drools `WorkerProvisioner` | New: `casehub-drools` (Integration tier) | Implements WorkerProvisioner; marshals case facts to/from Drools working memory |
| QuarkusFlow `WorkerProvisioner` | `flow` (mdproctor/flow) — pending formal integration | Implements WorkerProvisioner; maps structured data to/from fact space |
| Fact space prompt compiler | `casehub-engine` (runtime) | Compiles typed facts + epistemic metadata into LLM prompt context; injected by `WorkOrchestrator` before LLM worker dispatch |

The fact space is a casehub-engine concern — it is case-scoped and only live during case execution. It is not a platform-api concern (too engine-specific) and not a ledger concern (it is mutable working memory, not an immutable audit record — at case close, the final fact state may be snapshot to the ledger for compliance, but the live store is engine-internal).

---

## Priority and Sequencing

This capability is the architectural foundation for the AI Fusion positioning. Without it:
- QuarkusFlow-as-Worker and Drools-as-Worker work in isolation — they execute correctly but their outputs are opaque to each other and to LLM agents
- The platform cannot claim genuine AI Fusion — it is multi-paradigm orchestration without synthesis

The suggested sequencing:
1. Establish QuarkusFlow-as-Worker (WorkerProvisioner implementation, basic integration)
2. Establish Drools-as-Worker (WorkerProvisioner implementation, fact marshalling)
3. Design and implement the typed fact space SPI + JPA store
4. Implement the fact-space prompt compiler in engine (so LLM workers receive typed context)
5. Wire fact space into QuarkusFlow and Drools workers so they read/write natively
6. Validate end-to-end with a regulated-domain case (AML or clinical — both already have application repos)
