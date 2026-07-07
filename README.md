# casehub-neocortex

[![Open PRs](https://img.shields.io/github/issues-pr/casehubio/neocortex)](https://github.com/casehubio/neocortex/pulls)

[![casehub-neocortex](https://github.com/casehubio/neocortex/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/neocortex/actions/workflows/publish.yml)

Three capabilities for the [casehubio](https://github.com/casehubio) platform: local ONNX inference, hybrid RAG retrieval, and case-based reasoning. Each can be used independently; together they form the platform's AI reasoning layer.

---

## Why This Exists

### The inference gap

LangChain4j gives you dense embeddings, document parsing, and RAG pipelines. It does not give you the ability to run arbitrary ONNX models for NLI, classification, regression, sparse retrieval, or cross-encoder reranking. In regulated deployments (clinical, AML, financial), data cannot leave the tenant's infrastructure — running models locally is not optional, it is a compliance requirement.

### The retrieval quality gap

Dense vector search finds semantically similar text. That is necessary but not sufficient for regulated domains where specific terminology must appear in results, not merely something semantically related. Hybrid search (dense + sparse + BM25) with RRF fusion, corrective grading, and query expansion closes that gap.

### The reasoning gap

RAG finds *documents* relevant to a query. CBR finds *past decisions* structurally comparable to a current situation. A RAG system retrieves protocol text about adverse events; a CBR system retrieves the five most similar adverse events your team has already handled and what they did about them. Both are needed; neither substitutes for the other.

---

## inference-* — local ONNX model execution

All models run in-process via ONNX Runtime JVM. No API calls, no Python, no external services. The `inference-*` modules carry zero casehub, Quarkus, Spring, or LangChain4j dependencies — ArchUnit-enforced. Shared with [Hortora](https://github.com/Hortora/spec).

| Task | Class | What it does | Platform use |
|------|-------|-------------|-------------|
| Hallucination detection | `NliClassifier` | Scores LLM output for faithfulness against input facts: entailment, neutral, or contradiction | Engine observability — flags outputs that contradict facts before they enter the typed fact space |
| Action risk classification | `TextClassifier` | Classifies agent output for human oversight vs autonomous routing | OpenClaw — replaces the always-AUTONOMOUS stub with per-deployment ONNX classifiers |
| Confidence estimation | `ScalarRegressor` | Estimates epistemic confidence from agent output history | Eidos — dynamic `epistemicDomains` values that improve routing over time |
| Sparse embeddings | `SparseEmbedder` | SPLADE sparse term weight maps for lexical-precision retrieval | RAG sparse search leg; shared with Hortora |
| Cross-encoder reranking | `CrossEncoderReranker` | Joint query+candidate relevance scoring, more accurate than dot-product | RAG precision mode; Hortora human-facing retrieval UI |

---

## rag-* — hybrid knowledge retrieval

Tenancy-isolated, configurable retrieval pipeline built on LangChain4j and Qdrant. Exposes two SPIs: `EmbeddingIngestor` (ingest documents) and `CaseRetriever` (retrieve context). Both have reactive variants for Vert.x event loop consumers.

### Retrieval pipeline

The retrieval pipeline composes through CDI decorators — each capability is a separate module activated by classpath + config, layered via `@Priority`:

```
Query → Expansion Decorator → CRAG Decorator → Tracking Decorator → HybridCaseRetriever → Qdrant
```

| Layer | Module | What it does |
|-------|--------|-------------|
| **Query expansion** | `rag-expansion` | Pre-retrieval query transformation. HyDE (hypothetical documents), step-back prompting, template expansion. Decorator always includes the original query as a safety net alongside expansions. |
| **Corrective RAG** | `rag-crag` | Post-retrieval quality grading. `RelevanceEvaluator` scores each chunk; INCORRECT chunks are filtered; search expands if quality is poor. Cross-encoder default evaluator. |
| **Retrieval tracking** | `rag-tracking` | Records retrieval events for analysis. SQLite + HikariCP WAL + Flyway. Fires `RetrievalRecorded` CDI events. |
| **Hybrid retrieval** | `rag` | Dense + sparse + BM25 + ColBERT fusion via Qdrant. Three fusion strategies (RRF, DBSF, CC). Per-leg embedding separation: dense uses expanded text, sparse/ColBERT use original vocabulary. |

### Per-leg embedding separation

When query expansion is active, each retrieval leg gets the optimal input:

| Leg | Text used | Why |
|-----|-----------|-----|
| Dense | Expanded (`searchText()`) | HyDE bridges the query-document semantic gap |
| Sparse (SPLADE) | Original (`text()`) | Exact vocabulary — hypothetical terms pollute term weights |
| ColBERT | Original (`text()`) | MaxSim reranker — shorter original query has better signal-to-noise |
| BM25 | Original (`text()`) | Pure lexical matching |

### Corpus ingestion

`CorpusIngestionService` bridges corpus modules to RAG — polls `ChangeSource`, reads via `CorpusReader`, extracts metadata, chunks, and pushes to Qdrant. Event-driven for filesystem corpora, scheduled polling for ZIP-based archives.

---

## memory-* — case-based reasoning and agent memory

Two SPI families serving different needs:

### CaseMemoryStore — queryable agent memory

Permission-aware, persistent memory for agents. Backends: in-memory, JPA (PostgreSQL + FTS), SQLite (FTS5), Mem0 (vector embeddings), Graphiti (temporal knowledge graph). `CaseEnrichmentStep` SPI enables pre-store transformation pipelines.

### CbrCaseMemoryStore — structured similarity search

Feature-vector similarity search over past cases. Three CBR paradigms:

| Type | What it captures | Routing signal |
|------|-----------------|---------------|
| **Textual** | NL problem + solution | "Similar-sounding cases went to worker X" |
| **Feature-Vector** | Structured categorical/numeric/text features | "Structurally comparable cases with these features went to X with Y% success" |
| **Plan-Based** | Features + ordered execution traces | "Similar situations used this step sequence, 80% won" |

**Why three types:** each captures a different level of knowledge. Textual CBR is a starting point when no structured features exist. Feature-Vector CBR is where most applications live — it gives explainable, traceable similarity. Plan-Based CBR captures *how* the case was executed, not just the outcome, enabling CHEF-style case-based planning where the retrieved plan is the reusable knowledge.

### Similarity scoring

`CbrSimilarityScorer` computes weighted composite similarity with three-level precedence per field:

1. **Caller override** — `LocalSimilarityFunction` at query time
2. **Schema spec** — `SimilaritySpec` attached to the `FeatureField` (categorical tables, Gaussian/step/exponential decay)
3. **Type default** — exact match for categorical, Gaussian decay for numeric

Semantic text fields use `EmbeddingTextSimilarity` (`memory-cbr-embedding`) for cosine similarity via `EmbeddingModel`. The Qdrant backend runs two-pass retrieval: payload filters + vector first, then batch precompute for semantic text scoring.

- [CBR Types — what they are and when to use them](docs/cbr/cbr-types.md)
- [CBR Integration Guide — SPI, schemas, scoring, backends](docs/cbr/README.md)
- [casehubio/parent#227](https://github.com/casehubio/parent/issues/227) — CBR as a platform capability

---

## Module structure

| Module | Purpose |
|--------|---------|
| **Inference** | |
| `inference-api/` | `InferenceModel` SPI, `MultiModalEmbedder`, `MultiModalEmbedding`, value types. Zero deps. |
| `inference-runtime/` | ONNX Runtime JVM + HuggingFace Tokenizers JNI. Session management. |
| `inference-tasks/` | `NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker` |
| `inference-splade/` | `SparseEmbedder` — SPLADE sparse embeddings |
| `inference-inmem/` | Deterministic stubs. No JNI, safe in all test contexts. |
| `inference-quarkus/` | CDI wiring, `@InferenceModel` qualifier, Dev Services |
| **RAG** | |
| `rag-api/` | `EmbeddingIngestor`, `CaseRetriever`, `QueryExpander`, `RetrievalTracker`, `FusionStrategy`, value types. Mutiny provided. |
| `rag/` | Qdrant hybrid retrieval, per-leg embedding, corpus ingestion bridge, `MultiModalEmbedderProducer` |
| `rag-tika/` | Apache Tika document parsing |
| `rag-testing/` | In-memory stubs for both blocking and reactive SPIs |
| `rag-crag/` | Corrective RAG — `RelevanceEvaluator` grading, INCORRECT chunk filtering, search expansion |
| `rag-expansion/` | Query expansion — HyDE, step-back, template, multi-query RRF. Original query always included. |
| `rag-tracking/` | Retrieval event recording. SQLite + HikariCP. `RetrievalRecorded` CDI events. |
| **Corpus** | |
| `corpus-api/` | `CorpusStore`, `CorpusReader`, `ChangeSource`, `CorpusIntegrity` SPIs. Zero deps. |
| `corpus/` | Zip4j rolling archives, chain manifest, compaction |
| **Memory — Agent** | |
| `memory-api/` | `CaseMemoryStore`, `ReactiveCaseMemoryStore`, `CaseEnrichmentStep`, `MemoryCapability` |
| `memory/` | `CaseEnrichmentDecorator`, reactive bridge |
| `memory-inmem/` | Volatile ConcurrentHashMap. `discoverTenants` support. |
| `memory-jpa/` | PostgreSQL + Flyway + FTS via `websearch_to_tsquery` |
| `memory-sqlite/` | SQLite + HikariCP WAL + FTS5 |
| `memory-mem0/` | Mem0 REST + vector embeddings |
| `memory-graphiti/` | Graphiti REST temporal knowledge graph |
| **Memory — CBR** | |
| `memory-api/` | `CbrCaseMemoryStore`, `CbrCase` hierarchy, `CbrQuery`, `CbrFeatureSchema`, `FeatureField` (sealed), `SimilaritySpec` (sealed), `CbrSimilarityScorer`, `LocalSimilarityFunction` |
| `memory/` | `NoOpCbrCaseMemoryStore` default, `BlockingToReactiveCbrBridge` |
| `memory-cbr-inmem/` | In-memory stub — categorical exact match, scorer-based ranking |
| `memory-cbr-embedding/` | `EmbeddingTextSimilarity` — cosine similarity for semantic text fields |
| `memory-qdrant/` | Qdrant payload filters + dense vector + two-pass semantic text. Reconciliation service. |
| `memory-testing/` | `CbrCaseMemoryStoreContractTest` — 37-test contract suite |
| **Examples** | |
| `examples/example-text-analysis/` | NLI, classification, scoring, reranking, SPLADE demos |
| `examples/example-rag-pipeline/` | Corpus ingestion + hybrid search (requires Qdrant) |
| `examples/example-cbr/` | Six-domain CBR: AML, clinical, DevTown, engine, life, IoT |

---

## Relationship to LangChain4j

| Capability | Owner |
|---|---|
| Dense embeddings, document parsing, chunking, RAG pipeline, vector stores | LangChain4j |
| Sparse embeddings (SPLADE), NLI, classification, regression, cross-encoder | `inference-*` |
| Hybrid search, per-leg embedding, query expansion, CRAG, tracking, tenancy | `rag-*` |
| Case-based reasoning, structured similarity, feature schemas, scoring | `memory-*` (CBR) |

The inference modules sit below LangChain4j (raw model execution). The RAG modules sit above it (pipeline composition). CBR is orthogonal — structured decision retrieval, not document retrieval.

---

## Shared with Hortora

`inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, `inference-inmem`. Hortora wires its own RAG independently. The `rag-*` and `memory-*` modules are casehub-specific.

---

## Status

All core modules are complete and published. Native image gate (C2) passed — ONNX Runtime JNI and HuggingFace Tokenizers JNI work in Quarkus native image on macOS ARM.

| Epic | Title | Status |
|---|---|---|
| [#1](https://github.com/casehubio/neocortex/issues/1) | Scaffold | ✅ |
| [#2](https://github.com/casehubio/neocortex/issues/2) | Native image prototype | ✅ |
| [#3](https://github.com/casehubio/neocortex/issues/3) | SPI Foundation + Runtime Core | ✅ |
| [#4](https://github.com/casehubio/neocortex/issues/4) | Task adapters | ✅ |
| [#5](https://github.com/casehubio/neocortex/issues/5) | Quarkus integration | ✅ |
| [#6](https://github.com/casehubio/neocortex/issues/6) | SPLADE | ✅ |
| [#7](https://github.com/casehubio/neocortex/issues/7) | RAG pipeline | ✅ |
| [#18](https://github.com/casehubio/neocortex/issues/18) | Corpus storage (ZIP-backed) | ✅ |
| [#19](https://github.com/casehubio/neocortex/issues/19) | Corpus ingestion bridge | ✅ |
| [#24](https://github.com/casehubio/neocortex/issues/24) | Examples project | ✅ |
| [#20](https://github.com/casehubio/neocortex/issues/20) | CBR retrieval architecture | ✅ |
| [#68](https://github.com/casehubio/neocortex/issues/68) | CBR production readiness | ✅ |
| [#81](https://github.com/casehubio/neocortex/issues/81) | CBR roadmap — phased capability delivery | 🔧 |

---

## Documentation

- [Platform Architecture](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md)
- [Deep Dive](https://github.com/casehubio/parent/blob/main/docs/repos/casehub-neocortex.md)
- [Architecture & Delivery Plan](https://github.com/casehubio/neocortex/blob/main/ARC42STORIES.MD)
- [AI Fusion Brief](https://github.com/casehubio/parent/blob/main/docs/specs/2026-06-03-ai-fusion-hybrid-fact-space.md)
- [ONNX Inference Brief](https://github.com/casehubio/parent/blob/main/docs/specs/2026-06-03-standalone-rag-retrieval-brief.md)

## Tracking

- [casehubio/parent#158](https://github.com/casehubio/parent/issues/158) — casehubio/neocortex (onnx inference)
- [casehubio/parent#164](https://github.com/casehubio/parent/issues/164) — casehub-neocortex-rag (LangChain4j RAG integration)
- [casehubio/parent#227](https://github.com/casehubio/parent/issues/227) — CBR as a platform capability
- [Hortora/spec#15](https://github.com/Hortora/spec/issues/15) — Hortora alignment
