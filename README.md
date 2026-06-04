# casehub-neural-text

[![casehub-neural-text](https://github.com/casehubio/neural-text/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/neural-text/actions/workflows/publish.yml)

Local ONNX text inference and LangChain4j RAG wiring for the [casehubio](https://github.com/casehubio) platform. Two module sets: `inference-*` covers the scoring and classification tasks LangChain4j doesn't touch; `rag-*` wires the retrieval pipeline with casehub tenancy and hybrid dense+sparse search.

The `inference-*` modules are also used by [Hortora](https://github.com/Hortora/spec). They carry zero casehub, Quarkus, Spring, or LangChain4j dependencies — ArchUnit-enforced.

---

## inference-* — what it does and where it lands in casehub

| Task | Adapter | casehub integration |
|---|---|---|
| NLI — faithfulness scoring | `NliClassifier` | **casehub-engine** — scores LLM output against input facts before it enters the typed fact space; flags contradictions before they propagate |
| Multi-class text classification | `TextClassifier` | **casehub-openclaw** — implements the `ActionRiskClassifier` SPI; decides whether an agent output proceeds autonomously or routes to human oversight |
| Scalar regression | `ScalarRegressor` | **casehub-eidos** — estimates epistemic domain confidence from agent output history; replaces static declarations in `AgentCapability.epistemicDomains` |
| Cross-encoder reranking | `CrossEncoderReranker` | **casehub-rag** — precision-mode reranking of retrieved chunks before LLM prompt injection; also used directly by Hortora |
| SPLADE sparse embeddings | `SparseEmbedder` | **casehub-rag** — sparse leg of hybrid search; term-weight maps for Qdrant named vector spaces |

All models run in-process via ONNX Runtime JVM. No API call, no Python, no network dependency.

---

## rag-* — what it does and where it lands in casehub

| SPI | Implementation | What it enables |
|---|---|---|
| `CorpusStore` | Apache Tika ingestion → LangChain4j chunking → dual embedding → Qdrant | Application repos (aml, clinical, devtown) manage named, tenancy-scoped document corpora — SAR typologies, trial protocols, coding standards |
| `CaseRetriever` | Hybrid search: LangChain4j dense + SPLADE sparse, fused via RRF | Engine case steps retrieve grounded context before LLM dispatch; the fact space prompt compiler injects relevant corpus chunks into agent prompts |

Every SPI call requires a `CorpusRef` carrying `tenantId`. Cross-tenant retrieval is blocked at the boundary.

Hybrid search runs both a dense query (semantic similarity) and a sparse query (lexical precision via SPLADE), then fuses results with Reciprocal Rank Fusion. The sparse leg matters for regulated domains where specific terms — `Art. 22 GDPR`, `ICH E6`, `FinCEN SAR-F` — must appear, not just be semantically adjacent.

---

## Module structure

| Module | Artifact | Type | Purpose |
|---|---|---|---|
| `inference-api/` | `casehub-inference-api` | Pure Java, zero deps | `InferenceModel` SPI, `InferenceInput`, `InferenceOutput`, `ModelConfig` |
| `inference-runtime/` | `casehub-inference-runtime` | ONNX Runtime JVM + HF Tokenizers JNI | Session management, tokenization |
| `inference-tasks/` | `casehub-inference-tasks` | Pure Java | `NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker` |
| `inference-splade/` | `casehub-inference-splade` | Pure Java | `SparseEmbedder` — log-saturation SPLADE, `Map<Integer, Float>` output |
| `inference-inmem/` | `casehub-inference-inmem` | Pure Java | Deterministic stubs — no JNI, safe in all test contexts |
| `inference-quarkus/` | `casehub-inference-quarkus` | Quarkus | `@InferenceModel` qualifier, CDI lifecycle, model config |
| `rag-api/` | `casehub-rag-api` | Pure Java, zero deps | `CorpusStore`, `CaseRetriever`, `RetrievedChunk`, `CorpusRef` |
| `rag/` | `casehub-rag` | Quarkus + LangChain4j | Tika ingestion, Qdrant, hybrid RRF, tenancy isolation |
| `rag-testing/` | `casehub-rag-testing` | Pure Java | In-memory stubs — no Qdrant required in `@QuarkusTest` |

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

Hortora takes `casehub-inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, and `inference-inmem`. They wire LangChain4j RAG independently in their own stack. The `rag-*` modules are casehub-specific — Hortora does not take them.

---

## Status

Scaffold — no source code. Design agreed. Pending: ONNX Runtime JNI + HuggingFace Tokenizers JNI in Quarkus native image on macOS ARM. That prototype gates the native deployment path for both casehub and Hortora.

| Epic | Title | Status |
|---|---|---|
| [#1](https://github.com/casehubio/neural-text/issues/1) | Scaffold | ✅ |
| [#2](https://github.com/casehubio/neural-text/issues/2) | Native image prototype | 🔲 |
| [#3](https://github.com/casehubio/neural-text/issues/3) | SPI Foundation + Runtime Core | 🔲 |
| [#4](https://github.com/casehubio/neural-text/issues/4) | Task adapters | 🔲 |
| [#5](https://github.com/casehubio/neural-text/issues/5) | Quarkus integration | 🔲 |
| [#6](https://github.com/casehubio/neural-text/issues/6) | SPLADE | 🔲 |
| [#7](https://github.com/casehubio/neural-text/issues/7) | RAG pipeline | 🔲 |

---

## Documentation

- [Platform Architecture](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md)
- [Deep Dive](https://github.com/casehubio/parent/blob/main/docs/repos/casehub-neural-text.md)
- [Architecture & Delivery Plan](https://github.com/casehubio/neural-text/blob/main/ARC42STORIES.MD)
- [AI Fusion Brief](https://github.com/casehubio/parent/blob/main/docs/specs/2026-06-03-ai-fusion-hybrid-fact-space.md)
- [ONNX Inference Brief](https://github.com/casehubio/parent/blob/main/docs/specs/2026-06-03-standalone-rag-retrieval-brief.md)

## Tracking

- [casehubio/parent#158](https://github.com/casehubio/parent/issues/158) — casehubio/neural-text (onnx inference)
- [casehubio/parent#164](https://github.com/casehubio/parent/issues/164) — casehub-rag (LangChain4j RAG integration)
- [Hortora/spec#15](https://github.com/Hortora/spec/issues/15) — Hortora alignment
