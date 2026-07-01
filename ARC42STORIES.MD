# ARC42STORIES — casehub-neocortex

**Version:** 0.3  
**Status:** All chapters complete (C1–C11) — J1 + J2 + J3 + J4 ✅  
**Spec:** [arc42stories-spec.md](https://raw.githubusercontent.com/casehubio/parent/main/docs/arc42stories-spec.md)  
**Last updated:** 2026-06-18

---

## §1 Introduction and Goals

`casehub-neocortex` provides two related capabilities:

**Neural Text Inference** — a standalone, general-purpose ONNX model execution layer for JVM projects. Fills the gap LangChain4j leaves: NLI (hallucination detection), classification (action risk), regression (epistemic confidence), SPLADE sparse embeddings, and cross-encoder reranking. Zero casehub domain dependencies in the core modules. Shared with Hortora.

**RAG Integration** — casehub-specific LangChain4j RAG pipeline wiring with tenancy-isolated Qdrant corpus storage, hybrid dense+sparse search via RRF fusion, and `EmbeddingIngestor`/`CaseRetriever` SPIs for use by engine case steps and the typed fact space.

### Stakeholders

| Stakeholder | Concern |
|---|---|
| casehub-engine | Hallucination detection hook, fact space prompt compiler via CaseRetriever |
| casehub-openclaw | ActionRiskClassifier SPI implementation via TextClassifier |
| casehub-eidos | Dynamic epistemic domain confidence estimation via ScalarRegressor |
| Hortora | SPLADE, CrossEncoderReranker (inference-*) + EmbeddingIngestor, CaseRetriever, CorpusIngestionService (rag-*) |
| Application repos (aml, clinical) | Corpus-grounded LLM reasoning via CaseRetriever |

### Top Quality Goals

| Priority | Goal | Scenario |
|---|---|---|
| 1 | **Domain isolation** | `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade` pass ArchUnit: zero casehub/Quarkus/Spring/LangChain4j deps |
| 2 | **Local inference** | No API call required for any `InferenceModel.run()` — all models run in-process via ONNX Runtime JVM |
| 3 | **Testability without JNI** | `inference-inmem` stubs allow complete unit testing of all task adapters and rag pipeline without loading ONNX Runtime |
| 4 | **Tenancy isolation** | Every `CaseRetriever.retrieve()` is tenancy-scoped; cross-tenant retrieval is blocked when `CurrentPrincipal` is provided. Single-tenant consumers opt out via `TenantGuard` |

### Artifact Schema

| Artifact type | Format | Example | Where it lives |
|---|---|---|---|
| Issue / epic | `#N` | `#2` | casehubio/neocortex GitHub Issues |
| Parent tracking issue | `parent#N` | `parent#158` | casehubio/parent GitHub Issues |
| Garden entry | `GE-YYYYMMDD-XXXXXX` | `GE-20260530-0dc6de` | `~/.hortora/garden/` |
| Design spec | `YYYY-MM-DD-topic` | `2026-06-03-ai-fusion` | `docs/specs/` |
| Blog entry | `YYYY-MM-DD-title` | `2026-06-04-scaffold` | workspace `blog/` |

---

## §2 Constraints

**Zero domain dependency constraint** — `inference-api`, `inference-runtime`, `inference-tasks`, and `inference-splade` must have zero dependencies on casehub domain artifacts, Quarkus, Spring, or LangChain4j. Enforced by ArchUnit from day one. This constraint exists because Hortora must be able to depend on these modules without importing any casehub runtime.

**JVM mode by design** — the inference service is long-running; native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. `inference-*` modules operate in JVM mode. The C2 native image gate passed (both JNI layers work in Quarkus native image on macOS ARM). Reachability metadata ships in `inference-quarkus` for downstream consumers that distribute as native binaries.

**LangChain4j for dense RAG** — dense embeddings and the RAG pipeline use LangChain4j (`OnnxEmbeddingModel`, `DocumentSplitter`, Qdrant `EmbeddingStore`). This is not negotiable — casehub already uses LangChain4j (eidos uses langchain4j-core) and rebuilding the dense pipeline would be duplication.

**Hortora shares inference-* and rag-*** — `rag-*` modules support optional tenancy for non-casehub consumers (#36). Hortora depends on `rag-api` (SPIs), `rag` (Qdrant implementation), and `rag-testing` (in-memory stubs). Tenancy enforcement is active when a `CurrentPrincipal` bean is provided (all casehub deployments); single-tenant consumers opt out by not providing the bean. `casehub-platform-api` remains a transitive dependency of `rag/` — version coordination required.

**casehub-platform version** — rag/* modules depend on casehub-platform-api for tenancy primitives. Must stay in sync with the platform version pinned in casehub-parent BOM.

---

## §3 Context and Scope

```mermaid
C4Context
  title casehub-neocortex — System Context

  Person(hortora, "Hortora", "Takes inference-* and rag-* modules")
  Person(casehub_eng, "casehub-engine", "Hallucination detection; fact space prompt compiler")
  Person(casehub_oc, "casehub-openclaw", "ActionRiskClassifier via TextClassifier")
  Person(casehub_ei, "casehub-eidos", "Epistemic confidence via ScalarRegressor")
  Person(app_repos, "Application repos", "Corpus-grounded LLM reasoning")

  System_Boundary(b, "casehub-neocortex") {
    System(inference, "inference-*", "ONNX model execution layer — NLI, classification, SPLADE, reranking")
    System(rag, "rag-*", "LangChain4j RAG pipeline wiring — corpus storage, hybrid search, retrieval")
  }

  System_Ext(onnx, "ONNX Runtime JVM", "Model execution via JNI")
  System_Ext(hf_tok, "HuggingFace Tokenizers", "Tokenization via JNI")
  System_Ext(langchain, "LangChain4j", "Dense embeddings, chunking, RAG pipeline")
  System_Ext(qdrant, "Qdrant", "Vector store — dense + sparse named vector spaces")

  Rel(hortora, inference, "Takes inference-* as dependency")
  Rel(hortora, rag, "Takes rag-* as dependency")
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

**Matryoshka embeddings** — `MatryoshkaEmbeddingModel` is a LangChain4j `EmbeddingModel` decorator that truncates embeddings to a target dimension and L2-renormalizes. This makes `dimension()` return the truncated size, which flows transparently to `ensureCollection()` — collection vector dimensions are automatically correct. The alternative (standalone utility + manual dimension tracking) would require coordinating truncation across ingestors, retrievers, and collection creation. The decorator centralises this in one place. Dual-vector tiered search (storing a second truncated+binary-quantized named vector per point) was evaluated and rejected: at CaseHub's per-tenant scale (<1M vectors per collection), binary index RAM savings (~112 bytes/vec) are offset by wasted HNSW graph on the unused full "dense" vector (~128 bytes/vec). Qdrant's native server-side quantization provides tiered search transparently instead.

**Tenancy isolation at the SPI boundary** — `CaseRetriever.retrieve()` and `EmbeddingIngestor.ingest()` require a `CorpusRef` carrying the tenant ID. No cross-tenant retrieval is possible through the SPI when `CurrentPrincipal` is provided. Single-tenant consumers that do not provide a `CurrentPrincipal` opt out of tenant enforcement by design. Qdrant payload filtering enforces isolation at the storage layer.

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

- C2 before C5 and C6: **completed** — native image gate passed; C5 and C6 shipped in JVM mode. Native image not pursued for the service (JVM mode by design for long-running workloads); reachability metadata available for downstream native consumers.
- C3 before C4: **hard** — `inference-tasks` wraps `InferenceModel` from `inference-runtime`; runtime must exist before adapters can be built.
- C4 before C6: **soft** — `inference-splade` follows the same adapter pattern as `inference-tasks`; C4 establishes it.
- C6 before C7: **hard** — `casehub-neocortex-rag` uses `SparseEmbedder` from `inference-splade` for the sparse search leg.
- C3 and C5 independent: C5 (Quarkus CDI wiring) wraps C3 (runtime), but C3 ships first as a usable JVM-only artifact; C5 adds the Quarkus layer separately.
- C7 before C8: **hard** — corpus storage feeds the RAG pipeline; the pipeline must exist first.
- C8 before C9: **hard** — `CorpusIngestionService` reads `ChangeSource` + `CorpusReader` from `corpus-api`; corpus modules must exist before the bridge.
- C10 after C7 and C4: **hard** — CRAG decorates CaseRetriever (C7) using CrossEncoderReranker (C4); both must exist first.
- C11 after C7 and C10: **hard** — HyDE decorates CaseRetriever (C7) and retrofits CRAG's activation model (C10); both must exist first.

---

## §5 Building Block View

```mermaid
C4Container
  title casehub-neocortex — Layer Architecture (scaffold state)

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
    Container(rag_api, "rag-api", "Pure Java", "EmbeddingIngestor SPI, CaseRetriever SPI, RetrievedChunk, CorpusRef")
    Container(rag_testing, "rag-testing", "Pure Java", "In-memory EmbeddingIngestor + CaseRetriever stubs")
  }

  Container_Boundary(b_rag_rt, "Layer: RAG Runtime") {
    Container(rag, "rag", "Quarkus + LangChain4j", "LangChain4j pipeline, Qdrant, hybrid RRF fusion, tenancy isolation, CorpusIngestionService")
  }

  Container_Boundary(b_corpus_spi, "Layer: Corpus SPI") {
    Container(corpus_api, "corpus-api", "Pure Java, zero deps", "CorpusStore, CorpusReader, ChangeSource, CorpusIntegrity SPIs")
  }

  Container_Boundary(b_corpus_rt, "Layer: Corpus Runtime") {
    Container(corpus, "corpus", "Zip4j", "ZipCorpusStore, FlatCorpusStore, CompositeCorpusStore, compaction, migration")
  }

  Container_Boundary(b_rag_crag, "Layer: CRAG") {
    Container(rag_crag, "rag-crag", "CDI library", "CorrectiveCaseRetriever @Decorator, CrossEncoderRelevanceEvaluator, RelevanceEvaluator SPI consumer")
  }

  Container_Boundary(b_rag_expansion, "Layer: Query Expansion") {
    Container(rag_expansion, "rag-expansion", "CDI library", "QueryExpandingCaseRetriever @Decorator, multi-query support, StepBackQueryExpander, LlmQueryExpander, TemplateQueryExpander, QueryExpander SPI consumer, RrfFusion")
  }

  Rel(inference_tasks, inference_api, "wraps InferenceModel")
  Rel(inference_splade, inference_api, "wraps InferenceModel")
  Rel(inference_rt, inference_api, "implements InferenceModel")
  Rel(inference_inmem, inference_api, "implements InferenceModel (stub)")
  Rel(inference_quarkus, inference_rt, "CDI wires")
  Rel(rag, rag_api, "implements EmbeddingIngestor + CaseRetriever")
  Rel(rag, inference_splade, "SparseEmbedder for sparse search leg")
  Rel(rag, corpus_api, "CorpusIngestionService reads ChangeSource + CorpusReader")
  Rel(rag_testing, rag_api, "implements stubs")
  Rel(corpus, corpus_api, "implements CorpusStore, CorpusReader, ChangeSource")
  Rel(rag_crag, rag_api, "decorates CaseRetriever")
  Rel(rag_crag, inference_tasks, "uses CrossEncoderReranker")
  Rel(rag_expansion, rag_api, "decorates CaseRetriever")
```

### Layers

| Layer | Module | Tier | Hortora? |
|---|---|---|---|
| L1 SPI Foundation | `inference-api`, `inference-inmem` | Pure Java, zero deps | ✅ yes |
| L2 Runtime Core | `inference-runtime` | ONNX Runtime JVM + HF Tokenizers JNI | ✅ yes |
| L3 Task Adapters | `inference-tasks` | Pure Java | ✅ yes |
| L4 Sparse Embeddings | `inference-splade` | Pure Java | ✅ yes |
| L5 Quarkus Integration | `inference-quarkus` | Quarkus extension | casehub only |
| L6 RAG SPI | `rag-api`, `rag-testing` | Pure Java, zero deps | ✅ yes |
| L7 RAG Runtime | `rag` | Quarkus + LangChain4j | ✅ yes |
| L8 Corpus SPI | `corpus-api` | Pure Java, zero deps | ✅ yes |
| L9 Corpus Runtime | `corpus` | Zip4j | ✅ yes |
| L10 CRAG | `rag-crag` | CDI library, classpath + config activated | ✅ yes |
| L11 Query Expansion | `rag-expansion` | CDI library, classpath + config activated | ✅ yes |

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

### Search-time oversampling for quantized vectors

When binary or scalar quantization is active, Qdrant's default oversampling (1.0) is insufficient — hamming distance is a coarse approximation. Configurable oversampling is applied via `SearchParams.QuantizationSearchParams` on the dense prefetch leg only. Sparse prefetch is unaffected (sparse vectors are not quantized). `rescore` is hardcoded to `true` because oversampling without rescoring wastes I/O.

---

## §7 Deployment View

```mermaid
C4Deployment
  title casehub-neocortex — Deployment (JVM mode)

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

**Deployment mode:** JVM. The inference service is long-running — native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. The C2 native image gate passed (both JNI layers work), and reachability metadata ships in `inference-quarkus` for downstream native consumers, but this project deploys in JVM mode by design.

**Embedding dimension consistency** — `ReactiveQdrantEmbeddingIngestor` computes `denseDimension` from its `embeddingModel` argument in the constructor, not from a separate parameter. With `MatryoshkaEmbeddingModel` wrapping active, `dimension()` returns the truncated size. The cached `denseDimension` field is retained (rather than calling `embeddingModel.dimension()` inline) because `buildCreateRequest()` runs on Qdrant's gRPC callback thread via `directExecutor()` — calling a potentially non-trivial method there risks blocking.

---

## §8 Crosscutting Concepts

### Domain isolation enforcement

All `inference-*` modules (except `inference-quarkus`) and `rag-api` enforce zero external domain dependencies via ArchUnit. This is the single most critical architectural constraint. Violation breaks Hortora's dependency contract without compile-time error.

```java
// Example ArchUnit rule (inference-api)
noClasses().that().resideInAPackage("io.casehub.neocortex.inference..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("io.casehub..", "io.quarkus..", "org.springframework..", "dev.langchain4j..")
    .check(importedClasses);
```

### JNI lifecycle

`OrtSession` is thread-safe for concurrent inference. Model loading (file I/O + native heap allocation) is expensive — one session per `ModelConfig`, loaded once at startup and held for the process lifetime. In `inference-quarkus`, each configured model is an `@ApplicationScoped` CDI bean. Never create sessions per-request.

### Tenancy isolation

`CorpusRef` carries `tenantId`. `CaseRetriever.retrieve()` passes tenant ID as a Qdrant payload filter on every query. No cross-tenant retrieval is possible through the SPI when `CurrentPrincipal` is provided (all casehub deployments). Single-tenant consumers that do not provide a `CurrentPrincipal` opt out of tenant enforcement by design — `TenantGuard` no-ops. Cross-tenant access requires a separate `@CrossTenant`-qualified bean (not yet defined — file issue when needed).

### Testing without JNI

`inference-inmem` provides deterministic `InferenceModel` stubs: fixed outputs, configurable per test, no JNI, no native libs. All task adapters and rag pipeline components are testable without loading ONNX Runtime. This is mandatory — CI must not require model files or native libraries.

### Anti-patterns

**Exposing tensors to callers** — `Symptom:` callers reference tensor names like `"logits"` or `"last_hidden_state"` directly. `Cause:` task adapter layer bypassed. `Fix:` all tensor interpretation lives in `inference-tasks`; callers receive domain types.

**Session per request** — `Symptom:` inference is slow and memory grows. `Cause:` `OrtEnvironment.createSession()` called in request scope. `Fix:` `@ApplicationScoped` CDI bean; one session per model config.

**Domain types in inference-api** — `Symptom:` ArchUnit fails on Hortora's build. `Cause:` `InferenceInput` or `ModelConfig` references casehub domain types. `Fix:` only `java.*` and `java.util.*` in `inference-api`; domain mapping in adapters.

**Single Qdrant collection for all tenants** — `Symptom:` tenant A's retrieval returns tenant B's documents. `Cause:` payload filter missing or per-tenant collection not used. `Fix:` CorpusRef carries tenantId; Qdrant query always includes `filter: {must: [{key: "tenantId", match: {value: tenantId}}]}`.

**Using LangChain4j OnnxEmbeddingModel for SPLADE** — `Symptom:` sparse embeddings are dense float[] instead of `Map<Integer,Float>`. `Cause:` LangChain4j's `OnnxEmbeddingModel` is dense-only. `Fix:` SPLADE goes through `inference-splade` `SparseEmbedder`, not LangChain4j.

### Naming conventions

**`DenseQuantization` not `QuantizationType`** — the config enum is named `DenseQuantization` because Qdrant client 1.18.1 already defines `io.qdrant.client.grpc.Collections.QuantizationType` (values: `UnknownQuantization`, `Int8`, `UNRECOGNIZED`). Both enums appear in `ensureCollection()` and `buildCreateRequest()` — sharing the name would create ambiguous unqualified usage.

---

## §9 Journeys and Chapters

### §9.1 Journey Overview

| Journey | Description | Chapters | Status |
|---|---|---|---|
| J1 Neural Inference | Deliver the standalone ONNX inference layer — SPI, runtime, adapters, SPLADE, Quarkus wiring | C1–C6 | ✅ Complete |
| J2 RAG Integration | Deliver casehub-specific LangChain4j RAG pipeline with hybrid search | C7 | ✅ Complete |
| J3 Corpus Storage | ZIP-backed document storage and ingestion bridge to RAG pipeline | C8–C9 | ✅ Complete |
| J4 Retrieval Quality | Self-healing and expanded retrieval — evaluate, filter, correct, and expand queries | C10, C11 | ✅ Complete |

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
  C7 --> C8["C8: Corpus Storage\n+ L8, L9"]
  C8 --> C9["C9: Ingestion Bridge\n+ L7 (extended)"]
  C7 --> C10["C10: CRAG\n+ L10, L6 (enriched)"]
  C4 --> C10
  C7 --> C11["C11: HyDE\n+ L11, L6, L7, L10"]
  C10 --> C11

  style C1 fill:#90EE90,color:#000
  style C2 fill:#90EE90,color:#000
  style C3 fill:#90EE90,color:#000
  style C4 fill:#90EE90,color:#000
  style C5 fill:#90EE90,color:#000
  style C6 fill:#90EE90,color:#000
  style C7 fill:#90EE90,color:#000
  style C8 fill:#90EE90,color:#000
  style C9 fill:#90EE90,color:#000
  style C10 fill:#90EE90,color:#000
  style C11 fill:#90EE90,color:#000
```

| # | Chapter | Journey | Layers touched | Delta summary | Status |
|---|---|---|---|---|---|
| 1 | Scaffold | J1 | — | None | ✅ |
| 2 | Native Image Gate | J1 | (cross-cutting) | Risk validation | ✅ |
| 3 | SPI Foundation + Runtime Core | J1 | L1, L2 | High, High | ✅ |
| 4 | Task Adapters | J1 | L3 | High | ✅ |
| 5 | Quarkus Integration | J1 | L5 | High | ✅ |
| 6 | Sparse Embeddings (SPLADE) | J1 | L4 | High | ✅ |
| 7 | RAG Pipeline | J2 | L6, L7 | High, High | ✅ |
| 8 | Corpus Storage | J3 | L8, L9 | High, High | ✅ |
| 9 | Ingestion Bridge | J3 | L6, L7 | Low, Med | ✅ |
| 10 | Corrective RAG (CRAG) | J4 | L6, L7, L10 | L6: Low, L7: Low, L10: High | ✅ (#33, #38, #41) |
| 11 | Query Expansion | J4 | L6, L7, L10, L11 | L6: Med, L7: Med, L10: Low, L11: High | ✅ (#32, #42, #43) |

**Layer × Chapter matrix**

| Layer | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| L1 SPI Foundation | — | — | High | — | — | — | — | — | — | — | — |
| L2 Runtime Core | — | Low* | High | — | — | — | — | — | — | — | — |
| L3 Task Adapters | — | — | — | High | — | — | — | — | — | — | — |
| L4 Sparse Embeddings | — | — | — | — | — | High | Low | — | — | — | — |
| L5 Quarkus Integration | — | — | — | — | High | — | — | — | — | — | — |
| L6 RAG SPI | — | — | — | — | — | — | High | — | Low | Low | Med |
| L7 RAG Runtime | — | — | — | — | — | — | High | — | Med | Low | Med |
| L8 Corpus SPI | — | — | — | — | — | — | — | High | — | — | — |
| L9 Corpus Runtime | — | — | — | — | — | — | — | High | — | — | — |
| L10 CRAG | — | — | — | — | — | — | — | — | — | High | Low |
| L11 Query Expansion | — | — | — | — | — | — | — | — | — | — | High |

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

**Note:** Native image gate passed (C2 complete). Both ONNX Runtime JNI and HuggingFace Tokenizers JNI work in Quarkus native image on macOS ARM.

---

#### Chapter 3 — SPI Foundation + Runtime Core

**Journey:** J1 | **Sequence:** 3 of 7 | **Status:** ✅ Complete  
**Delivered:** 2026-06-07 | **Issues:** #3 | **Blocked by:** — (independent of C2)

**What this delivers**  
`inference-api` and `inference-runtime` are published artifacts. Any JVM project (casehub or Hortora) can depend on `casehub-neocortex-inference-api` and `casehub-neocortex-inference-runtime` and execute ONNX model inference. `inference-inmem` stubs are available for testing without JNI.

**Accountability gaps closed**  
- ArchUnit zero-domain-dep constraint → enforced from this chapter forward

**Layer Impact**

| Layer | Delta |
|---|---|
| L1 SPI Foundation | High |
| L2 Runtime Core | High |

---

#### Chapter 4 — Task Adapters

**Journey:** J1 | **Sequence:** 4 of 7 | **Status:** ✅ Complete  
**Delivered:** 2026-06-07 | **Issues:** #4 | **Blocked by:** C3

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

**Journey:** J1 | **Sequence:** 5 of 7 | **Status:** ✅ Done  
**Issues:** #5 | **Blocked by:** C3, C4

**What this delivers**  
`inference-quarkus` published. Quarkus applications configure ONNX models via `application.properties` and inject them with `@Inference("name")`. `@QuarkusTest` uses `inference-inmem` stubs via `@Alternative` test producers overriding the `@DefaultBean` production producer.

**Accountability gaps closed**  
- Quarkus-native model configuration → CDI-managed model lifecycle

**Layer Impact**

| Layer | Delta |
|---|---|
| L5 Quarkus Integration | High |

---

#### Chapter 6 — Sparse Embeddings (SPLADE)

**Journey:** J1 | **Sequence:** 6 of 7 | **Status:** ✅ Done  
**Issues:** #6 | **Blocked by:** C4 (adapter pattern established)

**What this delivers**  
`inference-splade` published. `SparseEmbedder` produces log-saturated sparse term weight maps from SPLADE models. The sparse leg of casehub-neocortex-rag's hybrid search is now available. Hortora can use `SparseEmbedder` for their Qdrant hybrid search. Candidate for upstream LangChain4j contribution (#1600).

**Accountability gaps closed**  
- Hybrid search sparse leg → SparseEmbedder available

**Layer Impact**

| Layer | Delta |
|---|---|
| L4 Sparse Embeddings | High |

---

#### Chapter 7 — RAG Pipeline

**Journey:** J2 | **Sequence:** 7 of 7 | **Status:** ✅ Complete  
**Issues:** #7 | **Blocked by:** C6 (SparseEmbedder for sparse search leg)

**What this delivers**  
`rag-api`, `rag`, `rag-tika`, and `rag-testing` published. casehub application repos can define named corpora in configuration, ingest documents via `EmbeddingIngestor`, and retrieve grounded context via `CaseRetriever`. Hybrid dense+sparse search operates across tenancy-isolated Qdrant collections. `rag-tika` provides optional Apache Tika document parsing → chunked `ChunkInput`. The fact space prompt compiler in casehub-engine can inject retrieved context into LLM worker prompts.

**Accountability gaps closed**  
- Knowledge-grounded LLM reasoning → CaseRetriever available for engine case steps

**Layer Impact**

| Layer | Delta |
|---|---|
| L4 Sparse Embeddings | Low (consumed, not extended) |
| L6 RAG SPI | High |
| L7 RAG Runtime | High |

---

#### Chapter 8 — Corpus Storage

**Journey:** J3 | **Sequence:** 8 of 9 | **Status:** ✅ Complete  
**Issues:** #18 | **Blocked by:** C7 (RAG pipeline must exist before corpus storage can feed it)

**What this delivers**  
`corpus-api` and `corpus` published. Application repos and Hortora can store, read, and track changes to document corpora via ZIP-backed rolling archives with chain integrity. `CorpusStore` handles writes (append, delete). `CorpusReader` handles reads (read, readVersion, versions, list, exists). `ChangeSource` provides delta tracking (changes since a cursor). `CorpusIntegrity` validates archive chain health. `FlatCorpusStore` provides a filesystem-based alternative. `CompositeCorpusStore` combines ZIP + flat sources. Zero casehub domain dependencies — Hortora-eligible.

**Accountability gaps closed**  
- Document storage for RAG corpora → CorpusStore + CorpusReader available
- Change tracking for incremental ingestion → ChangeSource available

**Layer Impact**

| Layer | Delta |
|---|---|
| L8 Corpus SPI | High |
| L9 Corpus Runtime | High |

---

#### Chapter 9 — Ingestion Bridge

**Journey:** J3 | **Sequence:** 9 of 9 | **Status:** ✅ Complete  
**Issues:** #19 | **Blocked by:** C8 (corpus modules provide ChangeSource + CorpusReader)

**What this delivers**  
`CorpusIngestionService` in `rag/` bridges corpus storage to the RAG pipeline. Polls `ChangeSource.changesSince()`, reads document content via `CorpusReader`, extracts metadata (`MetadataExtractor` SPI with YAML frontmatter default), chunks, embeds (dense + sparse), and pushes to Qdrant via `EmbeddingIngestor`. Handles deletion sync. `CursorStore` SPI persists polling cursors (`FileCursorStore` default, `InMemoryCursorStore` in `rag-testing`). Admin-triggered reconciliation mode compares `ChangeSource.fullScan()` against Qdrant and resyncs. Configuration-driven via `casehub.rag.ingestion.*`.

**Accountability gaps closed**  
- Automated corpus-to-RAG pipeline → documents flow from CorpusStore to Qdrant without manual embedding calls
- Metadata extraction → MetadataExtractor SPI available for custom extractors

**Layer Impact**

| Layer | Delta |
|---|---|
| L6 RAG SPI | Low (MetadataExtractor + CursorStore SPIs added) |
| L7 RAG Runtime | Med (CorpusIngestionService + FileCursorStore + IngestionConfig added) |

---

#### Chapter 10 — Corrective RAG (CRAG)

**Journey:** J4 | **Sequence:** 1 of 2 | **Status:** ✅
**Issues:** #33, #38, #41 | **Blocked by:** C7 (CaseRetriever SPI), C4 (CrossEncoderReranker)

**What this delivers**
`rag-crag` module — a CDI `@Decorator` on `CaseRetriever` that evaluates retrieval quality via `RelevanceEvaluator` SPI, filters INCORRECT chunks, optionally expands search when too few results survive, and fires `RetrievalQuality` CDI events. Default evaluator: `CrossEncoderRelevanceEvaluator` — threshold-based grading reusing the deployed cross-encoder model. Classpath-activated: add `casehub-neocortex-rag-crag` as a compile dependency to enable. `rag-api` enriched with `RelevanceGrade` on `RetrievedChunk`, `RelevanceEvaluator` SPI, and `RetrievalQuality` event type. `rag-testing` gains `InMemoryRelevanceEvaluator` stub.

**Accountability gaps closed**

| Gap | What breaks without it | Closed by |
|---|---|---|
| Silent bad retrieval | Irrelevant chunks reach LLM prompt, degrade output quality | RelevanceEvaluator grades each chunk; INCORRECT filtered |
| No retrieval quality signal | Consumers (AML, clinical) can't assess confidence in retrieved context | Per-chunk `RelevanceGrade` + `RetrievalQuality` CDI event |
| No corrective action | Poor initial retrieval is final | Top-K expansion re-retrieves when too few good results survive |

**Layer Impact**

| Layer | Delta |
|---|---|
| L6 RAG SPI | Low (RelevanceEvaluator SPI, RelevanceGrade, enriched RetrievedChunk, RetrievalQuality event) |
| L7 RAG Runtime | Low (no structural change — convenience constructor handles backward compat) |
| L10 CRAG | High (new module: CorrectiveCaseRetriever decorator, CrossEncoderRelevanceEvaluator, CragBeanProducer, CragConfig) |

---

#### Chapter 11 — Query Expansion

**Journey:** J4 | **Sequence:** 2 of 2 | **Status:** ✅
**Issues:** #32, #42, #43 | **Blocked by:** C7 (CaseRetriever SPI), C10 (CRAG)

**What this delivers**
`rag-expansion` module — a CDI `@Decorator` on `CaseRetriever` and `ReactiveCaseRetriever` that expands queries before retrieval. `QueryExpander` SPI in `rag-api` defines the expansion contract. Three implementations: `LlmQueryExpander` (LLM-generated hypothetical documents for HyDE mode, multi-query support via N hypothetical documents with RRF fusion), `TemplateQueryExpander` (pattern-based expansion), and `StepBackQueryExpander` (step-back prompting — generates abstract reformulation, returns [original, abstract] for multi-query fan-out). New `RetrievalQuery` value type replaces raw `String query` in CaseRetriever/ReactiveCaseRetriever — carries original text plus expanded `searchText()` for dense embedding. Sparse embedding and reranking continue to use original `text()`. Classpath + config activated (`casehub.rag.expansion.enabled=true`). CRAG retrofitted to same activation model (`casehub.rag.crag.enabled=true`). `RrfFusion` utility added to `rag-api` for application-level Reciprocal Rank Fusion (decorator-level multi-query merge).

**Accountability gaps closed**

| Gap | What breaks without it | Closed by |
|---|---|---|
| Vocabulary mismatch | Short user queries miss semantically relevant documents due to embedding distance | HyDE generates hypothetical answer, embedding matches document space |
| Query too specific | Overly specific queries miss conceptually relevant broader content | Step-back prompting generates abstract reformulation |
| No query expansion SPI | Query expansion locked to single strategy | QueryExpander SPI — swap LLM, template, step-back, or custom implementations |
| Single query path | One embedding per query — no multi-query diversity | Multi-query fan-out (N hypothetical documents, step-back [original + abstract]) + RRF merge |
| CRAG always-on | No way to disable CRAG without removing classpath dependency | Config-gated activation for both CRAG and expansion |

**Layer Impact**

| Layer | Delta |
|---|---|
| L6 RAG SPI | Med (RetrievalQuery value type, QueryExpander SPI signature change from single-query to multi-query, CaseRetriever/ReactiveCaseRetriever SPI signature change, RrfFusion utility) |
| L7 RAG Runtime | Med (dense/sparse split — dense uses searchText(), sparse uses text(), HybridCaseRetriever updated) |
| L10 CRAG | Low (activation model retrofit — classpath-only → classpath + config) |
| L11 Query Expansion | High (new module: QueryExpandingCaseRetriever decorator, ReactiveQueryExpandingCaseRetriever, LlmQueryExpander with multi-query, TemplateQueryExpander, StepBackQueryExpander, ExpansionConfig) |

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

| File | Role |
|---|---|
| `inference-api/.../InferenceModel.java` | SPI interface — `run()`, `runBatch()`, `outputSize()`, `close()` |
| `inference-api/.../InferenceInput.java` | Record — `List<String> texts`, factories `of()` and `pair()` |
| `inference-api/.../InferenceOutput.java` | Record — `float[] values` with value-based equality |
| `inference-api/.../InferenceException.java` | Unchecked exception for inference failures |
| `inference-inmem/.../InMemoryInferenceModel.java` | Deterministic stub — `returning()` and `withFunction()` factories |
| `inference-api/.../DependencyConstraintTest.java` | ArchUnit — blocks Quarkus, Spring, LangChain4j, ONNX Runtime, DJL |

#### Key wiring

`InferenceModel` extends `AutoCloseable`. `close()` must not throw — contract specifies silent cleanup. `runBatch()` has a default implementation that delegates to `run()` sequentially; `OnnxInferenceModel` overrides with a true batched implementation. `outputSize()` returns `OptionalInt` — models that don't know their output size at construction time return empty.

`InMemoryInferenceModel.returning(float...)` returns the same values for every input — simplest stub. `InMemoryInferenceModel.withFunction(int, Function<InferenceInput, float[]>)` allows input-dependent responses for adapter tests.

#### Architectural decisions

`InferenceInput` uses `List<String> texts` only — tokenization is the runtime's responsibility, not the caller's. Callers never touch token IDs or attention masks. This keeps the SPI portable across tokenizer versions and model families.

`InferenceOutput` simplified to `float[] values` (design departure from Hortora spec which proposed `Map<String, float[]>` keyed by tensor name). Single-output models are the dominant case; named tensors add complexity for no benefit at the SPI boundary. Task adapters know the output structure of their models.

`InferenceInput` capped at max 2 texts (design departure). NLI requires exactly 2 (premise + hypothesis); single-text tasks require 1. No known task requires 3+ texts in a single input. Enforced by the `InferenceInput` constructor.

`ModelConfig` placed in `inference-runtime` not `inference-api` (design departure). Config carries runtime-specific concerns (thread counts, sequence length) that don't belong in the zero-dep SPI.

#### Pattern introduced
`InferenceModel` SPI — thin contract over any ONNX model; stubs via `inference-inmem`; implementations via `inference-runtime`; typed interpretation via `inference-tasks`.

#### Pattern anchor
`inference-api/src/main/java/io/casehub/inference/InferenceModel.java`, `inference-api/src/main/java/io/casehub/inference/InferenceInput.java`

#### Gotchas
- `Objects.requireNonNull` throws NPE, not IAE — SPI contracts that specify "throws IAE for null" need explicit null checks (GE-20260607-db04c6)
- Sequential try-catch for `close()` with multiple resources — must not let first failure prevent closing remaining resources (GE-20260607-9cef08)

#### Pattern to replicate
ArchUnit `DependencyConstraintTest` per module — blocks Quarkus, Spring, LangChain4j, ONNX Runtime, DJL. Copy this test into every new `inference-*` module. The constraint list in each module should match its tier — `inference-api` blocks everything; `inference-runtime` allows ONNX Runtime and DJL but blocks Quarkus/Spring/LangChain4j.

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

| File | Role |
|---|---|
| `inference-runtime/.../OnnxInferenceModel.java` | `InferenceModel` impl — wraps `OrtSession` + `HuggingFaceTokenizer` |
| `inference-runtime/.../ModelConfig.java` | Record — `modelPath`, `tokenizerPath`, `maxSequenceLength`, thread counts |
| `inference-runtime/.../ModelLoadException.java` | Unchecked exception for model/tokenizer loading failures |

#### Key wiring

`OnnxInferenceModel` constructor takes `ModelConfig` and eagerly loads both the ONNX model (`OrtSession`) and tokenizer (`HuggingFaceTokenizer`). Validation: model file must exist, tokenizer file must exist, model must have at least one input/output node.

`OrtSession` is thread-safe for concurrent inference — do not create a new session per request. One session per `ModelConfig`, loaded at startup, held for process lifetime. In `inference-quarkus`, each model config becomes an `@ApplicationScoped` CDI bean.

`close()` uses sequential try-catch: session closed first, then environment, then tokenizer. Each close is independent — failure of one does not prevent the others.

DJL `HuggingFaceTokenizer` used instead of the HuggingFace Tokenizers JNI directly (design departure from C2). DJL wraps the same Rust JNI layer but provides a cleaner Java API and Maven-published native libs.

#### Architectural decisions

Two JNI layers (ONNX Runtime + DJL Tokenizers) are required rather than one. ONNX Runtime handles model execution; DJL HuggingFace Tokenizers handles tokenization. Alternative (LangChain4j tokenization) rejected — LangChain4j tokenizers are dense-embedding-specific and do not produce token ID + attention mask tensors needed for arbitrary ONNX model input.

`outputSize` auto-detected from the ONNX model's output metadata when the shape is statically known; returned as `OptionalInt.empty()` when the model has dynamic output dimensions (design departure — spec proposed mandatory int).

#### Gotchas
- C2 native image gotchas: `inference-quarkus/src/main/resources/NATIVE-IMAGE-NOTES.md` — 3 entries (GE-20260606-d3cd87, GE-20260606-7bf5dd, GE-20260606-025601)
- C3 runtime gotchas: requireNonNull NPE vs IAE (GE-20260607-db04c6), sequential try-catch close (GE-20260607-9cef08)

#### Pattern to replicate
`OnnxInferenceModel` constructor pattern: validate config → load model → validate model structure → load tokenizer → detect output size. Any failure during construction throws `ModelLoadException` and cleans up already-allocated resources. This eager-validate-or-fail pattern should be replicated in any future `InferenceModel` implementation.

---

#### Layer L3 — Task Adapters

**Participates in chapters:** C4  
**Architectural patterns:** Adapter (wraps InferenceModel; exposes domain types)  
**Issues:** #4 | parent#158  
**Completed:** ✅

#### What it adds

Callers interact with domain types, never raw tensors. `NliClassifier` interprets the `logits` tensor as entailment/neutral/contradiction scores. `TextClassifier` interprets softmax output as label + confidence. `ScalarRegressor` reads a scalar output tensor. `CrossEncoderReranker` ranks candidate texts by relevance to a query. All four wrap any `InferenceModel` — tests inject `inference-inmem` stubs.

#### Key files

| File | Role |
|---|---|
| `inference-tasks/.../NliClassifier.java` | Adapter — convention + explicit-index constructors, softmax, runtime guard |
| `inference-tasks/.../NliLabel.java` | Enum — ENTAILMENT, NEUTRAL, CONTRADICTION |
| `inference-tasks/.../NliResult.java` | Record — entailment/neutral/contradiction + `predicted()` + `scores()` |
| `inference-tasks/.../TextClassifier.java` | Adapter — labels at construction, softmax, `classify()` → `ClassificationResult` |
| `inference-tasks/.../ClassificationResult.java` | Record — label, confidence, scores (Map.copyOf) |
| `inference-tasks/.../ScalarRegressor.java` | Adapter — raw scalar output, `predict()` → float |
| `inference-tasks/.../CrossEncoderReranker.java` | Adapter — `score()` + batch `rerank()` via `runBatch()` |
| `inference-tasks/.../RankedResult.java` | Record — text, score, originalIndex |
| `inference-tasks/.../Softmax.java` | Package-private — numerically stable softmax utility |
| `inference-tasks/.../DependencyConstraintTest.java` | ArchUnit — framework + casehub domain exclusion (new `DescribedPredicate` rule) |

#### Architectural decisions

Each adapter is constructed with an `InferenceModel` dependency (injected or passed directly). Adapters are **not AutoCloseable** — they do not own the model. This makes adapters independently testable without Quarkus or JNI, and prevents double-close bugs when multiple adapters share a model. Quarkus wiring (`@InferenceModel` qualifier) is in L5 — adapters do not know about CDI.

`NliClassifier` has two constructors: convention (HuggingFace standard order 0=contradiction, 1=neutral, 2=entailment) and explicit-index (for non-standard models). `TextClassifier` fallback was rejected for non-standard NLI models — it returns `ClassificationResult`, not `NliResult`, losing the typed three-way distribution.

All adapters validate `model.outputSize()` at construction (fail-fast) and validate `values.length` at runtime (defence-in-depth). Softmax is a package-private utility class shared by NliClassifier and TextClassifier — zero public API surface.

SPLADE output is not an adapter in `inference-tasks` — it is a separate module (`inference-splade`, L4) because its post-processing (log-saturation, sparse map output) is distinct from the classifier/regressor/reranker pattern and because Hortora may want `inference-splade` independently without `inference-tasks`.

#### Gotchas

- Adapter null-arg validation must precede `InferenceInput.of()`/`.pair()` construction — otherwise callers get `NullPointerException` from `Objects.requireNonNull` in the factory instead of `IllegalArgumentException` with an adapter-specific message
- `ClassificationResult` compact constructor must use `Map.copyOf()` — records with Map fields need defensive copies to maintain immutability
- ArchUnit casehub domain exclusion requires `DescribedPredicate.describe()` with a lambda checking `getPackageName()` — the existing `resideInAnyPackage()` pattern cannot express "allow io.casehub.neocortex.inference but block all other io.casehub" (backported in #10)

#### Pattern to replicate

Adapter construction pattern: validate null model → validate `outputSize()` when present → store model. Method pattern: validate null args (IAE with adapter-specific message) → `model.run(InferenceInput.of/pair(...))` → extract `values()` exactly once → validate `values.length` (InferenceException) → interpret. This two-layer validation (construction + runtime) should be replicated in any future adapter including `SparseEmbedder` (C6).

---

#### Layer L4 — Sparse Embeddings (SPLADE)

**Participates in chapters:** C6, C7  
**Architectural patterns:** Adapter (wraps InferenceModel; post-processes to sparse format)  
**Design refs:** `docs/specs/2026-06-03-standalone-rag-retrieval-brief.md` §SPLADE  
**Issues:** #6 | parent#158  
**Completed:** ✅

#### What it adds

Produces sparse term weight maps (`Map<Integer, Float>`) from SPLADE models. Enables the sparse leg of hybrid search — regulatory, clinical, and legal text benefits from SPLADE's lexical precision over dense embeddings. Post-processing: `log(1 + relu(weight))` saturation + threshold filtering (drop weights < 0.01). Output is Qdrant named vector space compatible.

#### Architectural decisions

SPLADE output is `Map<Integer, Float>` not `float[]`. Qdrant named vector spaces store both dense `float[]` (from LangChain4j) and sparse `Map<Integer, Float>` (from `SparseEmbedder`) per document. This hybrid architecture requires Qdrant — pgvector does not natively support named vector spaces for true hybrid search as of 2026-06.

Log-saturation applied before threshold: `log(1 + relu(w))` for each raw weight w. This compresses high-weight terms and prevents dominance by single high-attention tokens. Threshold 0.01 removes noise — empirically established in the SPLADE paper.

Candidate for upstream LangChain4j contribution once stable (langchain4j#1600 was metadata filtering, now closed — a SPLADE-specific upstream issue has not been filed yet).

#### Gotchas

No JNI issues — `SparseEmbedder` is pure Java, depends only on `inference-api`. No native library involvement.

#### Key files

- `inference-splade/src/main/java/io/casehub/inference/splade/SparseEmbedder.java` — single class, embed + embedBatch
- `inference-splade/src/test/java/io/casehub/inference/splade/SparseEmbedderTest.java` — 17 tests
- `inference-splade/src/test/java/io/casehub/inference/splade/DependencyConstraintTest.java` — ArchUnit enforced

#### Pattern to replicate

Same adapter pattern as inference-tasks: constructor takes `InferenceModel`, validates at construction, typed method calls `model.run()` and post-processes. Add ArchUnit `DependencyConstraintTest` with the casehub domain exclusion rule.

---

#### Layer L5 — Quarkus Integration

**Participates in chapters:** C5  
**Architectural patterns:** CDI SPI Registration, `@Qualifier`-based disambiguation  
**Issues:** #5  
**Completed:** ✅

#### What it adds

Quarkus applications declare ONNX models in `application.properties` and inject them with `@Inference("name")`. `InferenceModelProducer` (`@ApplicationScoped @DefaultBean`) creates `OnnxInferenceModel` instances lazily from `@ConfigMapping` properties. `@QuarkusTest` overrides via `@Alternative` test producers returning `InMemoryInferenceModel`.

#### Architectural decisions

`@Inference` qualifier, not `@InferenceModel` — avoids import collision with the `io.casehub.neocortex.inference.InferenceModel` SPI interface. `@Nonbinding String value()` means CDI treats all `@Inference` qualifiers identically; the producer inspects `InjectionPoint` to resolve the specific model name at runtime.

CDI-only, no Quarkus extension (no deployment/runtime split). Full Quarkus extension not needed — JVM mode by design for long-running services. `@DefaultBean` on the production producer means any `@Produces @Inference` method in test code wins automatically.

`reuseForks=false` in surefire — required because the module mixes `@QuarkusTest` (augmented classloader) with plain JUnit tests that load JNI libraries. See GE-20260608-564065.

#### Gotchas

JNI classloader conflict: `@QuarkusTest` loads ONNX Runtime JNI via Quarkus augmented classloader. A plain JUnit test in the same JVM fork tries to reload the same native library via the system classloader — JNI refuses with `UnsatisfiedLinkError: Native Library already loaded in another classloader`. Fix: `reuseForks=false`. Captured in garden: GE-20260608-564065.

#### Key files

- `inference-quarkus/src/main/java/io/casehub/inference/quarkus/Inference.java` — CDI qualifier
- `inference-quarkus/src/main/java/io/casehub/inference/quarkus/InferenceModelConfig.java` — `@ConfigMapping`
- `inference-quarkus/src/main/java/io/casehub/inference/quarkus/InferenceModelProducer.java` — producer + lifecycle

#### Pattern to replicate

For new inference model consumers: inject `@Inference("name") InferenceModel model`. Configure in `application.properties` under `casehub.inference.models.<name>.*`. For tests: provide an `@Alternative @ApplicationScoped` producer class, enable via `QuarkusTestProfile.getEnabledAlternatives()`.

---

#### Layer L6 — RAG SPI

**Participates in chapters:** C7, C9  
**Architectural patterns:** Hexagonal (Ports and Adapters)  
**Issues:** #7, #11, #19 | parent#164  
**Completed:** ✅

#### What it adds

Defines the contract for knowledge corpus management and retrieval within casehub. `EmbeddingIngestor` allows application repos to ingest and manage documents scoped to a tenant corpus. `CaseRetriever` provides the retrieval interface for engine case steps and the typed fact space prompt compiler. Reactive variants (`ReactiveEmbeddingIngestor`, `ReactiveCaseRetriever`) enable consumers on the Vert.x event loop (e.g. casehub-engine's `CaseContextChangedEventHandler`). Mutiny is `provided` scope — same pattern as casehub-ledger-api and casehub-eidos-api. `rag-testing` stubs make both blocking and reactive interfaces usable in `@QuarkusTest` without Qdrant.

#### Architectural decisions

`CorpusRef` (tenantId + corpusName) is mandatory on all SPI calls — no implicit ambient tenancy. This enforces tenancy isolation at the type level, not just the runtime level.

`EmbeddingIngestor` and `CaseRetriever` are separate SPIs. `EmbeddingIngestor` is write-oriented (ingest, delete, list). `CaseRetriever` is read-oriented (retrieve). This separation allows different implementations and different CDI beans for admin vs. runtime contexts.

`RetrievedChunk` carries `sourceDocumentId`, `content`, `relevanceScore`, and `metadata`. No per-chunk `embeddingStrategy` field — after RRF fusion, every result is hybrid. The fused score is what matters for ranking; per-leg provenance is not recoverable from the fused result and would be architecturally misleading.

#### Gotchas

Qdrant `Common.Filter` is not `Points.Filter` — the import is `io.qdrant.client.grpc.Common.Filter`, not the Points variant. Using the wrong one compiles but produces empty results at runtime (GE-20260609-521cca).

#### Pattern to replicate

Reactive SPI co-location in Tier 1 api modules with Mutiny `provided` scope — same pattern as casehub-ledger-api and casehub-eidos-api. `@DefaultBean` bridge wraps blocking delegate with `runSubscriptionOn(workerPool)`. In-memory stubs use `@Alternative @Priority(1) @ApplicationScoped` with no `@IfBuildProperty` gate (no Hibernate Reactive dep).

---

#### Layer L7 — RAG Runtime

**Participates in chapters:** C7, C9  
**Architectural patterns:** Pipeline (ingest → chunk → embed → store; query → embed → retrieve → rerank → assemble)  
**Key protocols:** `flyway-extension-migration-registration.md` (no migrations — Qdrant managed externally)  
**Issues:** #7, #11, #13, #19 | parent#164  
**Completed:** ✅

#### What it adds

Implements `EmbeddingIngestor` and `CaseRetriever` using LangChain4j `EmbeddingModel` (dense embeddings) + `inference-splade` `SparseEmbedder` (sparse) + Qdrant Java client (gRPC) for hybrid search with RRF fusion. Optional `CrossEncoderReranker` for precision-mode top-N. Configurable tenancy isolation — separate collections per tenant (default) or shared collection with payload filtering. `@DefaultBean` bridges (`BlockingToReactiveEmbeddingIngestor`, `BlockingToReactiveCaseRetriever`) wrap blocking implementations with `runSubscriptionOn(workerPool)` as the default reactive path. Native reactive Qdrant implementations (`ReactiveQdrantEmbeddingIngestor`, `ReactiveHybridCaseRetriever`) activate via `casehub.rag.reactive.enabled=true` — use `QdrantFutures.toUni()` (`ListenableFuture` → Mutiny `Uni` via `Futures.addCallback` + `directExecutor`) for natively async Qdrant I/O, with blocking ONNX embedding offloaded to the worker pool. `ReactiveRagBeanProducer` (`@IfBuildProperty` + `@Startup`) produces both; `@DefaultBean` bridges are removed from the bean graph when active (#13).

`langchain4j-qdrant` not used — LangChain4j does not support Qdrant named vector spaces or hybrid search ([langchain4j#4994](https://github.com/langchain4j/langchain4j/issues/4994), open, no timeline). Both dense and sparse legs use the Qdrant Java gRPC client directly for unified architecture. LangChain4j used only for `EmbeddingModel`, `DocumentSplitter`, and Tika parser (in `rag-tika`).

#### Architectural decisions

Qdrant chosen over pgvector for the hybrid leg — pgvector does not natively support named vector spaces for true hybrid search.

RRF (Reciprocal Rank Fusion) used to merge dense and sparse result lists. Scores are not comparable across embedding types; rank is. RRF is parameter-free and well-studied. Weighted combination rejected — requires tuning per domain.

`casehub-platform-api` is the only casehub dependency in `rag` — `CurrentPrincipal` and `TenancyConstants` for tenancy isolation. No engine, ledger, or qhorus types.

#### Gotchas

`langchain4j-qdrant` does not support named vector spaces or hybrid search (langchain4j#4994). Must use the Qdrant Java gRPC client directly for both legs. Qdrant client 1.18.1 returns `ListenableFuture`, not `CompletionStage` — bridged via `QdrantFutures.toUni()` using `Futures.addCallback` + `directExecutor()` (#13 resolved). `memoize().indefinitely()` caches failures (verified from Mutiny `UniMemoizeOp` source) — `ensureCollection` evicts from the memoization map on failure to allow retry.

#### Pattern to replicate

Unified Qdrant gRPC client for both dense and sparse legs — one client, one code path. `TenancyStrategy` enum for configurable collection isolation. `@DefaultBean` bridges for reactive SPIs that wrap blocking delegates on the worker pool. `QdrantFutures.toUni()` for `ListenableFuture` → `Uni` bridging with cancellation propagation. `@IfBuildProperty` on a dedicated producer class gates all produced beans behind a single property. `ConcurrentHashMap<String, Uni<Void>>` + `computeIfAbsent` + `.memoize().indefinitely()` for concurrent-safe lazy collection creation with failure eviction.

---

#### Layer L8 — Corpus SPI

**Participates in chapters:** C8  
**Architectural patterns:** Hexagonal (Ports and Adapters), Three-plane separation (data, delta, ops)  
**Issues:** #18  
**Completed:** ✅

#### What it adds

Defines the contract for document corpus management independent of storage backend. Three-plane separation: `CorpusStore` (data plane — append, delete), `ChangeSource` (delta plane — changes since cursor for incremental sync), `CorpusIntegrity` (ops plane — validate archive health). `CorpusReader` provides read-only access (read, readVersion, versions, list, exists). Reactive variants (`ReactiveCorpusStore`, `ReactiveCorpusReader`, `ReactiveChangeSource`) follow the same Mutiny `provided` scope pattern as `rag-api`. Zero casehub domain dependencies — Hortora-eligible.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No document storage abstraction | Corpus ingestion coupled to filesystem layout | `CorpusStore` + `CorpusReader` SPIs |
| No change tracking for incremental ingestion | Full re-ingestion on every sync | `ChangeSource` with cursor-based delta tracking |
| No integrity verification | Corrupted archives silently return wrong content | `CorpusIntegrity` + `IntegrityReport` |

#### Key files

| File | Role |
|---|---|
| `corpus-api/.../CorpusStore.java` | SPI — append, delete (write-only) |
| `corpus-api/.../CorpusReader.java` | SPI — read, readVersion, versions, list, exists (read-only) |
| `corpus-api/.../ChangeSource.java` | SPI — changesSince(cursor), fullScan() |
| `corpus-api/.../CorpusIntegrity.java` | SPI — check() → IntegrityReport |
| `corpus-api/.../ChangedEntry.java` | Record — path, type |
| `corpus-api/.../ChangeSet.java` | Record — List<ChangedEntry> entries, newCursor |
| `corpus-api/.../VersionInfo.java` | Record — version, timestamp, zipFile |

#### Architectural decisions

Three-plane separation (data/delta/ops) keeps concerns independent — a consumer that only needs change tracking depends on `ChangeSource` alone, not the full `CorpusStore`. `CorpusReader` is separated from `CorpusStore` to enforce read-write separation — the ingestion bridge reads via `CorpusReader` and never writes to the corpus.

`ChangeType` enum (ADDED, MODIFIED, DELETED) — three states, no UPDATE alias. MODIFIED covers content changes; ADDED is first version only.

#### Pattern to replicate

Three-plane SPI separation for any storage module. Reactive variants co-located in the same api module with Mutiny `provided` scope.

---

#### Layer L9 — Corpus Runtime

**Participates in chapters:** C8  
**Architectural patterns:** Rolling Archive (size-threshold rollover), Chain Manifest (ordered archive list)  
**Issues:** #18  
**Completed:** ✅

#### What it adds

Implements all corpus SPIs using Zip4j for ZIP-backed storage. `ZipCorpusStore` manages rolling ZIP archives with size-threshold rollover, chain manifest (`chain.json`), tombstone-based deletion, and compaction (TOMBSTONES_ONLY default, FULL explicit). `FlatCorpusStore` provides filesystem-based storage for development and migration. `CompositeCorpusStore` combines ZIP + flat sources. `ZipChangeSource` tracks changes via central directory scanning; `CompositeChangeSource` merges changes from multiple sources. `ZipIntegrityChecker` validates chain integrity. `CorpusMigrator` enables onboarding existing document collections.

#### Key files

| File | Role |
|---|---|
| `corpus/.../ZipCorpusStore.java` | Rolling ZIP archives, chain manifest, compaction |
| `corpus/.../FlatCorpusStore.java` | Filesystem-based alternative |
| `corpus/.../CompositeCorpusStore.java` | ZIP + flat combined view |
| `corpus/.../ZipChangeSource.java` | Delta tracking from ZIP central directories |
| `corpus/.../CompositeChangeSource.java` | Merged changes from multiple sources |
| `corpus/.../ZipIntegrityChecker.java` | Chain integrity validation |
| `corpus/.../ChainManifest.java` | Ordered archive list with UUIDs |
| `corpus/.../Compactor.java` | Tombstone removal and full compaction |
| `corpus/.../CorpusMigrator.java` | Onboarding existing document collections |

#### Architectural decisions

Rolling archives with size threshold — a single ZIP grows until the threshold, then a new archive is created. Chain manifest (`chain.json`) records the ordered list of archives with UUIDs. Compaction replaces archive UUIDs to invalidate stale references.

Tombstone-based deletion — deleted entries are marked, not physically removed. This preserves change history for `ChangeSource` until compaction runs. TOMBSTONES_ONLY compaction removes tombstones; FULL compaction rebuilds archives without gaps.

`_` prefix reserved for internal paths (`_chain/meta.json`) — user paths starting with `_` are rejected.

#### Gotchas

Zip4j does not support in-place entry removal — compaction must rewrite the entire archive. This is acceptable for the expected corpus sizes (hundreds to low thousands of documents per archive).

#### Pattern to replicate

Rolling archive pattern with chain manifest for any append-heavy storage where individual files are small relative to the archive. Tombstone + compaction for soft-delete semantics with change tracking.

---

#### Layer L10 — CRAG

**Participates in chapters:** C10
**Architectural patterns:** Decorator (CDI @Decorator), SPI consumer (RelevanceEvaluator)
**Issues:** #33, #38, #41
**Completed:** ✅

#### What it adds

Classpath + config activated corrective retrieval — when `casehub-neocortex-rag-crag` is on the classpath and `casehub.rag.crag.enabled=true`, `CorrectiveCaseRetriever` and `ReactiveCorrectiveCaseRetriever` intercept all `CaseRetriever.retrieve()` and `ReactiveCaseRetriever.retrieve()` calls via CDI `@Decorator`. Evaluates each retrieved chunk as CORRECT/AMBIGUOUS/INCORRECT, filters INCORRECT, expands search when quality filtering leaves too few results, and fires `RetrievalQuality` CDI events for observability. Already-graded guard prevents double CRAG application when the blocking-to-reactive bridge is active — uses `RelevanceGrade.UNGRADED` as the idempotency signal. Shared `CragEvaluationLogic` helper provides pure functions consumed by both decorators.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Silent bad retrieval | Irrelevant chunks reach LLM prompt | RelevanceEvaluator grades; INCORRECT filtered |
| No quality signal | Consumers blind to retrieval confidence | Per-chunk grade + RetrievalQuality event |

#### Key files

| File | Role |
|---|---|
| `rag-crag/.../CorrectiveCaseRetriever.java` | @Decorator — evaluate, filter, expand, grade-sort, quality event |
| `rag-crag/.../CrossEncoderRelevanceEvaluator.java` | Default RelevanceEvaluator — cross-encoder threshold grading |
| `rag-crag/.../CragBeanProducer.java` | Produces RelevanceEvaluator from CrossEncoderReranker + config |
| `rag-crag/.../CragConfig.java` | @ConfigMapping — thresholds + expansion multiplier |
| `rag-crag/.../ReactiveCorrectiveCaseRetriever.java` | @Decorator on ReactiveCaseRetriever — Uni chains, worker thread offload, fireAsync |
| `rag-crag/.../CragEvaluationLogic.java` | Shared pure functions — grading, filtering, dedup, sort, quality event construction |

#### Architectural decisions

CDI `@Decorator` chosen over `@Alternative` to avoid priority collisions with test stubs and circular dependency in CDI resolution. Constructor injection enables pure Java unit tests without a CDI container. Expansion triggers only when quality filtering removed chunks (`surviving.size() < maxResults && incorrect > 0`), not when the corpus is simply small. Grade-first truncation ordering ensures CORRECT chunks survive `limit()` over AMBIGUOUS.

#### Gotchas

Float-to-double comparison: `score >= correctThreshold` fails at boundaries when `score` is `float` and `correctThreshold` is `double` — `0.7f` promotes to `0.699999988...`. Fix: cast thresholds to `float` before comparison.

#### Pattern to replicate

CDI `@Decorator` with constructor injection for classpath-activated pipeline stages. `@Decorator @Priority(N)` + `@Inject` constructor with `@Delegate @Any` — enables both CDI interception and pure Java unit testing. Stub the `Event<T>` interface in tests to capture fired events.

---

#### Layer L11 — Query Expansion

**Participates in chapters:** C11
**Architectural patterns:** Decorator (CDI @Decorator), SPI consumer (QueryExpander), Multi-query with RRF fusion
**Issues:** #32, #42, #43
**Completed:** ✅

#### What it adds

Config-gated query expansion — when `casehub-neocortex-rag-expansion` is on the classpath and `casehub.rag.expansion.enabled=true`, `QueryExpandingCaseRetriever` and `ReactiveQueryExpandingCaseRetriever` intercept all `CaseRetriever.retrieve()` and `ReactiveCaseRetriever.retrieve()` calls via CDI `@Decorator`. Expands the original query through `QueryExpander` SPI before passing to the delegate retriever. Dense embedding uses the expanded `searchText()`; sparse embedding and reranking use the original `text()`. Three bundled expanders: `LlmQueryExpander` (LLM-generated hypothetical documents for HyDE mode, multi-query support via N hypothetical documents with RRF fusion via `casehub.rag.expansion.hypothetical-count`), `TemplateQueryExpander` (pattern-based string expansion), and `StepBackQueryExpander` (step-back prompting — abstract reformulation, returns [original, abstract] for multi-query fan-out). Each bean carries its own `@IfBuildProperty` annotation — decorators gated by `casehub.rag.expansion.enabled`, expanders gated by `casehub.rag.expansion.mode`. `RrfFusion` utility in `rag-api` provides application-level Reciprocal Rank Fusion for decorator-level multi-query merging.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Vocabulary mismatch | Short queries produce poor dense embeddings | HyDE-expanded searchText() aligns with document embedding space |
| Query too specific | Overly specific queries miss broader conceptually relevant content | StepBackQueryExpander generates abstract reformulation |
| Single expansion strategy | No way to swap query expansion approach | QueryExpander SPI with pluggable implementations (LLM, template, step-back) |
| Single query path | One embedding per query — no diversity | Multi-query fan-out (N hypothetical docs, step-back [original + abstract]) + RRF merge |

#### Key files

| File | Role |
|---|---|
| `rag-expansion/.../QueryExpandingCaseRetriever.java` | @Decorator — expand query via QueryExpander (multi-query fan-out), RRF merge, pass RetrievalQuery to delegate |
| `rag-expansion/.../ReactiveQueryExpandingCaseRetriever.java` | @Decorator on ReactiveCaseRetriever — Uni chains, worker thread offload, reactive RRF merge |
| `rag-expansion/.../LlmQueryExpander.java` | QueryExpander impl — LLM generates N hypothetical documents (configurable via hypothetical-count) |
| `rag-expansion/.../TemplateQueryExpander.java` | QueryExpander impl — pattern-based string expansion |
| `rag-expansion/.../StepBackQueryExpander.java` | QueryExpander impl — step-back prompting, returns [original, abstract] for multi-query |
| `rag-expansion/.../ExpansionConfig.java` | @ConfigMapping — enabled flag, mode selection, hypothetical-count, step-back-prompt-template |
| `rag-api/.../RetrievalQuery.java` | Value type — text() + searchText(), replaces raw String in CaseRetriever SPI |
| `rag-api/.../QueryExpander.java` | SPI — expand(RetrievalQuery query) → List<RetrievalQuery> (multi-query support) |
| `rag-api/.../RrfFusion.java` | Utility — merge(List<List<RetrievedChunk>> results) → List<RetrievedChunk> via Reciprocal Rank Fusion |

#### Architectural decisions

CDI `@Decorator` chosen (same pattern as CRAG L10) for consistency and to avoid priority collisions. Config-gated via `@IfBuildProperty` — classpath alone does not activate the decorator. This same activation model was retrofitted to CRAG. `RetrievalQuery` carries both original `text()` and expanded `searchText()` — dense embedding benefits from expansion while sparse embedding preserves lexical precision of the original query. `QueryExpander` changed from single-query (`expand(RetrievalQuery) → RetrievalQuery`) to multi-query (`expand(RetrievalQuery) → List<RetrievalQuery>`) to enable step-back and multi-HyDE patterns. Decorator fans out, retrieves per expanded query, and RRF-merges results before returning to the caller.

#### Gotchas

Decorator priority ordering: Query expansion must run before CRAG in the decorator chain — expansion transforms the query, then CRAG evaluates retrieval quality. `@Priority` values must reflect this ordering.

Multi-query RRF: Fan-out retrieval multiplies Qdrant queries by N (hypothetical-count or step-back mode [2]). For large N, consider latency impact — each query hits Qdrant independently before RRF merge.

#### Pattern to replicate

Config-gated CDI decorator with `@IfBuildProperty` — same pattern for any future retrieval pipeline stage. `RetrievalQuery` dual-text pattern (original + expanded) for any transformation that should affect some embedding strategies but not others. Multi-query fan-out + RRF merge pattern for retrieval diversity — replicate for any future multi-path retrieval stage (e.g., multi-model ensemble, multi-corpus fusion).

---

## §10 Architectural Decisions

*(decisions not captured inline in layer or chapter entries)*

**AD-001 — Single repo for inference-* and rag-*** (2026-06-04)  
inference-* and rag-* are in the same repo (`casehubio/neocortex`). Rationale: rag-* uses `inference-splade` and `CrossEncoderReranker` — they are commonly deployed together in casehub. Hortora takes both `inference-*` and `rag-*` artifacts (#35). Tenancy enforcement is optional for single-tenant consumers (#36). Can be split into separate repos later if Hortora's dependency footprint becomes a concern. Cost of splitting: low (Maven multi-module → separate projects). Cost of premature splitting: one more repo to maintain before any code ships.

**AD-002 — LangChain4j for dense RAG, not a custom implementation** (2026-06-04)  
LangChain4j provides Tika document parsing, chunking, `OnnxEmbeddingModel` (dense ONNX embeddings), and 30+ vector store integrations including Qdrant. Building a parallel implementation would duplicate ~80% of what LangChain4j already does. casehub already uses langchain4j-core (in casehub-eidos). The remaining gap (SPLADE, NLI, classification) is precisely what `casehub-neocortex` adds.

---

## §11 Quality Requirements

| Quality goal | Scenario | Measure |
|---|---|---|
| Domain isolation | Hortora builds against `inference-*` without casehub on classpath | ArchUnit: zero casehub/Quarkus/LangChain4j deps in inference-api, runtime, tasks, splade |
| Testability | All task adapters and rag pipeline unit-testable | 100% test coverage possible without ONNX Runtime native libs via `inference-inmem` |
| Local inference | Hallucination detection runs without API call | `NliClassifier.classify()` returns in <100ms for standard NLI model |
| Tenancy isolation | Tenant A cannot retrieve Tenant B's corpus when `CurrentPrincipal` is provided | Configurable TenancyStrategy + `TenantGuard`; enforced when `CurrentPrincipal` bean is present |

---

## §12 Risks and Technical Debt

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| ~~ONNX Runtime JNI fails in Quarkus native image~~ | ~~Medium~~ | ~~High~~ | ✅ Resolved — C2 prototype passed; both JNI layers work in native image on macOS ARM |
| ~~HuggingFace Tokenizers JNI fails in native image~~ | ~~Medium~~ | ~~High~~ | ✅ Resolved — validated together with ONNX Runtime in C2 |
| LangChain4j #4994 (Qdrant hybrid) ships | Low | Low | Unified Qdrant gRPC client used for both legs — no migration needed; evaluate langchain4j-qdrant adoption opportunistically if it simplifies code |
| Hortora's usage pattern conflicts with SPI design | Low | Medium | rag-* consumption enabled (#35); tenancy optionality shipped (#36); Hortora adoption pending |
| ArchUnit rule drift | Low | High | ArchUnit tests run in CI on every build; fail-fast |

**Technical debt:**
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
| Native image gate | The C2 prototype that validated both JNI layers work in a Quarkus native image binary on macOS ARM. Gate passed; service deploys in JVM mode by design. Reachability metadata retained for downstream native consumers. |
