# Examples Project Design

**Date:** 2026-06-14
**Status:** Approved (revised after two review rounds)
**Tracks:** casehubio/neural-text examples initiative

---

## Overview

Two example modules demonstrating all casehub-neural-text capabilities across multiple domains (tech, news, legal). Organised along the infrastructure boundary: one standalone (no Quarkus, no Docker), one full RAG pipeline (Quarkus, Testcontainers Qdrant). Real ONNX models from HuggingFace. Comprehensive testing at five levels across two profiles, selected by JUnit `@Tag` and Maven surefire groups.

---

## Module Structure

```
examples/
  example-text-analysis/      — pure Java, no Quarkus, no infrastructure
  example-rag-pipeline/       — Quarkus, Testcontainers Qdrant
```

Both modules live inside the main repo but are excluded from the default build. Three Maven profiles control activation:

| Profile | Trigger | Models | Infrastructure | Speed |
|---|---|---|---|---|
| (default) | `mvn install` | — | — | Examples skipped |
| `examples-smoke` | `mvn install -Pexamples-smoke` | `InMemoryInferenceModel` + stubs | In-memory (rag-testing) | Seconds |
| `examples` | `mvn install -Pexamples` | Real HuggingFace ONNX models | Testcontainers Qdrant | Minutes |

### Parent POM Changes

Add to `casehub-neural-text-parent/pom.xml`:

```xml
<profiles>
  <profile>
    <id>examples-smoke</id>
    <modules>
      <module>examples/example-text-analysis</module>
      <module>examples/example-rag-pipeline</module>
    </modules>
  </profile>
  <profile>
    <id>examples</id>
    <modules>
      <module>examples/example-text-analysis</module>
      <module>examples/example-rag-pipeline</module>
    </modules>
  </profile>
</profiles>
```

### Profile-to-Test Mapping

Tests are tagged with JUnit 5 `@Tag` annotations. Surefire `<groups>` configuration per profile controls which tests run:

- **`examples-smoke` profile:** `<groups>smoke</groups>` — runs only `@Tag("smoke")` tests
- **`examples` profile:** no `<groups>` filter — runs all tests (smoke + integration)

Test tagging convention:
- Category 1 (unit) + Category 5 (edge cases): `@Tag("smoke")`
- Category 2 (happy path) + Category 3 (correctness) + Category 4 (cross-domain): `@Tag("integration")`

### Smoke vs Integration Test Runtime

**`example-text-analysis`:** all tests are plain JUnit (no Quarkus in this module).

**`example-rag-pipeline`:**
- **Smoke tests (`@Tag("smoke")`):** plain JUnit, NOT `@QuarkusTest`. Construct `CorpusIngestionService` manually with `new CorpusIngestionService(inMemoryIngestor, inMemoryCursorStore)` and call `processBinding()` directly. This mirrors the existing `CorpusIngestionServiceTest` pattern in the `rag` module. The CDI `@Inject` fields (`bindingProducer`, `config`, `customBindings`) remain null — `processBinding(binding)` doesn't use them.
- **Integration tests (`@Tag("integration")`):** `@QuarkusTest` with full CDI graph — `ExampleModelProducer` provides real model beans, Testcontainers provides Qdrant, `TestCurrentPrincipal` stubs tenancy.

### Model Downloads

Real ONNX models are downloaded via `download-maven-plugin` (already declared in parent POM), activated only under the `examples` profile. Each model specifies a SHA-256 checksum. Downloads go to `target/models/` — cached by the plugin's skip-if-exists behaviour.

### CI Configuration

- **Every PR:** `examples-smoke` profile runs (seconds)
- **Nightly:** `examples` profile runs with real models (minutes)
- **GitHub Actions caching:** `actions/cache` on model download directory between nightly runs. Models only re-download if URL or checksum changes.

---

## Example 1: Text Analysis

**Module:** `example-text-analysis`

**Dependencies:** `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, `inference-inmem` (test scope). No Quarkus, no RAG, no Qdrant.

**Story:** Load ONNX models, analyse text across three domains — in five lines of code per task.

### Demo Classes

Each class has a `main()` method and is independently runnable.

#### NliDemo

Premise + hypothesis pairs across three domains → entailment / contradiction / neutral scores.

Sample data:

| Domain | Premise | Hypothesis | Expected |
|---|---|---|---|
| tech | "CDI performs dependency injection at runtime using reflection" | "CDI uses compile-time injection" | contradiction |
| news | "Interest rates were held steady at the latest policy meeting" | "The central bank raised rates" | contradiction |
| legal | "Early termination requires 90 days written notice" | "The tenant may terminate at will" | contradiction |

Plus entailment and neutral pairs per domain. Prints a formatted table with scores.

**Model (full profile):** `cross-encoder/nli-deberta-v3-xsmall` (~80MB)

#### ZeroShotClassificationDemo

Demonstrates the NLI-based zero-shot classification pattern: for each candidate label, construct hypothesis "This text is about {label}", run `NliClassifier.classify(text, hypothesis)`, pick the label with highest entailment score. This exercises `NliClassifier` in a loop — it reuses the same NLI model as `NliDemo`, no separate download needed.

Sample data: one text per domain classified against candidate labels (e.g., "technology", "finance", "law", "healthcare", "politics"). The reader sees the same NLI model repurposed for classification.

**Note:** this does NOT exercise `TextClassifier`. `TextClassifier` requires a model whose output dimension matches the label count. The NLI-based zero-shot pattern is a different approach that works with any NLI model. `TextClassifier` is covered by `ScoringDemo` below.

#### ScoringDemo

Four scoring dimensions, each with 3-4 sample texts chosen to span the score range:

**Sentiment** — "terrible service" (strong negative) through "excellent work" (strong positive), including a factual financial statement (neutral despite negative content). Uses `TextClassifier` with a sentiment model (labels: positive/negative/neutral). Confidence score displayed as the "score" column.

**Toxicity** — "I disagree with the changes" (civil) through "anyone who writes code like this should be fired" (toxic). Uses `TextClassifier` or `ScalarRegressor` depending on model output shape.

**Quality** — "the the and or but" (garbage) through well-formed technical prose, including a too-short fragment. Uses `ScalarRegressor` if a single-output quality model is available.

**Readability** — "The cat sat on the mat" (trivial) through dense legal prose. Uses `ScalarRegressor` if a single-output readability model is available.

Each dimension prints a table showing text, score, and label/interpretation. The reader sees the range and can reason about edge cases. Both `TextClassifier` (multi-class) and `ScalarRegressor` (single float) are demonstrated — whichever is appropriate for each dimension's model output shape.

**Models (full profile):** Specific models per dimension TBD — research needed. Sentiment is the most likely `TextClassifier` candidate (small ONNX sentiment models exist). Quality and readability may fall back to smoke-test-only with `InMemoryInferenceModel` if no suitable small model is found.

#### RerankingDemo

Query + 10 candidates of varying relevance → ranked by cross-encoder score. Candidates span all three domains. Prints before and after reranking to show the cross-encoder reshuffling results.

**Model (full profile):** `cross-encoder/ms-marco-TinyBERT-L-2` (~17MB) or `cross-encoder/ms-marco-MiniLM-L-6-v2` (~80MB)

#### SparseEmbeddingDemo

Text → sparse SPLADE vector. Shows the top-weighted vocabulary terms for each input, demonstrating term expansion (query "dependency injection" produces weights for "inject", "bean", "container", "CDI").

**Model (full profile):** `naver/splade-cocondenser-ensembledistil` (~250MB)

### Multi-Domain Sample Data

Hardcoded in each demo class (not external files) — keeps each demo self-contained and copy-pasteable. Three domains: tech, news, legal. Each demo uses domain-appropriate examples.

---

## Example 2: RAG Pipeline

**Module:** `example-rag-pipeline`

**Dependencies:** `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, `inference-quarkus`, `inference-inmem` (test), `rag-api`, `rag`, `rag-testing` (test), `corpus-api`, `corpus`. `casehub-platform-api` comes transitively through `rag`. Quarkus (`quarkus-arc`, `quarkus-scheduler`), LangChain4j (`langchain4j-embeddings` — for `OnnxEmbeddingModel`; NOT `langchain4j-qdrant` — the rag module uses the Qdrant gRPC client directly), Testcontainers Qdrant (test).

**Note on `inference-quarkus`:** required for the `@Inference` CDI qualifier and `InferenceModelProducer` (config-driven model loading from `application.properties`).

**Note on `langchain4j-qdrant`:** NOT a dependency. Per ARC42STORIES §L7, LangChain4j does not support Qdrant named vector spaces or hybrid search (`langchain4j#4994`, open, no timeline). Both dense and sparse legs use the Qdrant Java gRPC client (`io.qdrant:client`) directly. LangChain4j is used only for `EmbeddingModel` and `DocumentSplitter`.

**Story:** Store documents in a corpus, ingest them into a vector store, then search with hybrid retrieval and cross-encoder reranking — end to end.

### CDI Wiring

The example module provides a `@ApplicationScoped` producer class that wires all model beans into CDI:

```java
@ApplicationScoped
public class ExampleModelProducer {

    @Produces @ApplicationScoped
    EmbeddingModel denseModel() {
        // Full profile:
        // new OnnxEmbeddingModel(
        //     Path.of("target/models/all-MiniLM-L6-v2/model.onnx"),
        //     Path.of("target/models/all-MiniLM-L6-v2/tokenizer.json"),
        //     PoolingMode.MEAN);
        // Smoke profile: overridden by @Alternative test stub
    }

    @Produces @ApplicationScoped
    SparseEmbedder sparseEmbedder(@Inference("splade") InferenceModel model) {
        return new SparseEmbedder(model);
    }

    @Produces @ApplicationScoped
    CrossEncoderReranker reranker(@Inference("reranker") InferenceModel model) {
        return new CrossEncoderReranker(model);
    }
}
```

Three beans produced:
- **`EmbeddingModel`** — LangChain4j's `dev.langchain4j.model.embedding.EmbeddingModel` for dense embeddings. Required by `RagBeanProducer` (line 18). Uses LangChain4j's `OnnxEmbeddingModel` (from `langchain4j-embeddings` artifact) which bundles ONNX inference for the dense leg.
- **`SparseEmbedder`** — wraps an `@Inference("splade")`-qualified `InferenceModel`. `InferenceModelProducer` creates the underlying `OnnxInferenceModel` from `application.properties`.
- **`CrossEncoderReranker`** — wraps an `@Inference("reranker")`-qualified `InferenceModel`. Same config-driven loading.

This exercises the `@Inference` CDI qualifier from `inference-quarkus` and demonstrates the config-driven model loading pattern.

### Test Setup — CurrentPrincipal

`HybridCaseRetriever.retrieve()` calls `MemoryPermissions.assertTenant()` at line 82, which requires a `CurrentPrincipal` CDI bean. In `@QuarkusTest`, the example provides a test stub:

```java
@ApplicationScoped @Alternative @Priority(1)
public class TestCurrentPrincipal implements CurrentPrincipal { ... }
```

### Act 1: Ingest

The ingestion story has two sub-stories, separated by storage mode:

#### Sub-story 1: Flat storage — `FlatCorpusIngestDemo`

1. Copy sample documents from classpath (`src/main/resources/corpus/`) to a temp directory (required because `FlatCorpusStore` needs a writable directory — its constructor calls `Files.createDirectories(rootDir)`)
2. Create a `FlatCorpusStore` from the temp directory
3. Create a `FlatChangeSource` from the same directory
4. Construct a `CorpusIngestionBinding`:
   ```java
   var binding = new CorpusIngestionBinding(
       "examples",
       new CorpusRef("demo-tenant", "examples"),
       flatChangeSource,
       flatCorpusStore,       // CorpusReader — FlatCorpusStore implements both
       yamlFrontmatterExtractor
   );
   ```
5. Call `CorpusIngestionService.processBinding(binding, splitter)` — this handles the entire pipeline internally: fullScan (no cursor), read, extract metadata, chunk, batch ingest to Qdrant
6. Print: "Ingested 15 documents, N chunks, into collection 'examples'"
7. Demonstrate incremental ingestion: call `processBinding()` again — cursor persisted by `CursorStore`, zero new documents processed
8. Demonstrate reconciliation: call `CorpusIngestionService.reconcile("examples", binding)` — verifies Qdrant state matches corpus state

#### Sub-story 2: Zip storage — `ZipCorpusIngestDemo`

1. Write the same sample documents into a `ZipCorpusStore` (rolling ZIP archive with chain manifest)
2. Construct a `CorpusIngestionBinding` with `ZipChangeSource` and `ZipCorpusStore` (which implements `CorpusReader`)
3. Call `processBinding()` — same pipeline, different storage backend
4. Delete a document, call `processBinding()` again — demonstrates tombstone handling (delete from Qdrant before re-ingesting remaining)
5. Force rollover — `Compactor.compact()` only operates on closed ZIP files, not the active one. Append enough data to exceed `maxZipSize` (or use a small `maxZipSize` in config) to trigger rollover, creating a closed entry in the chain manifest. This mirrors the pattern in `CompactorTest.forceRollover()`.
6. Load `ChainManifest`, find the closed entry, run `Compactor.compact(zipPath, CompactionMode.TOMBSTONES_ONLY, manifest, corpusDir)` — show archive size before and after

### Act 2: Search — `HybridSearchDemo`

1. Query across all three domains:
   - Tech: "How does dependency injection work?"
   - News: "What happened with interest rates?"
   - Legal: "Can I end my lease early?"
2. For each query, call `CaseRetriever.retrieve(query, corpusRef, 5)` — one call, final results
3. Print a table per query showing rank, score, document title, domain, and snippet

Output includes commentary describing what happened internally: "Under the hood: dense top-20 + sparse top-20 → RRF fusion → cross-encoder rerank → top-5." The intermediate stages are not separately visible through the `CaseRetriever` SPI — `HybridCaseRetriever` runs them as a single pipeline.

### Sample Documents

Shipped in `src/main/resources/corpus/` as markdown files with YAML frontmatter. Copied to a writable temp directory at demo/test startup.

```
corpus/
  tech/
    cdi-injection.md
    quarkus-lifecycle.md
    onnx-runtime-basics.md
    rest-endpoint-design.md
    reactive-streams.md
  news/
    central-bank-rates.md
    tech-earnings-q1.md
    climate-summit.md
    ai-regulation.md
    supply-chain.md
  legal/
    lease-termination.md
    data-protection.md
    employment-notice.md
    liability-limitation.md
    intellectual-property.md
```

Each file is 100-200 words — enough to chunk meaningfully, small enough to read in full.

### Models (full profile)

| Purpose | Model | Size |
|---|---|---|
| Dense embeddings | `sentence-transformers/all-MiniLM-L6-v2` (via LangChain4j ONNX) | ~80MB |
| Sparse embeddings (SPLADE) | `naver/splade-cocondenser-ensembledistil` | ~250MB |
| Cross-encoder reranking | `cross-encoder/ms-marco-MiniLM-L-6-v2` | ~80MB |

---

## Capability Coverage Grid

| Capability | Text Analysis | RAG Pipeline |
|---|:---:|:---:|
| **Inference Engine** | | |
| OnnxInferenceModel — load and run | x | x |
| InferenceInput.of() — single text | x | x |
| InferenceInput.pair() — text pairs | x | x |
| Batch inference (runBatch) | x | x |
| InMemoryInferenceModel (smoke tests) | x | x |
| **Task Wrappers** | | |
| NliClassifier | x | |
| NLI-based zero-shot classification | x | |
| TextClassifier | x | |
| ScalarRegressor | x | |
| CrossEncoderReranker | x | x |
| SparseEmbedder | x | x |
| **Quarkus Integration** | | |
| @Inference CDI qualifier | | x |
| InferenceModelProducer (config-driven) | | x |
| **RAG Retrieval** | | |
| Dense embedding (LangChain4j) | | x |
| Sparse embedding (SPLADE) | | x |
| Hybrid dense+sparse search | | x |
| RRF fusion (Qdrant server-side) | | x |
| Two-stage retrieval (retrieve then rerank) | | x |
| EmbeddingIngestor | | x |
| CorpusIngestionService.processBinding() | | x |
| CorpusIngestionBinding (wiring record) | | x |
| MetadataExtractor / YamlFrontmatterExtractor | | x |
| CursorStore (incremental ingestion) | | x |
| Reconciliation | | x |
| **Corpus Storage** | | |
| FlatCorpusStore | | x |
| ZipCorpusStore | | x |
| ChangeSource (change detection) | | x |
| Compaction (TOMBSTONES_ONLY) | | x |
| **Cross-cutting** | | |
| Multi-domain data (tech, news, legal) | x | x |
| Real ONNX model inference (full profile) | x | x |
| Testcontainers Qdrant | | x |

### Not covered by examples

- `CompositeCorpusStore` — requires multiple backends; adds complexity without teaching new concepts
- `CorpusMigrator` — flat→zip and zip→flat migration; operational concern, not usage pattern
- `ReactiveEmbeddingIngestor` / `ReactiveCaseRetriever` — blocking-to-reactive bridges are internal CDI wiring
- `CorpusIngestionService` `@Scheduled` polling — the example calls `processBinding()` explicitly; the polling loop is deployment configuration
- `rag-tika` (`TikaDocumentParser`) — example uses markdown with YAML frontmatter, handled by `YamlFrontmatterExtractor`. Tika is for PDF/DOCX parsing in production deployments

---

## Testing Strategy

Five test categories across two profiles. Tests are tagged with JUnit 5 `@Tag("smoke")` or `@Tag("integration")`. Profile-to-tag mapping is described in the Module Structure section above.

### Category 1: Unit Tests — `@Tag("smoke")`

Test example code logic in isolation. No models, no infrastructure.

| What | How | Proves |
|---|---|---|
| Demo wiring | `InMemoryInferenceModel` with known outputs → task wrapper → formatted result | Example code doesn't crash, produces structured output |
| Multi-domain coverage | All three domains passed through each demo | No domain-specific assumptions in code |
| Scoring dimensions | `InMemoryInferenceModel` returning varied floats → scoring demo formats correctly | Score table rendering handles full range (0.0 to 1.0, edge cases) |
| Zero-shot classification | `InMemoryInferenceModel` → `NliClassifier` in a loop → label with highest entailment | Zero-shot pattern produces ranked labels |
| Corpus ingest wiring | `FlatCorpusStore` + `InMemoryEmbeddingIngestor` + real sample docs via `CorpusIngestionBinding` → `processBinding()` | Documents read, frontmatter parsed, chunks produced, ingestor called with correct count |
| Search wiring | `InMemoryCaseRetriever` with stubbed results → formatting pipeline | Search result tables render correctly, all three domain queries handled |
| Incremental ingest | `processBinding()` twice on same binding | Second call produces zero new chunks (cursor persisted) |
| Compaction wiring | Delete entry, run `Compactor.compact()` on real `ZipCorpusStore` (no Qdrant) | Store reports fewer entries after compaction |

### Category 2: Happy Path Integration Tests — `@Tag("integration")`

Real models, real infrastructure. Verify the golden path works.

| What | How | Proves |
|---|---|---|
| NLI entailment | "A dog runs in the park" / "An animal is moving" → entailment score | Score > 0.7 |
| NLI contradiction | "The bank raised rates" / "Rates were held steady" → contradiction score | Score > 0.7 |
| Zero-shot classification | Tech doc + candidate labels → top label is "technology" | Correct label is top-1 by entailment score |
| Reranking order | Query with 5 candidates → top result is most relevant | Rank-1 candidate matches expected |
| SPLADE non-empty | "dependency injection" → sparse vector | Has > 0 entries, weights positive |
| Ingest + search | `processBinding()` with 15 docs → `CaseRetriever.retrieve()` → results | At least 1 result, contains expected domain |
| Scoring range | Known-positive text scores higher than known-negative | `score(positive) > score(negative)` |

Assertions are directional (greater-than, correct-label, non-empty), not exact values — model inference is not bit-reproducible across platforms.

### Category 3: Correctness Tests — `@Tag("integration")`

Deeper assertions about result quality. Catch subtle regressions.

| What | How | Proves |
|---|---|---|
| Reranking actually reorders | Pre-rerank candidate list vs post-`retrieve()` order → assert they differ | Cross-encoder is not a pass-through |
| Reranking improves relevance | Top-1 after reranking scores higher than a known-irrelevant candidate | Reranking does useful work |
| SPLADE term expansion | "dependency injection" sparse vector contains weights for tokens not in query | Term expansion works, not just exact-match |
| Metadata round-trip | Ingest doc with `domain: legal` → `retrieve()` → `RetrievedChunk.metadata()` contains `domain: legal` | Extraction → Qdrant payload → retrieval round-trips |
| Domain isolation | "lease termination" → top-3 all from legal domain | Pipeline doesn't return random cross-domain noise |
| Cursor correctness | `processBinding()` 15 docs, persist cursor. Add 2 new docs. `processBinding()` again → exactly 2 new chunks | Incremental ingestion is truly incremental |
| Reconciliation | Delete a doc from corpus (not Qdrant). `reconcile()` → Qdrant point count decreases | Reconciliation detects and removes orphaned points |

### Category 4: Cross-Domain Consistency Tests — `@Tag("integration")`

Verify same pipeline works uniformly across all domains.

| What | How | Proves |
|---|---|---|
| NLI across domains | One entailment pair per domain → all score > 0.5 | Works for tech and legal language, not just news |
| Zero-shot across domains | One text per domain → each gets plausible label | Model doesn't collapse all domains to one label |
| Search across domains | One query per domain → each returns own-domain results in top-3 | No domain systematically disadvantaged |
| Scoring across domains | Sentiment on one text per domain → all produce scores in valid range | Scoring doesn't fail or produce NaN on any domain |

### Category 5: Failure / Edge Case Tests — `@Tag("smoke")`

Verify examples handle bad input gracefully.

| What | How | Proves |
|---|---|---|
| Empty text | Pass "" to each demo | Doesn't crash — graceful handling or clear error |
| Very long text | 10,000-word text | Tokenizer truncates, inference completes, no OOM |
| Empty corpus | `processBinding()` on empty directory → `retrieve()` | Zero results returned cleanly, no NPE |
| Missing model file | Non-existent model path | Clear `ModelLoadException`, not raw JNI crash |
| Corrupt frontmatter | Document with malformed YAML | Treated as body text — doesn't blow up ingestion |

### Profile Summary

```
examples-smoke (every PR, seconds):
  ├── Category 1: Unit tests         @Tag("smoke")
  └── Category 5: Edge cases          @Tag("smoke")

examples (nightly, minutes):
  ├── Category 1: Unit tests         @Tag("smoke")       — also runs, fast
  ├── Category 2: Happy path         @Tag("integration")
  ├── Category 3: Correctness        @Tag("integration")
  ├── Category 4: Cross-domain       @Tag("integration")
  └── Category 5: Edge cases          @Tag("smoke")       — also runs
```

---

## Open Items

The following require research before implementation:

1. **Scoring models** — identify small ONNX models for each of the four scoring dimensions (sentiment, toxicity, quality, readability). Sentiment is the most likely `TextClassifier` candidate. Others may use `ScalarRegressor` or fall back to smoke-test-only. Dimensions without a suitable small model are demonstrated with `InMemoryInferenceModel` in smoke tests and documented as "bring your own model" in the README.

2. **SPLADE model ONNX export** — verify `naver/splade-cocondenser-ensembledistil` is available as a pre-exported ONNX model, or document the export steps.

3. **NLI model selection** — confirm `cross-encoder/nli-deberta-v3-xsmall` is the right size/quality tradeoff. Alternative: `cross-encoder/nli-deberta-v3-small`.

4. **ONNX Runtime version alignment** — both `inference-runtime` and `langchain4j-embeddings` depend on `com.microsoft.onnxruntime:onnxruntime`. The parent POM pins to 1.26.0 via `dependencyManagement`, so Maven resolves both to the same version. Verify during model research that `langchain4j-embeddings` 1.14.1 is compatible with onnxruntime 1.26.0 at runtime (API changes between versions could cause `NoSuchMethodError`).

These do not block writing the implementation plan — they are resolved during the first implementation task (model research and download configuration).

---

## Review History

**2026-06-14 — Initial spec reviewed.** 12 issues identified:

| # | Issue | Resolution |
|---|---|---|
| 1 | Act 1 bypassed `CorpusIngestionService` | Fixed: now uses `processBinding()` |
| 2 | `changesSince(null)` instead of `fullScan()` | Moot — `processBinding()` handles internally |
| 3 | Act 2 claimed intermediate search stages visible | Fixed: `CaseRetriever.retrieve()` returns final results; stages described in commentary |
| 4 | "Hybrid beats single-leg" test unimplementable | Dropped — `HybridCaseRetriever` always runs both legs |
| 5 | `ClassificationDemo` conflated `TextClassifier` with NLI zero-shot | Fixed: split into `ZeroShotClassificationDemo` (NliClassifier) and `ScoringDemo` (TextClassifier) |
| 6 | `CurrentPrincipal` test setup unspecified | Fixed: test stub documented |
| 7 | No mechanism for smoke vs. full test selection | Fixed: JUnit `@Tag` + surefire `<groups>` |
| 8 | `CorpusIngestionBinding` omitted | Fixed: now explicit in Act 1 |
| 9 | Compaction narrative conflated Flat and Zip | Fixed: Act 1 split into two sub-stories |
| 10 | `@Inference` IS covered by RAG Pipeline | Fixed: added to coverage grid with producer example |
| 11 | `rag-tika` not in "Not covered" | Fixed: added |
| 12 | Resource → temp dir copy missing | Fixed: explicit in Act 1 |

**2026-06-14 — Second review of revised spec.** 5 issues identified:

| # | Issue | Resolution |
|---|---|---|
| 1 | `ExampleModelProducer` missing `EmbeddingModel` bean | Fixed: added `EmbeddingModel` producer using LangChain4j `OnnxEmbeddingModel` |
| 2 | `langchain4j-qdrant` incorrectly listed as dependency | Fixed: removed; rag module uses `io.qdrant:client` directly per ARC42STORIES §L7 |
| 3 | `inference-quarkus` missing from dependency list | Fixed: added with explanatory note |
| 4 | Smoke vs `@QuarkusTest` mechanism unspecified | Fixed: smoke = plain JUnit (like `CorpusIngestionServiceTest`), integration = `@QuarkusTest` |
| 5 | Compaction requires rollover before `compact()` | Fixed: rollover step added before compaction in Zip sub-story |

**2026-06-14 — Third review.** No new issues. Two observations addressed:

| # | Observation | Resolution |
|---|---|---|
| 1 | `OnnxEmbeddingModel` pseudocode used builder pattern instead of constructor API | Fixed: updated to `new OnnxEmbeddingModel(path, tokenizer, PoolingMode.MEAN)` |
| 2 | ONNX Runtime version alignment between `inference-runtime` and `langchain4j-embeddings` | Added to Open Items as implementation-time check |
