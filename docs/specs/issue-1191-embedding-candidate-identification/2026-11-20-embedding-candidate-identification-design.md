# EdgeInferencePhase: Embedding-Based Candidate Identification

**Issue:** casehubio/neocortex#1191
**Parent:** casehubio/neocortex#547

## Problem

`EdgeInferencePhase.buildCandidates()` discovers candidate entity pairs exclusively via memory co-occurrence — entities must appear together in the same experience memory. This misses conceptually related entities that never share a memory: two art movements from different passages, two cities with a cultural link, two people in adjacent but separate contexts.

## Design

### New method: `buildEmbeddingCandidates()`

A second candidate identification layer that runs **after** co-occurrence candidates, filling remaining `maxCandidates` slots with semantically similar entity pairs discovered via embedding cosine similarity.

### Flow change in `run()`

```
1. findUnderConnectedNodes()           — unchanged
2. buildCandidates()                   — unchanged (co-occurrence)
3. buildEmbeddingCandidates()          — NEW: embedding similarity
4. merge co-occurrence + embedding candidates, cap at maxCandidates
5. invokeEdgeInference()               — unchanged
6. persistEdges()                      — unchanged
```

### Text representation

For each under-connected node, build: `name + " " + traits.join(" ") + " " + properties.values().join(" ")`. This uses only in-graph data — no store queries. Traits and property values provide sufficient semantic signal for cosine discrimination.

### Embedding and similarity

- Batch-embed all node texts via `DenseEmbedder.embedBatch()`
- Compute pairwise cosine similarity (reuse `TextSimilarityCorroborator.cosineSimilarity()` — it's a static utility)
- Keep pairs above `embeddingSimilarityThreshold` (default 0.7)
- Exclude pairs already found by co-occurrence
- Exclude pairs with existing edges (same check as `buildCandidates`)
- Sort by similarity descending
- Fill remaining `maxCandidates` slots

### CandidatePair for embedding matches

Embedding-discovered pairs create `CandidatePair` with `coOccurrenceCount=0` and `sharedContexts` containing a single entry: `"Semantic similarity: <score>"`. This preserves the existing data structure and gives the LLM context about why the pair was surfaced.

### Config additions

Add to `EdgeInferenceConfig`:

```java
@WithDefault("false")
boolean embeddingCandidatesEnabled();

@WithDefault("0.7")
double embeddingSimilarityThreshold();
```

Opt-in (`false` by default) because embedding candidates require a `DenseEmbedder` on the classpath. When disabled or no embedder available, the method returns an empty list — no behavioral change.

### CDI wiring

Add `Instance<DenseEmbedder>` to the CDI constructor (same graceful degradation pattern as `ExperienceConsolidationPhase`). Store as `@Nullable` field. The embedding layer is skipped when:
- `embeddingCandidatesEnabled` is false, OR
- `DenseEmbedder` is not resolvable, OR
- co-occurrence candidates already fill `maxCandidates`

### Test plan

Add to `EdgeInferencePhaseTest`:

1. `buildEmbeddingCandidates_findsSimilarNodes` — two nodes with similar properties, no co-occurrence → candidate pair found
2. `buildEmbeddingCandidates_excludesExistingEdges` — similar nodes with existing edge → excluded
3. `buildEmbeddingCandidates_excludesCoOccurrencePairs` — pair already found by co-occurrence → not duplicated
4. `buildEmbeddingCandidates_belowThreshold_excluded` — pair with low similarity → excluded
5. `buildEmbeddingCandidates_noEmbedder_returnsEmpty` — graceful degradation
6. `buildEmbeddingCandidates_disabled_returnsEmpty` — config gate
7. `run_mergesCoOccurrenceAndEmbeddingCandidates` — integration: both sources contribute candidates

Test double: simple `DenseEmbedder` that returns deterministic vectors based on input text hash.

### Unchanged

- `findUnderConnectedNodes()` — no changes
- `invokeEdgeInference()` — no changes
- `persistEdges()` — no changes
- `parseRelationships()` — no changes
- `SYSTEM_PROMPT` — no changes (the LLM doesn't need to know the candidate source)

## References

- `EdgeInferencePhase.java` — production code being extended
- `ExperienceConsolidationPhase.java:62-82` — `Instance<DenseEmbedder>` graceful degradation precedent
- `TextSimilarityCorroborator.java:86-95` — static `cosineSimilarity()` utility
- `DenseEmbedder.java` — embedding SPI with `embedBatch()`
- `EdgeInferenceConfig.java` — config interface to extend
