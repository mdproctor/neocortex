package io.casehub.neocortex.rag.crossencoder.reranking;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;
import io.casehub.neocortex.rag.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public final class RerankingLogic {

    public static final String SCORE_KEY = "_crossEncoderScore";
    public static final String RERANKED_KEY          = "_reranked";
    public static final String VECTOR_SIMILARITY_KEY = "_vectorSimilarity";

    private RerankingLogic() {}

    public static List<RetrievedChunk> rerank(CrossEncoderReranker reranker,
                                               String queryText,
                                               List<RetrievedChunk> chunks,
                                               int maxResults) {
        if (chunks.isEmpty()) {return List.of();}

        if (hasPrecomputedScores(chunks)) {
            return sortByPrecomputedScores(chunks, maxResults);
        }

        List<String> contents = chunks.stream()
                                      .map(RetrievedChunk::content).toList();
        List<RankedResult> ranked = reranker.rerank(queryText, contents);

        List<RetrievedChunk> result = new ArrayList<>(
                Math.min(ranked.size(), maxResults));
        for (int i = 0; i < Math.min(ranked.size(), maxResults); i++) {
            RankedResult   r         = ranked.get(i);
            RetrievedChunk original  = chunks.get(r.originalIndex());
            var            augmented = new HashMap<>(original.metadata());
            augmented.put(SCORE_KEY, String.valueOf(r.score()));
            augmented.put(VECTOR_SIMILARITY_KEY, String.valueOf(original.relevanceScore()));
            result.add(original.withMetadata(augmented).withRelevanceScore(r.score()));
        }
        return List.copyOf(result);}

    public static List<RetrievedChunk> attachScores(List<RetrievedChunk> chunks,
                                                     float[] scores) {
        List<RetrievedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk original  = chunks.get(i);
            var            augmented = new HashMap<>(original.metadata());
            augmented.put(SCORE_KEY, String.valueOf(scores[i]));
            augmented.put(VECTOR_SIMILARITY_KEY, String.valueOf(original.relevanceScore()));
            result.add(original.withMetadata(augmented).withRelevanceScore(scores[i]));
        }
        return List.copyOf(result);}

    public static boolean hasPrecomputedScores(List<RetrievedChunk> chunks) {
        return !chunks.isEmpty()
            && chunks.stream().allMatch(c -> c.metadata().containsKey(SCORE_KEY));
    }

    public static boolean isAlreadyReranked(List<RetrievedChunk> chunks) {
        return !chunks.isEmpty()
            && chunks.stream().anyMatch(c -> c.metadata().containsKey(RERANKED_KEY));
    }

    public static List<RetrievedChunk> stamp(List<RetrievedChunk> chunks) {
        return chunks.stream()
            .map(c -> {
                var augmented = new HashMap<>(c.metadata());
                augmented.put(RERANKED_KEY, "true");
                return c.withMetadata(augmented);
            })
            .toList();
    }

    private static List<RetrievedChunk> sortByPrecomputedScores(
            List<RetrievedChunk> chunks, int maxResults) {
        return chunks.stream()
            .sorted(Comparator.comparingDouble(
                (RetrievedChunk c) -> Float.parseFloat(c.metadata().get(SCORE_KEY)))
                .reversed())
            .limit(maxResults)
            .toList();
    }
}
