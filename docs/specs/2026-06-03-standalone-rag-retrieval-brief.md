# casehubio/neocortex — Design Brief

**Date:** 2026-06-03  
**Status:** Design agreed — pending native image prototype  
**Repo:** `casehubio/neocortex`  
**Consumers:** casehub, Hortora  
**Tracking:** casehubio/parent#158, Hortora/spec#15  
**Authoritative design spec:** `Hortora/spec: docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`

---

## What This Is

A standalone, general-purpose ONNX inference module for JVM projects. Shared between casehub and Hortora — neither project owns it, both depend on it.

**Not a RAG library.** LangChain4j covers the RAG pipeline (Tika document parsing, chunking, dense embeddings via `OnnxEmbeddingModel`, 30+ vector store integrations including Qdrant, retrieval pipeline, context assembly). This module fills the gap LangChain4j leaves: running arbitrary ONNX models for inference tasks that don't fit the dense embedding mold.

---

## Why This and Not a Custom RAG Library

The original direction (a universal RAG library shared between casehub and Hortora) was superseded once LangChain4j's actual coverage was assessed:

- LangChain4j has `langchain4j-document-parser-apache-tika` — Tika is covered
- LangChain4j has `OnnxEmbeddingModel` — local dense ONNX embeddings are covered
- LangChain4j has Qdrant `EmbeddingStore` — vector storage is covered
- LangChain4j has `EmbeddingStoreContentRetriever` + `RetrievalAugmentor` — retrieval pipeline is covered

The real gap is **inference types LangChain4j does not handle**: NLI, classification, regression, SPLADE sparse embeddings, cross-encoder reranking. This module addresses exactly that gap.

---

## What LangChain4j Does vs What This Module Does

| Capability | Where it lives |
|---|---|
| Dense float-vector embeddings (ONNX) | LangChain4j `OnnxEmbeddingModel` |
| Document parsing (Tika, PDF, Office) | LangChain4j document parsers |
| Chunking, retrieval pipeline, vector stores | LangChain4j |
| Sparse embeddings (SPLADE) | `inference-splade` (this module) |
| NLI — hallucination detection | `inference-tasks` (this module) |
| Classification — action risk, multi-class | `inference-tasks` (this module) |
| Regression — scalar confidence output | `inference-tasks` (this module) |
| Cross-encoder reranking | `inference-tasks` (this module) |

This module sits **below** LangChain4j, not beside it.

---

## How Each Project Uses It

**casehub** takes `casehubio/neocortex` as a dependency in:
- `casehub-neocortex-rag` (#164) — `inference-splade` for the sparse leg of hybrid search; `CrossEncoderReranker` for precision-mode reranking
- `casehub-openclaw` — `TextClassifier` replaces the always-AUTONOMOUS `ActionRiskClassifier` stub
- `casehub-engine` (observability) — `NliClassifier` for hallucination detection
- `casehub-eidos` — `ScalarRegressor` for dynamic epistemic confidence estimation

**Hortora** takes `casehubio/neocortex` directly:
- `inference-splade` — sparse leg of hybrid search with Qdrant
- `CrossEncoderReranker` — precision-mode reranking for human-facing retrieval UI

---

## What Is Not Shared

Each project wires LangChain4j RAG independently for their own runtime and domain model:

- **casehub** → `casehub-neocortex-rag` (#164): Quarkus CDI, casehub tenancy isolation, `CorpusStore` SPI, `CaseRetriever` SPI, fact space integration
- **Hortora** → their own equivalent wiring for their stack

No code is shared between the two LangChain4j wiring layers. `casehubio/neocortex` is the only shared artifact between the two projects.

---

## Native Image Gate

Two JNI layers must work in Quarkus native image on macOS ARM before `inference-quarkus` is built:
1. ONNX Runtime (`com.microsoft.onnxruntime`)
2. HuggingFace Tokenizers JNI

The prototype is the first deliverable. If it fails, `inference-quarkus` is JVM-only. Neither casehub nor Hortora commits to native image deployment until the prototype confirms viability.

---

## Sequencing

1. Prototype — JNI gate on macOS ARM
2. `inference-api` + `inference-runtime` + `inference-inmem`
3. `inference-tasks`
4. `inference-quarkus` — conditional on prototype
5. `inference-splade` — after native image validated; upstream LangChain4j contribution candidate (#1600)

`casehub-neocortex-rag` (#164) depends on `inference-splade` being available. It can proceed with the dense-only pipeline until `inference-splade` ships, then add the sparse leg.
