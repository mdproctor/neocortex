# casehub-neural-text

[![casehub-neural-text](https://github.com/casehubio/neural-text/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/neural-text/actions/workflows/publish.yml)

ONNX neural text inference and LangChain4j RAG integration for the [casehubio](https://github.com/casehubio) platform.

## What This Is

Two related capabilities in one repo:

**Neural Text Inference (`inference-*`)** — a standalone, general-purpose ONNX inference layer for JVM projects. Zero casehub domain dependencies. Shared with [Hortora](https://github.com/Hortora/spec). Fills the gap LangChain4j leaves: running ONNX models for NLI, classification, regression, SPLADE sparse embeddings, and cross-encoder reranking.

**RAG Integration (`rag-*`)** — casehub-specific LangChain4j RAG pipeline wiring with tenancy isolation, `CorpusStore` and `CaseRetriever` SPIs, and hybrid dense+sparse search via RRF fusion.

## Module Structure

```
inference-api/      — zero deps: InferenceModel SPI and domain types
inference-runtime/  — ONNX Runtime JVM + HuggingFace Tokenizers JNI
inference-tasks/    — NliClassifier, TextClassifier, ScalarRegressor, CrossEncoderReranker
inference-splade/   — SPLADE sparse embeddings (Map<Integer, Float>)
inference-inmem/    — deterministic test stubs (no JNI)
inference-quarkus/  — Quarkus CDI wiring and Dev Services
rag-api/            — CorpusStore and CaseRetriever SPIs (zero deps)
rag/                — LangChain4j + Qdrant wiring, hybrid RRF fusion
rag-testing/        — in-memory stubs for @QuarkusTest
```

## Status

Scaffold — no source code yet. Design complete, pending prototype validation (ONNX JNI in Quarkus native image on macOS ARM).

## Documentation

- [Platform Architecture](https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md)
- [Deep Dive](https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-neural-text.md)
- [AI Fusion Brief](https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-ai-fusion-hybrid-fact-space.md)
- [ONNX Inference Brief](https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-standalone-rag-retrieval-brief.md)

## Tracking

- `casehubio/parent#158` — casehubio/neural-text (onnx inference)
- `casehubio/parent#164` — casehub-rag (LangChain4j RAG integration)
- `Hortora/spec#15` — Hortora alignment
