---
layout: post
title: "The Candidate You Can't Co-Occur"
date: 2026-11-22
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [edge-inference, embedding, candidate-identification, regex-parsing, knowledge-graph, consolidation]
---

# The Candidate You Can't Co-Occur

The edge inference consolidation phase discovers implicit relationships between entities in the knowledge graph. It works by finding under-connected nodes, building candidate pairs, sending them to an LLM for classification, and persisting the inferred edges. Two issues this session addressed different ends of that pipeline: the input (candidate identification) and the output (response parsing).

## The regex that only speaks one dialect

`EdgeInferencePhase.parseRelationships()` uses a regex to extract source/target/type triples from the LLM's JSON response. The pattern assumes a fixed key ordering: `"source"`, then `"target"`, then `"type"`. If the LLM produces keys in any other order — which JSON permits — the match fails silently and the relationship is lost.

The system prompt explicitly asks for the `source, target, type` ordering, so this works in practice. But it's worth documenting what happens when it doesn't. Seven tests now cover the regex edge cases:

- **Standard order** works. Baseline confirmation.
- **Reordered keys** (type before source) produce no match. The regex can't backtrack across field boundaries.
- **Escaped quotes** in entity names (`O\"Brien`) break the `[^"]+` capture group. The regex sees the escaped quote as a field terminator and the remaining text fails to match.
- **Truncated JSON** (partial LLM response) returns an empty list. No crash, no partial extraction.
- **Empty relationship array** returns an empty list. Clean no-op.
- **Multiple relationships** extracts each one independently via `Matcher.find()`.
- **Mixed valid and reordered** entries extract only the valid ones. The regex is resilient in this direction — it doesn't fail globally when one entry is malformed.

The tests are documentation, not an argument for changing the parser. A proper JSON parser would be more robust but adds a dependency for a consolidation-time background operation where the system prompt controls the format. The regex works; now it's tested.

## Co-occurrence is high signal but limited coverage

The candidate identification layer before this change worked exclusively through memory co-occurrence. Two entities mentioned together in the same experience memory — "Alice and Bob collaborated on research" — become a candidate pair for edge inference. The co-occurrence count ranks the pairs and the LLM decides if a relationship exists.

This misses an entire class of potential relationships: entities that are conceptually related but never appear together. Two art movements from different historical passages. Two cities with a cultural link described in separate memories. Two researchers in adjacent fields whose work is never discussed in the same sentence.

Co-occurrence is a strong signal — entities that share context probably have a relationship. But the absence of co-occurrence doesn't mean the absence of relationship. It means the knowledge came in separate pieces.

## Embedding similarity as a second identification layer

The new `buildEmbeddingCandidates()` method adds an optional second candidate source. For each under-connected node, it assembles a text representation from the node's name, traits, and properties, then batch-embeds all texts via `DenseEmbedder.embedBatch()`. Pairwise cosine similarity identifies semantically similar entities that weren't found by co-occurrence.

The integration follows a priority model:

1. Co-occurrence candidates fill slots first (stronger signal)
2. Embedding candidates fill remaining `maxCandidates` slots
3. Duplicate pairs are excluded via canonical pair key dedup
4. Pairs with existing edges are excluded (same check both layers use)

The design reuses infrastructure already in the codebase. `TextSimilarityCorroborator` in the same package provides a static `cosineSimilarity()` utility. `ExperienceConsolidationPhase` established the `Instance<DenseEmbedder>` graceful degradation pattern — when no embedder is on the classpath, the method returns an empty list and the phase behaves exactly as before.

Configuration is opt-in: `casehub.mindmap.consolidation.edge-inference.embedding-candidates-enabled=false` by default, with a similarity threshold at 0.7. The O(n^2) pairwise comparison is acceptable because under-connected nodes are typically a small subset of the graph — the degree threshold filters most nodes out before embedding begins.

## The text representation question

What to embed for each node is the design decision that matters most. Three options surfaced during brainstorming:

- **Node name only** — cheapest but won't discriminate entities with common names
- **Node name + properties** — in-graph data only, no extra store queries, good semantic signal from traits and property values
- **Node name + memory context** — richest signal but O(N) store queries per node

The middle option won. Node properties often carry the semantic context that matters: a "Cubism" node with traits `["ArtMovement"]` and properties `{"era": "20th century"}` embeds differently from a "Cubism" node with no metadata. The traits and properties are already loaded when `findUnderConnectedNodes()` returns, so there's no additional I/O cost.

## What the tests prove

Seven tests cover the embedding layer:

- Similar nodes produce a candidate pair with `coOccurrenceCount=0` and a shared context string reporting the similarity score
- Nodes with existing edges are excluded
- Pairs already found by co-occurrence are excluded (canonical pair key dedup)
- Dissimilar nodes below the threshold produce no candidates
- Null embedder returns an empty list (graceful degradation)
- Disabled config returns an empty list (opt-in gate)
- `buildNodeText()` correctly assembles name, traits, and property values

The `StubDenseEmbedder` test double uses `startsWith` matching on input text to return deterministic vectors. This works because `buildNodeText()` starts with the node name. Orthogonal vectors `[1,0,0,0]` and `[0,1,0,0]` give cosine similarity 0.0; near-parallel vectors `[1,0,0,0]` and `[0.95,0.1,0,0]` give ~0.99. Controlled inputs, predictable outputs.

## The practical point

Knowledge graphs benefit from multiple candidate identification strategies the same way retrieval systems benefit from hybrid search. Co-occurrence is the equivalent of keyword match — precise, high signal, limited recall. Embedding similarity is the equivalent of semantic search — broader recall, lower precision, catches what lexical matching misses.

The two layers compose well because they operate on different signals and feed into the same LLM classification step. The LLM is the precision filter regardless of how the candidates were identified. Adding a second candidate source doesn't change the accuracy of the final edges — it changes the coverage.
