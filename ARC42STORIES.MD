# ARC42STORIES — casehub-neural-text

**Version:** 0.1  
**Status:** Scaffold — delivery not yet started  
**Spec:** [arc42stories-spec.md](https://raw.githubusercontent.com/casehubio/parent/main/docs/arc42stories-spec.md)  
**Last updated:** 2026-06-04

---

## §1 Introduction and Goals

`casehub-neural-text` provides two related capabilities:

**Neural Text Inference** — a standalone, general-purpose ONNX model execution layer for JVM projects. Fills the gap LangChain4j leaves: NLI (hallucination detection), classification (action risk), regression (epistemic confidence), SPLADE sparse embeddings, and cross-encoder reranking. Zero casehub domain dependencies in the core modules. Shared with Hortora.

**RAG Integration** — casehub-specific LangChain4j RAG pipeline wiring with tenancy-isolated Qdrant corpus storage, hybrid dense+sparse search via RRF fusion, and `CorpusStore`/`CaseRetriever` SPIs for use by engine case steps and the typed fact space.

### Stakeholders

| Stakeholder | Concern |
|---|---|
| casehub-engine | Hallucination detection hook, fact space prompt compiler via CaseRetriever |
| casehub-openclaw | ActionRiskClassifier SPI implementation via TextClassifier |
| casehub-eidos | Dynamic epistemic domain confidence estimation via ScalarRegressor |
| Hortora | SPLADE sparse embeddings + CrossEncoderReranker — takes inference-* only |
| Application repos (aml, clinical) | Corpus-grounded LLM reasoning via CaseRetriever |

### Top Quality Goals

| Priority | Goal | Scenario |
|---|---|---|
| 1 | **Domain isolation** | `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade` pass ArchUnit: zero casehub/Quarkus/Spring/LangChain4j deps |
| 2 | **Local inference** | No API call required for any `InferenceModel.run()` — all models run in-process via ONNX Runtime JVM |
| 3 | **Testability without JNI** | `inference-inmem` stubs allow complete unit testing of all task adapters and rag pipeline without loading ONNX Runtime |
| 4 | **Tenancy isolation** | Every `CaseRetriever.retrieve()` is tenancy-scoped; cross-tenant retrieval is blocked at the SPI boundary |

### Artifact Schema

| Artifact type | Format | Example | Where it lives |
|---|---|---|---|
| Issue / epic | `#N` | `#2` | casehubio/neural-text GitHub Issues |
| Parent tracking issue | `parent#N` | `parent#158` | casehubio/parent GitHub Issues |
| Garden entry | `GE-YYYYMMDD-XXXXXX` | `GE-20260530-0dc6de` | `~/.hortora/garden/` |
| Design spec | `YYYY-MM-DD-topic` | `2026-06-03-ai-fusion` | `docs/specs/` |
| Blog entry | `YYYY-MM-DD-title` | `2026-06-04-scaffold` | workspace `blog/` |

---

## §2 Constraints

**Zero domain dependency constraint** — `inference-api`, `inference-runtime`, `inference-tasks`, and `inference-splade` must have zero dependencies on casehub domain artifacts, Quarkus, Spring, or LangChain4j. Enforced by ArchUnit from day one. This constraint exists because Hortora must be able to depend on these modules without importing any casehub runtime.

**Native image gate** — `inference-quarkus` and Hortora's distributable native binary both require ONNX Runtime JNI and HuggingFace Tokenizers JNI to work in Quarkus native image on macOS ARM. This is unproven. Until the prototype (C2) passes, `inference-quarkus` is JVM-only.

**LangChain4j for dense RAG** — dense embeddings and the RAG pipeline use LangChain4j (`OnnxEmbeddingModel`, `DocumentSplitter`, Qdrant `EmbeddingStore`). This is not negotiable — casehub already uses LangChain4j (eidos uses langchain4j-core) and rebuilding the dense pipeline would be duplication.

**Hortora shares inference-* only** — `rag-*` modules are casehub-specific. Hortora wires their own LangChain4j RAG stack independently. No code is shared between the two RAG wiring layers.

**casehub-platform version** — rag/* modules depend on casehub-platform-api for tenancy primitives. Must stay in sync with the platform version pinned in casehub-parent BOM.

---

## §3 Context and Scope

```mermaid
C4Context
  title casehub-neural-text — System Context

  Person(hortora, "Hortora", "Takes inference-* modules; wires own RAG stack")
  Person(casehub_eng, "casehub-engine", "Hallucination detection; fact space prompt compiler")
  Person(casehub_oc, "casehub-openclaw", "ActionRiskClassifier via TextClassifier")
  Person(casehub_ei, "casehub-eidos", "Epistemic confidence via ScalarRegressor")
  Person(app_repos, "Application repos", "Corpus-grounded LLM reasoning")

  System_Boundary(b, "casehub-neural-text") {
    System(inference, "inference-*", "ONNX model execution layer — NLI, classification, SPLADE, reranking")
    System(rag, "rag-*", "LangChain4j RAG pipeline wiring — corpus storage, hybrid search, retrieval")
  }

  System_Ext(onnx, "ONNX Runtime JVM", "Model execution via JNI")
  System_Ext(hf_tok, "HuggingFace Tokenizers", "Tokenization via JNI")
  System_Ext(langchain, "LangChain4j", "Dense embeddings, chunking, RAG pipeline")
  System_Ext(qdrant, "Qdrant", "Vector store — dense + sparse named vector spaces")

  Rel(hortora, inference, "Takes inference-* as dependency")
  Rel(casehub_eng, inference, "NliClassifier for hallucination detection")
  Rel(casehub_eng, rag, "CaseRetriever for prompt compilation")
  Rel(casehub_oc, inference, "TextClassifier for ActionRiskClassifier")
  Rel(casehub_ei, inference, "ScalarRegressor for epistemic confidence")
  Rel(app_repos, rag, "CaseRetriever for knowledge-grounded reasoning")
  Rel(inference, onnx, "Model execution")
  Rel(inference, hf_tok, "Tokenization")
  Rel(rag, langchain, "Dense embedding + chunking + retrieval pipeline")
  Rel(rag, qdrant, "Vector storage + hybrid search")
  Rel(rag, inference, "SparseEmbedder for sparse search leg")
```

**What is in scope:**
- Running pre-trained ONNX models for text tasks
- casehub RAG pipeline wiring with tenancy isolation and hybrid search
- SPIs for extension and testability

**What is out of scope:**
- Model training or fine-tuning
- Dense embedding for RAG (LangChain4j's responsibility)
- Agent-to-agent messaging (casehub-qhorus)
- Audit ledger (casehub-ledger)
- Case orchestration (casehub-engine)

---

## §4 Solution Strategy

### Core architectural patterns

**SPI layering** — every capability is expressed as a SPI in `inference-api` or `rag-api` (pure Java, zero deps). Implementations live in separate modules. Callers depend only on the SPI; implementations are swapped by adding/removing classpath entries. This enables `inference-inmem` for testing without JNI, and enables Hortora to wire its own implementations.

**Task adapter pattern** — raw ONNX tensor output is never exposed to callers. Typed task adapters (`NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker`) in `inference-tasks` interpret tensor names and post-process outputs into domain types. An LLM calling `NliClassifier.classify("premise", "hypothesis")` receives an `NliResult` with named fields — no knowledge of tensor names required.

**Hybrid RAG** — two vector representations per document: dense (LangChain4j `OnnxEmbeddingModel`) and sparse (SPLADE via `inference-splade`). Both stored in Qdrant named vector spaces. Retrieval uses either or both, fused via RRF. Dense handles semantic similarity; sparse handles lexical precision for regulatory/clinical text.

**Tenancy isolation at the SPI boundary** — `CaseRetriever.retrieve()` and `CorpusStore.ingest()` require a `CorpusRef` carrying the tenant ID. No cross-tenant retrieval is possible through the SPI. Qdrant payload filtering enforces isolation at the storage layer.

### Relationship to LangChain4j

This module sits **below** LangChain4j for inference and **above** LangChain4j for RAG:

| Capability | Where |
|---|---|
| Dense float-vector embeddings | LangChain4j `OnnxEmbeddingModel` |
| Document parsing, chunking, retrieval pipeline | LangChain4j |
| Sparse SPLADE embeddings | `inference-splade` (this module) |
| NLI, classification, regression, cross-encoder | `inference-tasks` (this module) |
| casehub RAG wiring, tenancy, hybrid search | `rag-*` (this module) |

### Chapter sequencing rationale

- C2 before C5 and C6: **hard** — native image gate must pass before `inference-quarkus` (C5) or `inference-splade` (C6) commit to native image. If gate fails, C5 and C6 continue in JVM-only mode.
- C3 before C4: **hard** — `inference-tasks` wraps `InferenceModel` from `inference-runtime`; runtime must exist before adapters can be built.
- C4 before C6: **soft** — `inference-splade` follows the same adapter pattern as `inference-tasks`; C4 establishes it.
- C6 before C7: **hard** — `casehub-rag` uses `SparseEmbedder` from `inference-splade` for the sparse search leg.
- C3 and C5 independent: C5 (Quarkus CDI wiring) wraps C3 (runtime), but C3 ships first as a usable JVM-only artifact; C5 adds the Quarkus layer separately.

---

## §5 Building Block View

```mermaid
C4Container
  title casehub-neural-text — Layer Architecture (scaffold state)

  Container_Boundary(b_inference_spi, "Layer: SPI Foundation") {
    Container(inference_api, "inference-api", "Pure Java", "InferenceModel SPI, InferenceInput, InferenceOutput, ModelConfig")
    Container(inference_inmem, "inference-inmem", "Pure Java", "Deterministic stubs — no JNI, safe in all test contexts")
  }

  Container_Boundary(b_inference_rt, "Layer: Runtime Core") {
    Container(inference_rt, "inference-runtime", "ONNX Runtime JVM + HF Tokenizers", "OrtSession management, tokenization, batch inference")
  }

  Container_Boundary(b_inference_tasks, "Layer: Task Adapters") {
    Container(inference_tasks, "inference-tasks", "Pure Java", "NliClassifier, TextClassifier, ScalarRegressor, CrossEncoderReranker")
    Container(inference_splade, "inference-splade", "Pure Java", "SparseEmbedder — log-saturation SPLADE output")
  }

  Container_Boundary(b_inference_q, "Layer: Quarkus Integration") {
    Container(inference_quarkus, "inference-quarkus", "Quarkus extension", "@InferenceModel qualifier, CDI wiring, Dev Services")
  }

  Container_Boundary(b_rag_spi, "Layer: RAG SPI") {
    Container(rag_api, "rag-api", "Pure Java", "CorpusStore SPI, CaseRetriever SPI, RetrievedChunk, CorpusRef")
    Container(rag_testing, "rag-testing", "Pure Java", "In-memory CorpusStore + CaseRetriever stubs")
  }

  Container_Boundary(b_rag_rt, "Layer: RAG Runtime") {
    Container(rag, "rag", "Quarkus + LangChain4j", "LangChain4j pipeline, Qdrant, hybrid RRF fusion, tenancy isolation")
  }

  Rel(inference_tasks, inference_api, "wraps InferenceModel")
  Rel(inference_splade, inference_api, "wraps InferenceModel")
  Rel(inference_rt, inference_api, "implements InferenceModel")
  Rel(inference_inmem, inference_api, "implements InferenceModel (stub)")
  Rel(inference_quarkus, inference_rt, "CDI wires")
  Rel(rag, rag_api, "implements CorpusStore + CaseRetriever")
  Rel(rag, inference_splade, "SparseEmbedder for sparse search leg")
  Rel(rag_testing, rag_api, "implements stubs")
```

### Layers

| Layer | Module | Tier | Hortora? |
|---|---|---|---|
| L1 SPI Foundation | `inference-api`, `inference-inmem` | Pure Java, zero deps | ✅ yes |
| L2 Runtime Core | `inference-runtime` | ONNX Runtime JVM + HF Tokenizers JNI | ✅ yes |
| L3 Task Adapters | `inference-tasks` | Pure Java | ✅ yes |
| L4 Sparse Embeddings | `inference-splade` | Pure Java | ✅ yes |
| L5 Quarkus Integration | `inference-quarkus` | Quarkus extension | casehub only |
| L6 RAG SPI | `rag-api`, `rag-testing` | Pure Java, zero deps | casehub only |
| L7 RAG Runtime | `rag` | Quarkus + LangChain4j | casehub only |

---

## §6 Runtime View

### Inference flow (NLI example — hallucination detection)

```mermaid
C4Dynamic
  title Runtime: NliClassifier.classify() — hallucination detection

  Person(engine, "casehub-engine", "ObservabilityHook")
  Container(nli, "NliClassifier", "inference-tasks")
  Container(model, "InferenceModel", "inference-runtime (OrtSession)")
  Container(hf, "HuggingFace Tokenizers JNI", "tokenization")
  Container(onnx, "ONNX Runtime JVM", "model execution")

  Rel(engine, nli, "classify(premise, hypothesis)")
  Rel(nli, model, "run(InferenceInput.pair(premise, hypothesis))")
  Rel(model, hf, "tokenize(texts) → token IDs + attention mask")
  Rel(model, onnx, "OrtSession.run(inputTensors) → raw float tensors")
  Rel(model, nli, "InferenceOutput")
  Rel(nli, engine, "NliResult(entailment, neutral, contradiction)")
```

### RAG retrieval flow

```mermaid
C4Dynamic
  title Runtime: CaseRetriever.retrieve() — hybrid search

  Person(engine, "casehub-engine", "fact space prompt compiler")
  Container(retriever, "CaseRetriever", "rag — EmbeddingStoreContentRetriever + SparseEmbedder + RRF")
  Container(langchain, "LangChain4j OnnxEmbeddingModel", "dense vectors")
  Container(splade, "SparseEmbedder", "inference-splade")
  Container(qdrant, "Qdrant", "named vector spaces: dense + sparse")

  Rel(engine, retriever, "retrieve(query, corpusRef)")
  Rel(retriever, langchain, "embed(query) → float[]")
  Rel(retriever, splade, "embed(query) → Map<Integer,Float>")
  Rel(retriever, qdrant, "hybrid query: dense + sparse, payload filter: tenantId")
  Rel(qdrant, retriever, "List<RankedChunk>")
  Rel(retriever, engine, "List<RetrievedChunk> (RRF-fused, reranked)")
```

---

## §7 Deployment View

```mermaid
C4Deployment
  title casehub-neural-text — Deployment (JVM mode, pending native image gate)

  Deployment_Node(jvm, "JVM (Java 21+)", "hosting process") {
    Deployment_Node(quarkus, "Quarkus application", "consuming app") {
      Container(inference_quarkus, "inference-quarkus", "Quarkus extension", "@ApplicationScoped per ModelConfig")
      Container(rag, "rag", "Quarkus module", "LangChain4j + Qdrant client")
    }
    Deployment_Node(onnx_rt, "ONNX Runtime JVM native lib", "JNI") {
      Container(ort, "libonnxruntime", "native", "model execution")
    }
    Deployment_Node(hf_rt, "HuggingFace Tokenizers native lib", "JNI") {
      Container(hft, "libtokenizers", "native", "tokenization")
    }
  }

  Deployment_Node(qdrant_server, "Qdrant", "vector store") {
    Container(qdrant_db, "Qdrant collections", "gRPC/REST", "named vector spaces per tenant corpus")
  }

  Rel(inference_quarkus, ort, "JNI — model inference")
  Rel(inference_quarkus, hft, "JNI — tokenization")
  Rel(rag, qdrant_db, "gRPC — upsert + hybrid query")
```

**Native image:** conditional on C2 prototype. Both JNI layers must work in a Quarkus native image binary on macOS ARM before `inference-quarkus` switches to native mode. Until then, JVM mode only.

---

## §8 Crosscutting Concepts

### Domain isolation enforcement

All `inference-*` modules (except `inference-quarkus`) and `rag-api` enforce zero external domain dependencies via ArchUnit. This is the single most critical architectural constraint. Violation breaks Hortora's dependency contract without compile-time error.

```java
// Example ArchUnit rule (inference-api)
noClasses().that().resideInAPackage("io.casehub.inference..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("io.casehub..", "io.quarkus..", "org.springframework..", "dev.langchain4j..")
    .check(importedClasses);
```

### JNI lifecycle

`OrtSession` is thread-safe for concurrent inference. Model loading (file I/O + native heap allocation) is expensive — one session per `ModelConfig`, loaded once at startup and held for the process lifetime. In `inference-quarkus`, each configured model is an `@ApplicationScoped` CDI bean. Never create sessions per-request.

### Tenancy isolation

`CorpusRef` carries `tenantId`. `CaseRetriever.retrieve()` passes tenant ID as a Qdrant payload filter on every query. No cross-tenant retrieval is possible through the SPI. Cross-tenant access requires a separate `@CrossTenant`-qualified bean (not yet defined — file issue when needed).

### Testing without JNI

`inference-inmem` provides deterministic `InferenceModel` stubs: fixed outputs, configurable per test, no JNI, no native libs. All task adapters and rag pipeline components are testable without loading ONNX Runtime. This is mandatory — CI must not require model files or native libraries.

### Anti-patterns

**Exposing tensors to callers** — `Symptom:` callers reference tensor names like `"logits"` or `"last_hidden_state"` directly. `Cause:` task adapter layer bypassed. `Fix:` all tensor interpretation lives in `inference-tasks`; callers receive domain types.

**Session per request** — `Symptom:` inference is slow and memory grows. `Cause:` `OrtEnvironment.createSession()` called in request scope. `Fix:` `@ApplicationScoped` CDI bean; one session per model config.

**Domain types in inference-api** — `Symptom:` ArchUnit fails on Hortora's build. `Cause:` `InferenceInput` or `ModelConfig` references casehub domain types. `Fix:` only `java.*` and `java.util.*` in `inference-api`; domain mapping in adapters.

**Single Qdrant collection for all tenants** — `Symptom:` tenant A's retrieval returns tenant B's documents. `Cause:` payload filter missing or per-tenant collection not used. `Fix:` CorpusRef carries tenantId; Qdrant query always includes `filter: {must: [{key: "tenantId", match: {value: tenantId}}]}`.

**Using LangChain4j OnnxEmbeddingModel for SPLADE** — `Symptom:` sparse embeddings are dense float[] instead of `Map<Integer,Float>`. `Cause:` LangChain4j's `OnnxEmbeddingModel` is dense-only. `Fix:` SPLADE goes through `inference-splade` `SparseEmbedder`, not LangChain4j.

---

## §9 Journeys and Chapters

### §9.1 Journey Overview

| Journey | Description | Chapters | Status |
|---|---|---|---|
| J1 Neural Inference | Deliver the standalone ONNX inference layer — SPI, runtime, adapters, SPLADE, Quarkus wiring | C1–C6 | 🚧 In progress (C1 complete) |
| J2 RAG Integration | Deliver casehub-specific LangChain4j RAG pipeline with hybrid search | C7 | 🔲 Pending (blocked on C6) |

### §9.2 Chapter Index

```mermaid
flowchart LR
  C1["C1: Scaffold\nNone"] --> C2["C2: Native Image Gate\n+ Risk validation"]
  C1 --> C3["C3: SPI + Runtime\n+ L1, L2"]
  C2 --> C5["C5: Quarkus\n+ L5"]
  C2 --> C6["C6: SPLADE\n+ L4"]
  C3 --> C4["C4: Task Adapters\n+ L3"]
  C4 --> C5
  C4 --> C6
  C6 --> C7["C7: RAG\n+ L6, L7"]

  style C1 fill:#90EE90,color:#000
  style C2 fill:#D3D3D3,color:#000
  style C3 fill:#D3D3D3,color:#000
  style C4 fill:#D3D3D3,color:#000
  style C5 fill:#D3D3D3,color:#000
  style C6 fill:#D3D3D3,color:#000
  style C7 fill:#D3D3D3,color:#000
```

| # | Chapter | Journey | Layers touched | Delta summary | Status |
|---|---|---|---|---|---|
| 1 | Scaffold | J1 | — | None | ✅ |
| 2 | Native Image Gate | J1 | (cross-cutting) | Risk validation | ✅ |
| 3 | SPI Foundation + Runtime Core | J1 | L1, L2 | High, High | 🔲 |
| 4 | Task Adapters | J1 | L3 | High | 🔲 |
| 5 | Quarkus Integration | J1 | L5 | High | 🔲 |
| 6 | Sparse Embeddings (SPLADE) | J1 | L4 | High | 🔲 |
| 7 | RAG Pipeline | J2 | L6, L7 | High, High | 🔲 |

**Layer × Chapter matrix**

| Layer | C1 | C2 | C3 | C4 | C5 | C6 | C7 |
|---|---|---|---|---|---|---|---|
| L1 SPI Foundation | — | — | High | — | — | — | — |
| L2 Runtime Core | — | Low* | High | — | — | — | — |
| L3 Task Adapters | — | — | — | High | — | — | — |
| L4 Sparse Embeddings | — | — | — | — | — | High | Low |
| L5 Quarkus Integration | — | — | — | — | High | — | — |
| L6 RAG SPI | — | — | — | — | — | — | High |
| L7 RAG Runtime | — | — | — | — | — | — | High |

*C2 validates L2's JNI stack in native image; L2 itself is not built until C3.

### §9.3 Chapter Entries

---

#### Chapter 1 — Scaffold

**Journey:** J1 | **Sequence:** 1 of 7 | **Status:** ✅ Complete  
**Delivered:** 2026-06-04 | **Issues:** #1

**What this delivers**  
Repository exists with CLAUDE.md, CI workflow, LAYER-LOG.md, and ARC42STORIES.MD. The ecosystem recognises the repo: it appears in PLATFORM.md, build dashboards, casehubio.github.io, and the incremental build. No inference code or RAG code.

**Accountability gaps closed**  
None — this is infrastructure only.

**Layer Impact**

| Layer | Delta |
|---|---|
| (none) | — |

---

#### Chapter 2 — Native Image Gate

**Journey:** J1 | **Sequence:** 2 of 7 | **Status:** ✅ Complete  
**Delivered:** 2026-06-06 | **Issues:** #2

**What this delivers**  
A passing prototype: ONNX Runtime JNI + HuggingFace Tokenizers JNI both load and execute a real ONNX model inside a Quarkus native image binary on macOS ARM. This is a binary pass/fail gate — either both JNI layers work natively or they do not.

**Accountability gaps closed**  
- Native deployment commitment → confirmed or deferred

**Layer Impact**

| Layer | Delta |
|---|---|
| L2 Runtime Core | Low (validation only — no production code) |

**Note:** If prototype fails, C5 (Quarkus CDI wiring) proceeds in JVM-only mode. Hortora's distributable native binary goal is deferred pending alternative options (JVM + CDS, sidecar process).

---

#### Chapter 3 — SPI Foundation + Runtime Core

**Journey:** J1 | **Sequence:** 3 of 7 | **Status:** ✅ Complete  
**Issues:** #3 | **Blocked by:** — (independent of C2)

**What this delivers**  
`inference-api` and `inference-runtime` are published artifacts. Any JVM project (casehub or Hortora) can depend on `casehub-inference-api` and `casehub-inference-runtime` and execute ONNX model inference. `inference-inmem` stubs are available for testing without JNI.

**Accountability gaps closed**  
- ArchUnit zero-domain-dep constraint → enforced from this chapter forward

**Layer Impact**

| Layer | Delta |
|---|---|
| L1 SPI Foundation | High |
| L2 Runtime Core | High |

---

#### Chapter 4 — Task Adapters

**Journey:** J1 | **Sequence:** 4 of 7 | **Status:** 🔲 Pending  
**Issues:** #4 | **Blocked by:** C3

**What this delivers**  
`inference-tasks` published with all four typed adapters. casehub-engine can integrate `NliClassifier` for hallucination detection. casehub-openclaw can integrate `TextClassifier` for `ActionRiskClassifier`. casehub-eidos can integrate `ScalarRegressor` for epistemic confidence. Hortora can integrate `CrossEncoderReranker` for precision-mode reranking.

**Accountability gaps closed**  
- Hallucination detection hook → NliClassifier available
- ActionRiskClassifier stub → TextClassifier available

**Layer Impact**

| Layer | Delta |
|---|---|
| L3 Task Adapters | High |

---

#### Chapter 5 — Quarkus Integration

**Journey:** J1 | **Sequence:** 5 of 7 | **Status:** 🔲 Pending  
**Issues:** #5 | **Blocked by:** C3, C4; conditional on C2 for native image

**What this delivers**  
`inference-quarkus` published. Quarkus applications configure ONNX models via `application.properties` and inject them with `@InferenceModel("name")`. Dev Services make models available in development without manual setup. `@QuarkusTest` uses `inference-inmem` stubs automatically.

**Accountability gaps closed**  
- Quarkus-native model configuration → CDI-managed model lifecycle

**Layer Impact**

| Layer | Delta |
|---|---|
| L5 Quarkus Integration | High |

---

#### Chapter 6 — Sparse Embeddings (SPLADE)

**Journey:** J1 | **Sequence:** 6 of 7 | **Status:** 🔲 Pending  
**Issues:** #6 | **Blocked by:** C2 (ONNX native image validation), C4 (adapter pattern established)

**What this delivers**  
`inference-splade` published. `SparseEmbedder` produces log-saturated sparse term weight maps from SPLADE models. The sparse leg of casehub-rag's hybrid search is now available. Hortora can use `SparseEmbedder` for their Qdrant hybrid search. Candidate for upstream LangChain4j contribution (#1600).

**Accountability gaps closed**  
- Hybrid search sparse leg → SparseEmbedder available

**Layer Impact**

| Layer | Delta |
|---|---|
| L4 Sparse Embeddings | High |

---

#### Chapter 7 — RAG Pipeline

**Journey:** J2 | **Sequence:** 7 of 7 | **Status:** 🔲 Pending  
**Issues:** #7 | **Blocked by:** C6 (SparseEmbedder for sparse search leg)

**What this delivers**  
`rag-api`, `rag`, and `rag-testing` published. casehub application repos can define named corpora in configuration, ingest documents via `CorpusStore`, and retrieve grounded context via `CaseRetriever`. Hybrid dense+sparse search operates across tenancy-isolated Qdrant collections. The fact space prompt compiler in casehub-engine can inject retrieved context into LLM worker prompts.

**Accountability gaps closed**  
- Knowledge-grounded LLM reasoning → CaseRetriever available for engine case steps

**Layer Impact**

| Layer | Delta |
|---|---|
| L4 Sparse Embeddings | Low (consumed, not extended) |
| L6 RAG SPI | High |
| L7 RAG Runtime | High |

---

### §9.4 Layer Entries

---

#### Layer L1 — SPI Foundation

**Participates in chapters:** C3  
**Architectural patterns:** Hexagonal (Ports and Adapters), SPI Registry  
**Key protocols:** `module-tier-structure.md` (pure Java, zero deps), `spi-default-method-contract-test.md`  
**Design refs:** `docs/specs/2026-06-03-standalone-rag-retrieval-brief.md`, `Hortora/spec: docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`  
**Issues:** #3 | parent#158  
**Completed:** ✅

#### What it adds

Defines the contract between callers and ONNX model execution. Before this layer: no shared type language between casehub modules, Hortora, and ONNX Runtime. After: all parties agree on `InferenceModel`, `InferenceInput`, `InferenceOutput`, and `ModelConfig`. Implementations (real ONNX, in-memory stubs) are substituted at the CDI or classpath level.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Hortora dependency contract | Hortora forced to depend on casehub domain types | Zero-dep `inference-api` — ArchUnit enforced |
| Testability without JNI | Unit tests require ONNX Runtime native libs | `inference-inmem` stubs implement `InferenceModel` |

#### Key files
🔲 — populated at C3 close

#### Key wiring
🔲 — populated at C3 close

#### Architectural decisions

`InferenceInput` uses `List<String> texts` only — tokenization is the runtime's responsibility, not the caller's. Callers never touch token IDs or attention masks. This keeps the SPI portable across tokenizer versions and model families.

`InferenceOutput` exposes `float[] tensor(String outputName)` — raw tensor by name. Task adapters in L3 interpret tensor names. Callers never see tensor names directly. This isolates model-specific output structure from consuming code.

#### Pattern introduced
`InferenceModel` SPI — thin contract over any ONNX model; stubs via `inference-inmem`; implementations via `inference-runtime`; typed interpretation via `inference-tasks`.

#### Pattern anchor
🔲 — `InferenceModel.java`, `InferenceInput.java` (to be created in `inference-api/`)

#### Gotchas
🔲 — populated at C3 close

#### Pattern to replicate
🔲 — populated at C3 close

---

#### Layer L2 — Runtime Core

**Participates in chapters:** C2 (validation), C3 (implementation)  
**Architectural patterns:** Session Pool (one session per ModelConfig, singleton lifetime)  
**Design refs:** `docs/specs/2026-06-03-standalone-rag-retrieval-brief.md` §Native Image Gate  
**Issues:** #2, #3 | parent#158  
**Completed:** ✅

#### What it adds

Bridges the JVM world and ONNX Runtime JNI. Before this layer: ONNX models cannot be executed from JVM code without platform-specific native library setup. After: text can be tokenized via HuggingFace Tokenizers JNI and passed to an ONNX Runtime session; raw float tensors are returned as `InferenceOutput`.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Local model execution | Every inference requires an LLM API call | `inference-runtime` executes models in-process via JNI |
| Air-gapped deployment | Regulated deployments (clinical, AML) cannot call external APIs | ONNX Runtime runs locally; no network required |

#### Key files
🔲 — populated at C3 close

#### Key wiring

`OrtSession` is thread-safe for concurrent inference — do not create a new session per request. One session per `ModelConfig`, loaded at startup, held for process lifetime. In `inference-quarkus`, each model config becomes an `@ApplicationScoped` CDI bean.

HuggingFace Tokenizers JNI: requires the native library to be on `java.library.path` or bundled in the JAR. For native image, a GraalVM resource config JSON must register the native lib path.

#### Architectural decisions

Two JNI layers (ONNX Runtime + HuggingFace Tokenizers) are required rather than one. ONNX Runtime handles model execution; HuggingFace Tokenizers handles tokenization. Alternative (LangChain4j tokenization) rejected — LangChain4j tokenizers are dense-embedding-specific and do not produce token ID + attention mask tensors needed for arbitrary ONNX model input.

#### Gotchas
See `inference-quarkus/src/main/resources/NATIVE-IMAGE-NOTES.md` — 3 gotchas documented (GE-20260606-d3cd87, GE-20260606-7bf5dd, GE-20260606-025601)

#### Pattern to replicate
🔲 — populated at C3 close

---

#### Layer L3 — Task Adapters

**Participates in chapters:** C4  
**Architectural patterns:** Adapter (wraps InferenceModel; exposes domain types)  
**Issues:** #4 | parent#158  
**Completed:** 🔲

#### What it adds

Callers interact with domain types, never raw tensors. `NliClassifier` interprets the `logits` tensor as entailment/neutral/contradiction scores. `TextClassifier` interprets softmax output as label + confidence. `ScalarRegressor` reads a scalar output tensor. `CrossEncoderReranker` ranks candidate texts by relevance to a query. All four wrap any `InferenceModel` — tests inject `inference-inmem` stubs.

#### Key files
🔲 — populated at C4 close

#### Architectural decisions

Each adapter is constructed with an `InferenceModel` dependency (injected or passed directly). This makes adapters independently testable without Quarkus or JNI. Quarkus wiring (`@InferenceModel` qualifier) is in L5 — adapters do not know about CDI.

SPLADE output is not an adapter in `inference-tasks` — it is a separate module (`inference-splade`, L4) because its post-processing (log-saturation, sparse map output) is distinct from the classifier/regressor/reranker pattern and because Hortora may want `inference-splade` independently without `inference-tasks`.

#### Gotchas
🔲 — populated at C4 close

#### Pattern to replicate
🔲 — populated at C4 close

---

#### Layer L4 — Sparse Embeddings (SPLADE)

**Participates in chapters:** C6, C7  
**Architectural patterns:** Adapter (wraps InferenceModel; post-processes to sparse format)  
**Design refs:** `docs/specs/2026-06-03-standalone-rag-retrieval-brief.md` §SPLADE  
**Issues:** #6 | parent#158  
**Completed:** 🔲

#### What it adds

Produces sparse term weight maps (`Map<Integer, Float>`) from SPLADE models. Enables the sparse leg of hybrid search — regulatory, clinical, and legal text benefits from SPLADE's lexical precision over dense embeddings. Post-processing: `log(1 + relu(weight))` saturation + threshold filtering (drop weights < 0.01). Output is Qdrant named vector space compatible.

#### Architectural decisions

SPLADE output is `Map<Integer, Float>` not `float[]`. Qdrant named vector spaces store both dense `float[]` (from LangChain4j) and sparse `Map<Integer, Float>` (from `SparseEmbedder`) per document. This hybrid architecture requires Qdrant — pgvector does not natively support named vector spaces for true hybrid search as of 2026-06.

Log-saturation applied before threshold: `log(1 + relu(w))` for each raw weight w. This compresses high-weight terms and prevents dominance by single high-attention tokens. Threshold 0.01 removes noise — empirically established in the SPLADE paper.

Candidate for upstream LangChain4j contribution (issue #1600) once stable.

#### Gotchas
🔲 — populated at C6 close (JNI native image issues likely here too)

#### Pattern to replicate
🔲 — populated at C6 close

---

#### Layer L5 — Quarkus Integration

**Participates in chapters:** C5  
**Architectural patterns:** CDI SPI Registration, `@Qualifier`-based disambiguation  
**Issues:** #5  
**Completed:** 🔲

#### What it adds

Quarkus applications declare ONNX models in `application.properties` and inject them with `@InferenceModel("name")`. `@ApplicationScoped` CDI lifecycle manages model sessions. `@QuarkusTest` receives `inference-inmem` stubs automatically via the `@DefaultBean` displacement pattern.

#### Architectural decisions

`@InferenceModel` is a custom CDI qualifier, not `@Named`. This enforces type safety at injection points — a `TextClassifier` bean cannot be accidentally injected with a mismatched model config.

Each model config produces one `InferenceModel` bean. Beans are `@ApplicationScoped` (not `@Singleton`) because Quarkus CDI treats `@ApplicationScoped` as the correct scope for application-lifetime beans.

Native image mode conditional on C2: if JNI validation passes, `inference-quarkus` produces a native-image-capable build. If not, JVM mode only.

#### Gotchas
🔲 — populated at C5 close

#### Pattern to replicate
🔲 — populated at C5 close

---

#### Layer L6 — RAG SPI

**Participates in chapters:** C7  
**Architectural patterns:** Hexagonal (Ports and Adapters)  
**Issues:** #7 | parent#164  
**Completed:** 🔲

#### What it adds

Defines the contract for knowledge corpus management and retrieval within casehub. `CorpusStore` allows application repos to ingest and manage documents scoped to a tenant corpus. `CaseRetriever` provides the retrieval interface for engine case steps and the typed fact space prompt compiler. `rag-testing` stubs make both interfaces usable in `@QuarkusTest` without Qdrant.

#### Architectural decisions

`CorpusRef` (tenantId + corpusName) is mandatory on all SPI calls — no implicit ambient tenancy. This enforces tenancy isolation at the type level, not just the runtime level.

`CorpusStore` and `CaseRetriever` are separate SPIs. `CorpusStore` is write-oriented (ingest, delete, list). `CaseRetriever` is read-oriented (retrieve). This separation allows different implementations and different CDI beans for admin vs. runtime contexts.

`RetrievedChunk` carries `sourceDocumentId`, `content`, `relevanceScore`, and `embeddingStrategy` (DENSE, SPARSE, HYBRID). Callers know how each chunk was retrieved — relevant for epistemic metadata in the typed fact space.

#### Gotchas
🔲 — populated at C7 close

#### Pattern to replicate
🔲 — populated at C7 close

---

#### Layer L7 — RAG Runtime

**Participates in chapters:** C7  
**Architectural patterns:** Pipeline (ingest → chunk → embed → store; query → embed → retrieve → rerank → assemble)  
**Key protocols:** `flyway-extension-migration-registration.md` (no migrations — Qdrant managed externally)  
**Issues:** #7 | parent#164  
**Completed:** 🔲

#### What it adds

Implements `CorpusStore` and `CaseRetriever` using LangChain4j's RAG pipeline backed by Qdrant. Hybrid search: LangChain4j `OnnxEmbeddingModel` (dense) + `inference-splade` `SparseEmbedder` (sparse), fused via RRF. Optional `CrossEncoderReranker` for precision-mode top-N. Tenancy isolation via Qdrant payload filtering.

**Hybrid search until LangChain4j #4994 ships:** Qdrant's native named vector space hybrid search is accessed via the Qdrant REST API directly for the sparse leg. When LangChain4j #4994 (Qdrant hybrid search) ships, migrate to the native LangChain4j integration.

#### Architectural decisions

Qdrant chosen over pgvector for the hybrid leg — pgvector does not natively support named vector spaces for true hybrid search. When LangChain4j #4994 ships, re-evaluate whether pgvector has caught up.

RRF (Reciprocal Rank Fusion) used to merge dense and sparse result lists. Scores are not comparable across embedding types; rank is. RRF is parameter-free and well-studied. Weighted combination rejected — requires tuning per domain.

`casehub-platform-api` is the only casehub dependency in `rag` — `CurrentPrincipal` and `TenancyConstants` for tenancy isolation. No engine, ledger, or qhorus types.

#### Gotchas
🔲 — populated at C7 close

#### Pattern to replicate
🔲 — populated at C7 close

---

## §10 Architectural Decisions

*(decisions not captured inline in layer or chapter entries)*

**AD-001 — Single repo for inference-* and rag-*** (2026-06-04)  
inference-* and rag-* are in the same repo (`casehubio/neural-text`). Rationale: rag-* uses `inference-splade` and `CrossEncoderReranker` — they are commonly deployed together in casehub. Hortora ignores `rag-*` by depending only on `inference-*` artifacts. Can be split into separate repos later if Hortora's dependency footprint becomes a concern. Cost of splitting: low (Maven multi-module → separate projects). Cost of premature splitting: one more repo to maintain before any code ships.

**AD-002 — LangChain4j for dense RAG, not a custom implementation** (2026-06-04)  
LangChain4j provides Tika document parsing, chunking, `OnnxEmbeddingModel` (dense ONNX embeddings), and 30+ vector store integrations including Qdrant. Building a parallel implementation would duplicate ~80% of what LangChain4j already does. casehub already uses langchain4j-core (in casehub-eidos). The remaining gap (SPLADE, NLI, classification) is precisely what `casehub-neural-text` adds.

---

## §11 Quality Requirements

| Quality goal | Scenario | Measure |
|---|---|---|
| Domain isolation | Hortora builds against `inference-*` without casehub on classpath | ArchUnit: zero casehub/Quarkus/LangChain4j deps in inference-api, runtime, tasks, splade |
| Testability | All task adapters and rag pipeline unit-testable | 100% test coverage possible without ONNX Runtime native libs via `inference-inmem` |
| Local inference | Hallucination detection runs without API call | `NliClassifier.classify()` returns in <100ms for standard NLI model |
| Tenancy isolation | Tenant A cannot retrieve Tenant B's corpus | Every Qdrant query includes payload filter on tenantId; enforced by CorpusRef type |

---

## §12 Risks and Technical Debt

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| ONNX Runtime JNI fails in Quarkus native image | Medium | High | C2 prototype is mandatory first deliverable; fallback to JVM-only mode documented |
| HuggingFace Tokenizers JNI fails in native image | Medium | High | Same — C2 validates both JNI layers together |
| LangChain4j #4994 (Qdrant hybrid) never ships | Low | Low | Direct Qdrant REST API for sparse leg as interim; fully functional without LangChain4j support |
| Hortora's usage pattern conflicts with SPI design | Medium | Medium | Hortora/spec#15 validation required before C3 implementation begins |
| ArchUnit rule drift | Low | High | ArchUnit tests run in CI on every build; fail-fast |

**Technical debt:**
- SPLADE hybrid leg via direct Qdrant REST API until LangChain4j #4994 ships — migration required when it lands
- `CrossEncoderReranker` in `rag` is optional (precision mode) — default path is RRF without reranking; reranking adds latency

---

## §13 Glossary

| Term | Definition |
|---|---|
| ONNX | Open Neural Network Exchange — framework-agnostic format for ML models |
| ONNX Runtime JVM | Microsoft's ONNX Runtime Java bindings — executes ONNX models in the JVM |
| SPLADE | Sparse Lexical and Expansion model — produces sparse term weight maps combining lexical matching with learned expansion |
| NLI | Natural Language Inference — entailment/neutral/contradiction scoring between a premise and hypothesis |
| Cross-encoder | A model that jointly encodes a query + candidate pair and scores their relevance — more accurate than bi-encoder retrieval but slower |
| RRF | Reciprocal Rank Fusion — parameter-free rank-based score merging for combining results from heterogeneous retrieval strategies |
| Named vector spaces | Qdrant feature allowing multiple vector representations per document (e.g. dense + sparse) in separate nameable spaces |
| InferenceModel | The core SPI — wraps any ONNX model session; accepts `InferenceInput`, returns `InferenceOutput` |
| CorpusRef | Carries `tenantId` + `corpusName` — mandatory on all RAG SPI calls; enforces tenancy isolation |
| HuggingFace Tokenizers | Rust-based tokenizer library with JNI bindings — produces token IDs + attention masks from text |
| Native image gate | The C2 prototype that validates both JNI layers work in a Quarkus native image binary on macOS ARM |
