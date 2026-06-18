# neural-text Workspace
**Name:** casehub-neural-text

**Physical path:** `/Users/mdproctor/claude/casehub/neural-text/CLAUDE.md`
**Project repo:** `/Users/mdproctor/claude/casehub/neural-text`
**Workspace:** `/Users/mdproctor/claude/public/casehub/neural-text`
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/neural-text` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` (workspace staging) |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` (workspace staging) |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (staging; promoted to project `docs/specs/` at epic close)
- `plans/` — implementation plans (ephemeral; stay in workspace only)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records (staging; promoted to project `docs/adr/` at epic close)
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/neural-text`) — staging for specs/ADRs; permanent home for blog, handover, plans, snapshots
- **Project repo** (`/Users/mdproctor/claude/casehub/neural-text`) — source code + promoted specs (`docs/specs/`) + promoted ADRs (`docs/adr/`)

Never rely on CWD for git operations. Always use explicit paths:
```bash
git -C /Users/mdproctor/claude/public/casehub/neural-text ...  # workspace artifacts
git -C /Users/mdproctor/claude/casehub/neural-text ...         # project artifacts
```

Source code commits → project repo (`origin` = mdproctor/neural-text, `upstream` = casehubio/neural-text)

## Rules

- All methodology artifacts go to workspace first
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

## Peer Repos — Hard Boundary

**Never commit to these repos from a neural-text session.** Each has its own Claude session. For cross-repo fixes, create a GitHub issue on the target repo instead.

Peer repos: platform, ledger, connectors, work, qhorus, eidos, engine, claudony, openclaw, devtown, aml, clinical, life, drafthouse, quarkmind, flow

---

# CaseHub Neural-Text — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

**Platform architecture:**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md
```

**This repo's deep-dive:**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-neural-text.md
```

**Related specs:**
- AI Fusion brief: `https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-ai-fusion-hybrid-fact-space.md`
- ONNX inference brief: `https://raw.githubusercontent.com/casehubio/parent/main/docs/specs/2026-06-03-standalone-rag-retrieval-brief.md`

**Upstream work in progress:**
- quarkus-langchain4j #2572: `@RagPipeline`, `@HybridSearch`, `@DocumentIngestion` composition annotations — simplifies RAG wiring. Also `@RegisterAiService` supplier→bean migration. When shipped, adopt these in `rag/` module to replace manual CDI wiring. Track at `https://github.com/quarkiverse/quarkus-langchain4j/issues/2572`

---

## Reference Documents

| Document | What it covers |
|----------|---------------|
| `../garden/docs/protocols/universal/INDEX.md` | Universal Java/Quarkus protocols |

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, LangChain4j 1.14.1, ONNX Runtime JVM

---

## What This Project Is

`casehub-neural-text` provides two related capabilities for the casehubio platform:

### 1. Neural Text Inference (`inference-*` modules)

A standalone, general-purpose ONNX inference layer for JVM projects. Zero casehub domain dependencies. Shared with Hortora. Fills the gap LangChain4j leaves: running arbitrary ONNX models for NLI, classification, regression, sparse embeddings (SPLADE), and cross-encoder reranking.

**LangChain4j covers:** dense embeddings (`OnnxEmbeddingModel`), RAG pipeline, vector stores.
**This covers:** everything else — NLI, classification, regression, SPLADE, cross-encoder.

Tracks `casehubio/parent#158`. Authoritative design: `Hortora/spec: docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`

### 2. RAG Integration (`rag-*` modules)

casehub-specific LangChain4j RAG pipeline wiring. Exposes `EmbeddingIngestor` SPI (ingest documents) and `CaseRetriever` SPI (retrieve context for case steps), with reactive variants (`ReactiveEmbeddingIngestor`, `ReactiveCaseRetriever`) for consumers on the Vert.x event loop. Tenancy-isolated Qdrant collections. Hybrid dense (LangChain4j) + sparse (inference-splade) search via RRF fusion. `CorpusIngestionService` bridges corpus modules to RAG — polls `ChangeSource`, reads via `CorpusReader`, extracts metadata (`MetadataExtractor` SPI), chunks, and pushes to Qdrant via `EmbeddingIngestor`. Config-driven with cursor persistence (`CursorStore` SPI) and admin-triggered reconciliation.

Tracks `casehubio/parent#164`.

---

## Module Structure

```
inference-api/      — zero deps: InferenceModel SPI, InferenceInput, InferenceOutput, InferenceException
inference-runtime/  — ONNX Runtime JVM + HuggingFace Tokenizers JNI; OnnxInferenceModel, ModelConfig
inference-tasks/    — NliClassifier, TextClassifier, ScalarRegressor, CrossEncoderReranker
inference-splade/   — sparse SPLADE embeddings (Map<Integer, Float>)
inference-inmem/    — deterministic stubs; no JNI; safe in all test contexts
inference-quarkus/  — CDI wiring, @InferenceModel qualifier, Dev Services, @QuarkusTest
rag-api/            — EmbeddingIngestor + ReactiveEmbeddingIngestor SPIs, CaseRetriever + ReactiveCaseRetriever SPIs, MetadataExtractor + CursorStore SPIs, value types — Mutiny provided
rag/                — LangChain4j wiring, Qdrant, hybrid RRF fusion, @DefaultBean blocking-to-reactive bridges, CorpusIngestionService (event-driven via directory-watcher for filesystem corpora, @Scheduled polling fallback for ZIP-based corpora)
rag-tika/           — optional Apache Tika document parsing → chunked ChunkInput
rag-testing/        — in-memory stubs for both blocking and reactive SPIs + InMemoryCursorStore + InMemoryRelevanceEvaluator (@Alternative @Priority(1) @ApplicationScoped)
rag-crag/           — Corrective RAG: CDI @Decorator on CaseRetriever — evaluates retrieval quality (RelevanceEvaluator SPI), filters INCORRECT chunks, expands search, fires RetrievalQuality CDI events. Classpath-activated. CrossEncoderRelevanceEvaluator default.
corpus-api/         — CorpusStore + CorpusReader + ChangeSource + WatchableChangeSource + CorpusIntegrity SPIs, reactive variants, value types — zero deps, Hortora-eligible
corpus/             — Zip4j implementation: ZipCorpusStore (rolling archives, chain manifest), FlatCorpusStore, CompositeCorpusStore, compaction, migration — Hortora-eligible
examples/
  example-text-analysis/  — standalone demos: NLI, zero-shot classification, scoring, reranking, SPLADE — no Quarkus
  example-rag-pipeline/   — Quarkus demos: corpus ingestion (flat + zip), hybrid search, CDI wiring — requires Qdrant
```

Examples are excluded from the default build. Activate with `-Pexamples-smoke` (in-memory stubs) or `-Pexamples` (real ONNX models + Testcontainers Qdrant).

## Maven Coordinates

| Element | Value |
|---|---|
| GitHub repo | `casehubio/neural-text` |
| groupId | `io.casehub` |
| Parent artifactId | `casehub-neural-text-parent` |
| Inference API | `casehub-inference-api` |
| Inference Runtime | `casehub-inference-runtime` |
| Inference Tasks | `casehub-inference-tasks` |
| Inference SPLADE | `casehub-inference-splade` |
| Inference in-memory | `casehub-inference-inmem` |
| Inference Quarkus | `casehub-inference-quarkus` |
| RAG API | `casehub-rag-api` |
| RAG | `casehub-rag` |
| RAG Tika | `casehub-rag-tika` |
| RAG testing | `casehub-rag-testing` |
| RAG CRAG | `casehub-rag-crag` |
| Corpus API | `casehub-corpus-api` |
| Corpus | `casehub-corpus` |
| Example Text Analysis | `casehub-example-text-analysis` |
| Example RAG Pipeline | `casehub-example-rag-pipeline` |
| Root Java package (inference) | `io.casehub.inference` |
| Root Java package (rag) | `io.casehub.rag` |
| Root Java package (examples) | `io.casehub.examples.analysis`, `io.casehub.examples.rag` |
| Root Java package (corpus) | `io.casehub.corpus` |

## Build Commands

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Build without tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -DskipTests

# Build specific module
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -pl inference-api

# Examples — smoke tests (no models, no Docker, seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples-smoke

# Examples — full tests (downloads ONNX models, Testcontainers Qdrant)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean test -Pexamples
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

## Critical: Native Image Gate

`inference-quarkus` requires both ONNX Runtime JNI and HuggingFace Tokenizers JNI to work in Quarkus native image on macOS ARM. The prototype must demonstrate this before `inference-quarkus` is built. Until the gate passes, all `inference-*` modules operate in JVM mode only.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/neural-text
**Changelog:** GitHub Releases
