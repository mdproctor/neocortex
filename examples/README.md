# casehub-neocortex Examples

Two example modules demonstrating all casehub-neocortex capabilities across tech, news, and legal domains.

## Quick Start

```bash
# Smoke tests — no models, no Docker, seconds
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples-smoke

# Full tests — downloads real ONNX models, runs Testcontainers Qdrant
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples
```

## Modules

### example-text-analysis

Standalone Java — no Quarkus, no infrastructure. Load ONNX models and run inference.

| Demo | What it does |
|------|-------------|
| `NliDemo` | Premise + hypothesis → entailment / contradiction / neutral scores |
| `ZeroShotClassificationDemo` | NLI-based zero-shot text classification against candidate labels |
| `ScoringDemo` | Sentiment and toxicity scoring with `TextClassifier` |
| `RerankingDemo` | Cross-encoder reranking of 10 multi-domain search candidates |
| `SparseEmbeddingDemo` | SPLADE sparse embedding with top-weighted vocabulary terms |

### example-rag-pipeline

Quarkus + Testcontainers Qdrant — end-to-end corpus ingestion and hybrid search.

| Demo | What it does |
|------|-------------|
| `FlatCorpusIngestDemo` | Flat storage → `CorpusIngestionBinding` → `processBinding()` → incremental ingestion |
| `ZipCorpusIngestDemo` | ZIP storage → tombstones → rollover → `TOMBSTONES_ONLY` compaction |
| `HybridSearchDemo` | Dense + SPLADE sparse → RRF fusion → cross-encoder rerank → top-5 results |

## Capability Coverage

| Capability | Text Analysis | RAG Pipeline |
|---|:---:|:---:|
| **Inference Engine** | | |
| `OnnxInferenceModel` — load and run | x | x |
| `InferenceInput.of()` — single text | | x |
| `InferenceInput.pair()` — text pairs | | x |
| `InMemoryInferenceModel` (smoke tests) | x | x |
| **Task Wrappers** | | |
| `NliClassifier` | x | |
| NLI-based zero-shot classification | x | |
| `TextClassifier` | x | |
| `ScalarRegressor` | smoke only | |
| `CrossEncoderReranker` | x | x |
| `SparseEmbedder` | x | x |
| **Quarkus Integration** | | |
| `@Inference` CDI qualifier | | x |
| `InferenceModelProducer` (config-driven) | | x |
| **RAG Retrieval** | | |
| Dense embedding (LangChain4j) | | x |
| Sparse embedding (SPLADE) | | x |
| Hybrid dense+sparse search | | x |
| RRF fusion (Qdrant server-side) | | x |
| Two-stage retrieval (retrieve then rerank) | | x |
| `EmbeddingIngestor` | | x |
| `CorpusIngestionService.processBinding()` | | x |
| `CorpusIngestionBinding` (wiring record) | | x |
| `MetadataExtractor` / `YamlFrontmatterExtractor` | | x |
| `CursorStore` (incremental ingestion) | | x |
| Reconciliation | | x |
| **Corpus Storage** | | |
| `FlatCorpusStore` | | x |
| `ZipCorpusStore` | | x |
| `ChangeSource` (change detection) | | x |
| Compaction (`TOMBSTONES_ONLY`) | | x |
| **Cross-cutting** | | |
| Multi-domain data (tech, news, legal) | x | x |
| Real ONNX model inference (full profile) | x | x |
| Testcontainers Qdrant | | x |

## Testing Strategy

Five test categories across two Maven profiles:

| Category | Tag | Profile | What it proves |
|---|---|---|---|
| Unit tests | `smoke` | `examples-smoke` | Example code wiring and logic |
| Edge cases | `smoke` | `examples-smoke` | Graceful handling of bad input |
| Happy path | `integration` | `examples` | Real models produce expected results |
| Correctness | `integration` | `examples` | Reranking reorders, SPLADE expands terms, metadata round-trips |
| Cross-domain | `integration` | `examples` | Same pipeline works for tech, news, and legal content |

## Models (full profile)

| Purpose | Model | Size |
|---|---|---|
| NLI + zero-shot classification | `Xenova/nli-deberta-v3-xsmall` | ~87 MB |
| Cross-encoder reranking | `Xenova/ms-marco-MiniLM-L-6-v2` | ~23 MB |
| Sentiment scoring | `Xenova/distilbert-base-uncased-finetuned-sst-2-english` | ~68 MB |
| SPLADE sparse embeddings | `onnx-models/Splade_PP_en_v1-onnx` | ~436 MB |
| Dense embeddings (RAG) | `Xenova/all-MiniLM-L6-v2` | ~80 MB |

## Architecture Justification

See [`docs/architecture-justification.md`](../docs/architecture-justification.md) for evidence-based rationale for each architectural layer.
