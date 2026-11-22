## D1: Text representation for embedding

**Choice:** Node name + properties (traits and property values concatenated)
**Alternatives:**
- Node name only — cheapest but won't discriminate entities with common names
- Node name + memory context — richest signal but O(N) store queries per node
**Rationale:** In-graph data only, no extra store queries. Traits and properties provide sufficient semantic context for cosine similarity discrimination.
**Trade-offs:** Less signal than memory-backed context; entities with sparse properties may embed similarly to name-only
**Sources:** ExperienceConsolidationPhase.java (DenseEmbedder precedent), TextSimilarityCorroborator.java (cosine similarity pattern)
**Exploration:** quick
**Status:** captured

## D2: Candidate integration strategy

**Choice:** Fill remaining maxCandidates slots after co-occurrence candidates
**Alternatives:**
- Separate cap for embedding candidates — more candidates total but explodes the LLM prompt
- Replace co-occurrence entirely with embedding — loses the strong co-occurrence signal
**Rationale:** Co-occurrence is a stronger signal (entities literally share context). Embedding fills gaps without inflating the total candidate count.
**Trade-offs:** When co-occurrence fills all slots, embedding candidates are excluded
**Sources:** EdgeInferencePhase.buildCandidates() line 200
**Exploration:** quick
**Status:** captured
