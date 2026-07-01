# Architecture Justification — neocortex

This document records the evidence base for each architectural layer in `casehub-neocortex`. Every claim is attributed to a specific source. Where benchmarks are cited, the dataset, metric, and result are included. No claims are made without supporting evidence.

---

## 1. Hybrid Dense + Sparse Retrieval

### The problem with dense-only retrieval

Dense embedding models encode semantic meaning but lose lexical precision. A February 2026 study by the Information Retrieval Group at MIT CSAIL found that state-of-the-art dense retrievers fail on **23% of factual queries** containing rare but critical entities. Hybrid pipelines reduced that failure rate to **11%**. ([AIMultiple — Hybrid RAG: Boosting RAG Accuracy][1])

Financial and legal documents contain precise, domain-specific terminology (company names, standardised metric labels, regulatory references) that lexical matching captures effectively. The same study challenged the common assumption that dense retrieval universally dominates sparse methods. ([AIMultiple][1])

### Measured improvement from hybrid search

Hybrid RAG (combining dense vector search with sparse keyword search) improves retrieval accuracy by **26–31% NDCG** over dense-only approaches, and improves **Recall@1,000 from ~0.87 to 0.98**. ([AIMultiple][1])

### Production adoption

All major vector databases now support hybrid search natively: Weaviate, Qdrant, Pinecone, Elasticsearch, and Amazon OpenSearch. Hybrid search is the production default for enterprise RAG systems as of 2026. ([Digital Applied — Hybrid Search: BM25, Vector & Reranking 2026][2])

### What neocortex implements

`HybridCaseRetriever` and `ReactiveHybridCaseRetriever` perform two-leg retrieval against Qdrant: dense embeddings via LangChain4j `EmbeddingModel` and sparse embeddings via `SparseEmbedder` (SPLADE). Both legs are stored as named vector spaces in Qdrant and fused server-side via RRF.

---

## 2. SPLADE Learned Sparse Retrieval (over BM25)

### Why SPLADE over BM25

BM25 matches only on exact surface terms. SPLADE uses masked language modelling to generate sparse learned vectors with implicit term expansion — a query about "prepayment" is expanded to represent "early closure," "foreclosure," "advance payment," and "part payment." ([GoPenAI — Hybrid Search in RAG][3])

### Measured improvement over BM25

A May 2026 benchmark tested SPLADE v3 against BM25 as the sparse leg of hybrid systems. Across all enterprise datasets, SPLADE hybrid pipelines drove hallucination reduction from **31% (BM25 + vector) to 43%** relative to pure vector alone. ([RAG About It — 7 Hybrid Search Secrets][4])

### Latency

Open-source SPLADE libraries ship with pre-trained weights fine-tuned on enterprise search tasks. Inference adds **8–15ms per query** on modern hardware, making it viable for sub-100ms latency budgets. Some deployments report higher latency of **80–120ms on GPU endpoints** depending on model size and hardware. ([RAG About It][4]; [Digital Applied][2])

### What neocortex implements

`inference-splade` provides `SparseEmbedder`, which runs any SPLADE-family ONNX model locally via `OnnxInferenceModel`. The output is `Map<Integer, Float>` (vocabulary index → weight), with ReLU activation, log-saturation (`log1p`), and a configurable sparsity threshold. Single and batch modes are supported.

---

## 3. Reciprocal Rank Fusion (RRF)

### Why RRF

Dense cosine similarities and sparse BM25/SPLADE scores have incompatible distributions. BM25 scores range from 0 to ~15; dense cosine similarities from 0.6 to 0.95. After normalisation, outlier scores in either system distort the combined ranking. RRF discards scores entirely and operates on **ranks only**, which eliminates the score-incompatibility problem. ([GoPenAI][3]; [Ailog RAG — Hybrid Fusion][5])

### Benchmark comparison

In one benchmark, Convex Combination with α=0.5 achieved **Recall@5 of 0.726**, outperforming RRF (k=60) at **0.695**. Among RRF variants, k=10 achieved the best RRF performance at **0.716**. However, RRF's advantage is robustness: k=60 requires no per-dataset tuning and works across domains. ([Digital Applied][2])

The parameter k=60 was empirically validated in the original RRF paper (Cormack, Clarke, and Butt, 2009) across multiple TREC benchmarks. For small corpora (50–200 documents), lower k values (10–20) produce steeper rank differentiation. ([Digital Applied][2])

### What neocortex implements

`HybridCaseRetriever` uses Qdrant's native `PrefetchQuery` with `Fusion.RRF` — both dense and sparse prefetch queries are executed server-side and fused by Qdrant before results are returned. This avoids client-side fusion logic and benefits from Qdrant's optimised implementation.

---

## 4. Cross-Encoder Reranking

### The two-stage retrieval paradigm

The consensus across 2026 sources is that two-stage retrieval (retrieve broadly, then rerank precisely) is the correct production model. Embedding models are trained for semantic similarity at scale, not for ranking. ANN search is optimised for recall, not precision. Cross-encoder reranking addresses this directly. ([Towards Data Science — Advanced RAG Retrieval][6]; [DEV Community — RAG Is Not Dead][7])

### Measured improvement

Adding a cross-encoder reranker to hybrid retrieval yields **+17.2 percentage points MRR@3** and **+12.1pp Recall@5** over unreranked hybrid retrieval. The two-stage pipeline achieves **Recall@5 = 0.816, MRR@3 = 0.605**, outperforming all single-stage methods. ([Digital Applied][2])

Cross-encoder reranking typically provides **+5 to +15 NDCG@10 improvement** across MTEB and BEIR benchmarks. Leading organisations report **30–50% improvements in retrieval precision** from adding a reranking layer. ([Ailog RAG — Cross-Encoder Reranking Study][8]; [Towards Data Science][6])

### Reranking depth

With only 20 candidates, reranking is ineffective (**Recall@5: 0.458**) because relevant documents are often not in the candidate pool. Performance increases sharply at 50 candidates (**0.826**) and continues to improve at 100 (**0.888**). The recommended practice is to rerank the top 50–100 candidates from the fusion stage. ([Digital Applied][2])

### How cross-encoders work

A cross-encoder processes the query and document as a single input sequence — `[CLS] query [SEP] document [SEP]` — and outputs a scalar relevance score. Because both texts pass through every transformer layer together, attention models their interaction directly. This is fundamentally more expressive than bi-encoder similarity but too expensive for first-stage retrieval over an entire corpus. ([Medium — Reranking in RAG][9])

### What neocortex implements

`CrossEncoderReranker` in `inference-tasks` wraps any single-output ONNX model. It provides `score(query, candidate)` for individual scoring and `rerank(query, candidates)` which returns `List<RankedResult>` sorted by descending relevance, with original indices preserved. The model runs locally via `OnnxInferenceModel` — no external API dependency.

---

## 5. JVM-Native ONNX Inference

### Why run inference in-process

Frameworks like Spring AI delegate inference to remote services. ONNX Runtime executes models directly inside the JVM, so inference is **deterministic, auditable, and fully under enterprise control**. External framework-based inference produces non-repeatable outputs and depends on the availability and evolving APIs of third-party providers. ONNX inference uses versioned artifacts (`model.onnx` and `tokenizer.json`) which behave consistently across environments. ([InfoQ — Bringing AI Inference to Java with ONNX][10])

### Performance

ONNX Runtime accelerates models from PyTorch, TensorFlow, and other frameworks by **2–10x** compared to their native runtimes, through constant folding, operator fusion, and dead code elimination. ([Reintech — ONNX Runtime for Production ML][11])

### Tokenizer alignment

Accurate inference depends on keeping tokenizers and models perfectly aligned. Architects must treat tokenizers as versioned, first-class components — not interchangeable utilities. ([InfoQ][10])

### JVM-specific considerations

Scalable inference patterns use native Java constructs for load-balancing across CPU/GPU threads, async job queues, and high-throughput pipelines. JVM-native observability, security, and CI/CD workflows are preserved. The Foreign Function & Memory API (JEP 454) is being evaluated as a future replacement for JNI in inference pipelines. ([InfoQ][10])

### What neocortex implements

`OnnxInferenceModel` in `inference-runtime` loads ONNX models via ONNX Runtime JNI and tokenizes via HuggingFace Tokenizers JNI. It supports both 2-input (input_ids + attention_mask) and 3-input (+ token_type_ids for BERT-family) models. It is thread-safe for concurrent `run`/`runBatch` calls, with close-safety guards that reject post-close inference attempts. Model and tokenizer are treated as versioned file artifacts (`model.onnx` + `tokenizer.json`).

---

## 6. NLP Task Coverage Beyond RAG

### The gap LangChain4j leaves

LangChain4j provides `OnnxEmbeddingModel` for dense embeddings but does not cover NLI, text classification, scalar regression, or SPLADE sparse embeddings. These capabilities require running arbitrary ONNX models with varying output shapes and post-processing (softmax, label mapping, ReLU + log-saturation). ([Java Code Geeks — RAG Architecture on the JVM][12])

### What neocortex implements

`inference-tasks` provides four task wrappers that fill this gap:

- **`NliClassifier`** — natural language inference (premise + hypothesis → entailment/neutral/contradiction). Softmax normalisation applied. Supports both convention mapping (DeBERTa label order: contradiction=0, neutral=1, entailment=2) and explicit index constructors for non-standard models.
- **`TextClassifier`** — multi-label text classification with arbitrary label sets. Softmax normalisation applied. Returns predicted label, confidence, and full score map.
- **`ScalarRegressor`** — single-float regression output (sentiment scoring, quality assessment, relevance scoring).
- **`CrossEncoderReranker`** — as described in §4.

Each wrapper validates output size at construction (when known) and at runtime, with clear error messages on mismatch. All are stateless over the underlying `InferenceModel`, inheriting its thread-safety guarantees.

---

## 7. Test Isolation Without JNI

### The problem

ONNX Runtime JNI and HuggingFace Tokenizers JNI require native libraries that are platform-specific and add significant test startup time. Unit tests and `@QuarkusTest` suites should not depend on native library availability.

### What neocortex implements

`InMemoryInferenceModel` in `inference-inmem` provides deterministic stubs with no JNI dependency. It supports fixed return values (`returning(float...)`), custom functions (`withFunction(outputSize, Function<InferenceInput, float[]>)`), and configurable output size. It is safe in all test contexts including CI environments without native libraries installed.

---

## Summary — Pipeline Alignment

The recommended 2026 production RAG pipeline, as described across the cited sources:

| Stage | Recommendation | neocortex implementation |
|-------|---------------|--------------------------|
| 1. Sparse retrieval | SPLADE over BM25 for domain-specific term expansion | `SparseEmbedder` (inference-splade) |
| 2. Dense retrieval | Bi-encoder semantic embeddings via ANN search | LangChain4j `EmbeddingModel` (rag/) |
| 3. Fusion | RRF — rank-based, no score normalisation | Qdrant `PrefetchQuery` + `Fusion.RRF` (rag/) |
| 4. Reranking | Cross-encoder on top-50 to top-100 fused candidates | `CrossEncoderReranker` (inference-tasks) |
| 5. Inference runtime | In-process, deterministic, versioned model artifacts | `OnnxInferenceModel` (inference-runtime) |

---

## References

[1]: https://aimultiple.com/hybrid-rag "AIMultiple — Hybrid RAG: Boosting RAG Accuracy"
[2]: https://www.digitalapplied.com/blog/hybrid-search-bm25-vector-reranking-reference-2026 "Digital Applied — Hybrid Search: BM25, Vector & Reranking 2026"
[3]: https://blog.gopenai.com/hybrid-search-in-rag-dense-sparse-bm25-splade-reciprocal-rank-fusion-and-when-to-use-which-fafe4fd6156e "GoPenAI — Hybrid Search in RAG: Dense + Sparse, RRF"
[4]: https://ragaboutit.com/7-hybrid-search-secrets-that-cut-rag-hallucination-by-43/ "RAG About It — 7 Hybrid Search Secrets That Cut RAG Hallucination by 43%"
[5]: https://app.ailog.fr/en/blog/guides/hybrid-retrieval-fusion "Ailog RAG — Hybrid Fusion: Combining Dense and Sparse Retrieval"
[6]: https://towardsdatascience.com/advanced-rag-retrieval-cross-encoders-reranking/ "Towards Data Science — Advanced RAG Retrieval: Cross-Encoders & Reranking"
[7]: https://dev.to/young_gao/rag-is-not-dead-advanced-retrieval-patterns-that-actually-work-in-2026-2gbo "DEV Community — RAG Is Not Dead: Advanced Retrieval Patterns That Actually Work in 2026"
[8]: https://app.ailog.fr/en/blog/news/reranking-cross-encoders-study "Ailog RAG — Cross-Encoder Reranking Improves RAG Accuracy by 40%"
[9]: https://medium.com/@vaibhav-p-dixit/reranking-in-rag-cross-encoders-cohere-rerank-flashrank-c7d40c685f6a "Medium — Reranking in RAG: Cross-Encoders, Cohere Rerank & FlashRank"
[10]: https://www.infoq.com/articles/onnx-ai-inference-with-java/ "InfoQ — Bringing AI Inference to Java with ONNX: a Practical Guide for Enterprise Architects"
[11]: https://reintech.io/blog/onnx-runtime-production-ml-optimizing-model-inference-speed "Reintech — ONNX Runtime for Production ML: Optimizing Model Inference Speed"
[12]: https://www.javacodegeeks.com/2026/04/rag-architecture-on-the-jvm-building-a-production-ready-pipeline-with-langchain4j.html "Java Code Geeks — RAG Architecture on the JVM with LangChain4j (April 2026)"
