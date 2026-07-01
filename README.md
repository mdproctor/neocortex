# casehub-neocortex

[![Open PRs](https://img.shields.io/github/issues-pr/casehubio/neocortex)](https://github.com/casehubio/neocortex/pulls)

[![casehub-neocortex](https://github.com/casehubio/neocortex/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/neocortex/actions/workflows/publish.yml)

Local ONNX text inference and LangChain4j RAG wiring for the [casehubio](https://github.com/casehubio) platform. Two module sets: `inference-*` covers the scoring and classification tasks LangChain4j doesn't handle; `rag-*` wires the retrieval pipeline with casehub tenancy and hybrid dense+sparse search.

The `inference-*` modules carry zero casehub, Quarkus, Spring, or LangChain4j dependencies — ArchUnit-enforced from day one. They are shared with [Hortora](https://github.com/Hortora/spec), which uses them independently in a different stack.

---

## Why this is needed

LangChain4j gives you dense embeddings, document parsing, chunking, and retrieval. What it doesn't give you is the ability to run arbitrary pre-trained ONNX models for scoring, classification, and sparse retrieval — the things the platform needs to reason about agent behaviour without making external API calls. In regulated deployments (clinical, AML, financial), data cannot leave the tenant's infrastructure, so running models locally isn't optional.

---

## inference-* — neural scoring in casehub

All models run in-process via ONNX Runtime JVM. No API call, no Python.

### Hallucination detection — `NliClassifier`

LLM agents assert things not supported by the facts they were given. Before an LLM worker's output enters the typed fact space — the shared epistemic workspace the AI Fusion architecture uses — an NLI model scores the output for faithfulness against the input facts: entailment, neutral, or contradiction. Outputs that contradict the input facts are flagged before they propagate downstream to Drools rules or other agents.

**Lands in:** `casehub-engine` observability module.

### Action risk classification — `TextClassifier`

`casehub-openclaw` provisions OpenClaw agents as casehub workers. The `ActionRiskClassifier` SPI decides whether an agent's output proceeds autonomously or routes to human oversight. The current implementation is a stub — always autonomous. A real implementation is a per-deployment ONNX classifier trained on the organisation's own escalation decisions: fast, in-process, deterministic.

**Lands in:** `casehub-openclaw`, replacing the always-AUTONOMOUS stub.

### Epistemic confidence estimation — `ScalarRegressor`

`casehub-eidos` lets agents declare domain confidence statically: `{"java": 0.95, "rust": 0.42}`. A regression model trained on agent output history can estimate actual confidence dynamically. That estimate feeds into `CapabilityHealth.probe()`, so the engine's routing decisions improve over time rather than relying on declarations that were accurate at registration and wrong six months later.

**Lands in:** `casehub-eidos` — dynamic `epistemicDomains` values.

### SPLADE sparse embeddings — `SparseEmbedder`

Dense embeddings capture semantic similarity but dilute the weight of specific regulatory and clinical terms. SPLADE produces sparse term weight maps — only vocabulary tokens with meaningful weight are non-zero — giving the lexical precision that dense vectors sacrifice. When a query needs to find `Art. 22 GDPR` or `ICH E6` rather than something semantically similar to those terms, sparse retrieval finds it; dense retrieval may not.

`SparseEmbedder` output (`Map<Integer, Float>`) goes directly into Qdrant named vector spaces alongside dense vectors. Retrieval fuses both using Reciprocal Rank Fusion.

**Lands in:** `casehub-neocortex-rag` sparse search leg. Also used directly by Hortora.

### Cross-encoder reranking — `CrossEncoderReranker`

Initial retrieval (top-20 candidates) ranks by vector similarity. A cross-encoder jointly encodes each query+candidate pair and scores relevance more accurately than dot-product similarity. The top-N after reranking are what get injected into the LLM prompt. Optional — adds latency, use when retrieval accuracy matters more than speed.

**Lands in:** `casehub-neocortex-rag` precision mode. Also used directly by Hortora for human-facing retrieval UI.

---

## rag-* — knowledge retrieval for case steps

### EmbeddingIngestor — document ingestion

Application repos manage named, tenancy-scoped document corpora. A corpus is a collection of documents relevant to a domain: SAR typologies for AML investigations, clinical trial protocols, coding standards for devtown. `EmbeddingIngestor` handles ingest (Apache Tika extracts text from any format), chunking, dual embedding — dense via LangChain4j `OnnxEmbeddingModel`, sparse via `SparseEmbedder` — and Qdrant storage.

Every call requires a `CorpusRef` carrying the tenant ID. Cross-tenant access is blocked at the SPI boundary.

### CaseRetriever — retrieval for case steps and the fact space

`casehub-engine`'s fact space prompt compiler uses `CaseRetriever` to ground LLM workers before dispatch. When a clinical trial case reaches a step requiring protocol interpretation, the LLM worker receives the relevant protocol excerpts retrieved from the clinical corpus alongside the case context — it reasons against actual documents, not just training data.

Retrieval runs both a dense query (semantic similarity) and a sparse query (lexical precision), fuses results via RRF, then optionally reranks with `CrossEncoderReranker`. The sparse leg is what makes this usable in regulated domains where specific terminology must appear in the results, not merely something semantically related to it.

---

## Module structure

| Module | Artifact | Type | Purpose |
|---|---|---|---|
| `inference-api/` | `casehub-neocortex-inference-api` | Pure Java, zero deps | `InferenceModel` SPI, `InferenceInput`, `InferenceOutput`, `ModelConfig` |
| `inference-runtime/` | `casehub-neocortex-inference-runtime` | ONNX Runtime + HF Tokenizers JNI | Session management, tokenization |
| `inference-tasks/` | `casehub-neocortex-inference-tasks` | Pure Java | `NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker` |
| `inference-splade/` | `casehub-neocortex-inference-splade` | Pure Java | `SparseEmbedder` — log-saturation SPLADE, `Map<Integer, Float>` output |
| `inference-inmem/` | `casehub-neocortex-inference-inmem` | Pure Java | Deterministic stubs — no JNI, safe in all test contexts |
| `inference-quarkus/` | `casehub-neocortex-inference-quarkus` | Quarkus | `@InferenceModel` qualifier, CDI model lifecycle |
| `rag-api/` | `casehub-neocortex-rag-api` | Pure Java, zero deps | `EmbeddingIngestor`, `CaseRetriever`, `MetadataExtractor`, `CursorStore`, `CorpusRef` |
| `rag/` | `casehub-neocortex-rag` | Quarkus + LangChain4j | Qdrant, hybrid RRF, tenancy isolation, corpus ingestion bridge |
| `rag-tika/` | `casehub-neocortex-rag-tika` | LangChain4j + Tika | Apache Tika document parsing → chunked `ChunkInput` |
| `rag-testing/` | `casehub-neocortex-rag-testing` | Pure Java | In-memory stubs — no Qdrant in `@QuarkusTest` |
| `corpus-api/` | `casehub-neocortex-corpus-api` | Pure Java, zero deps | `CorpusStore`, `CorpusReader`, `ChangeSource`, `CorpusIntegrity` SPIs |
| `corpus/` | `casehub-neocortex-corpus` | Zip4j | ZIP-backed rolling archives, chain manifest, compaction |
| `examples/example-text-analysis/` | `casehub-neocortex-example-text-analysis` | Pure Java | Standalone demos: NLI, classification, scoring, reranking, SPLADE |
| `examples/example-rag-pipeline/` | `casehub-neocortex-example-rag-pipeline` | Quarkus | Corpus ingestion + hybrid search demos (requires Qdrant) |

---

## Relationship to LangChain4j

| Capability | Owner |
|---|---|
| Dense embeddings, document parsing, chunking, RAG pipeline, vector stores | LangChain4j |
| Sparse embeddings (SPLADE) | `inference-splade` |
| NLI, classification, regression, cross-encoder reranking | `inference-tasks` |
| casehub RAG wiring — tenancy, hybrid search, SPIs | `rag`, `rag-api` |

This module sits below LangChain4j for inference and above it for RAG.

---

## Shared with Hortora

Hortora takes `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, and `inference-inmem`. They wire LangChain4j RAG independently in their own stack. The `rag-*` modules are casehub-specific.

---

## Status

All core modules are complete and published. The native image gate (C2) passed — both ONNX Runtime JNI and HuggingFace Tokenizers JNI work in Quarkus native image on macOS ARM. Inference, RAG, and corpus storage are production-ready.

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
- [Hortora/spec#15](https://github.com/Hortora/spec/issues/15) — Hortora alignment
