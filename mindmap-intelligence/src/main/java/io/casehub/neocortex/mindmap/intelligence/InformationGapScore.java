package io.casehub.neocortex.mindmap.intelligence;

/**
 * Per-node information gap score per Loewenstein (1994) and Golman &amp; Loewenstein (2016).
 * Curiosity peaks at partial knowledge (moderate confidence + connections to known concepts)
 * and is absent at the extremes (know nothing with no context, know everything).
 *
 * @param nodeId       MindMap node identifier
 * @param nodeName     human-readable node name
 * @param subgraphType subgraph type string (e.g. "concept", "person")
 * @param gapIntensity composite gap score in [0,1] — entropy × attention weight
 * @param entropy      binary entropy H(confidence) — peaks at 0.5
 * @param edgeCount    number of edges connecting this node to the graph
 * @param confidence   node confidence value
 * @param gapType       classification of the gap pattern
 * @param goalRelevance BFS proximity to nearest active goal node in [0,1] — 1.0 at 1 hop, 0.7 at 2, 0.4 at 3, 0.0 beyond
 */
public record InformationGapScore(
        String nodeId,
        String nodeName,
        String subgraphType,
        double gapIntensity,
        double entropy,
        int edgeCount,
        double confidence,
        GapType gapType,
        double goalRelevance) {

    public enum GapType {
        /** Moderate confidence with connections — the sweet spot (Loewenstein 1994). */
        PARTIAL_KNOWLEDGE,
        /** High confidence + many connections but gap not quite closed. Brown &amp; McNeill (1966). */
        TIP_OF_TONGUE,
        /** Low confidence but connected — aware of the gap. Loewenstein (1994). */
        CONNECTED_UNKNOWN
    }

    public InformationGapScore {
        if (gapIntensity < 0.0 || gapIntensity > 1.0)
            throw new IllegalArgumentException("gapIntensity must be in [0,1], got " + gapIntensity);
        if (entropy < 0.0)
            throw new IllegalArgumentException("entropy must be non-negative");
        if (edgeCount < 0)
            throw new IllegalArgumentException("edgeCount must be non-negative");
        if (goalRelevance < 0.0 || goalRelevance > 1.0)
            throw new IllegalArgumentException("goalRelevance must be in [0,1], got " + goalRelevance);
    }
}
