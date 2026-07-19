package io.casehub.neocortex.rag;

import java.time.Instant;
import java.util.Map;

public record DocumentStats(
        String sourceDocumentId,
        int retrievalCount,
        Instant firstRetrieved,
        Instant lastRetrieved,
        double averageRetrievalScore,
        Map<RetrievalOutcome, Integer> feedbackDistribution) {

    public DocumentStats {
        feedbackDistribution = Map.copyOf(feedbackDistribution);
    }
}
