package io.casehub.neocortex.memory;

import java.util.List;

/**
 * Graph-native extension of CaseMemoryStore. Implemented by adapters backed by
 * temporal knowledge graph engines (Graphiti, Neo4j-direct, FalkorDB-direct, etc.).
 *
 * <p>Callers needing temporal graph queries inject {@code GraphCaseMemoryStore} directly.
 * Callers needing only basic storage inject {@code CaseMemoryStore} — unaffected by
 * graph-specific parameters.
 *
 * <p>{@code NoOpCaseMemoryStore} implements this interface; {@code UnsatisfiedResolutionException}
 * will not occur when no graph adapter is deployed.
 */
public interface GraphCaseMemoryStore extends CaseMemoryStore {

    /**
     * Semantic graph query. Uses the adapter's native search endpoint with graph-specific
     * parameters. {@code Memory.text()} carries LLM-extracted fact descriptions — not the
     * original stored text.
     *
     * <p>The caller must supply a non-blank {@code question} — this path is purely semantic.
     * For chronological (non-semantic) retrieval use the base
     * {@link CaseMemoryStore#query(MemoryQuery)} with {@link MemoryOrder#CHRONOLOGICAL}.
     */
    List<Memory> graphQuery(GraphMemoryQuery query);
}
