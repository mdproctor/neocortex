package io.casehub.neocortex.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RrfFusion {

    private static final int DEFAULT_K = 60;

    private RrfFusion() {}

    public static List<RetrievedChunk> fuse(
            List<List<RetrievedChunk>> rankedLists, int maxResults) {
        return fuse(rankedLists, maxResults, DEFAULT_K);
    }

    public static List<RetrievedChunk> fuse(
            List<List<RetrievedChunk>> rankedLists, int maxResults, int k) {
        if (rankedLists.isEmpty()) {
            return List.of();
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> chunks = new LinkedHashMap<>();

        for (List<RetrievedChunk> results : rankedLists) {
            for (int rank = 0; rank < results.size(); rank++) {
                RetrievedChunk chunk = results.get(rank);
                String key = dedupKey(chunk);
                scores.merge(key, 1.0 / (k + rank + 1), Double::sum);

                chunks.merge(key, chunk, (existing, incoming) ->
                    betterGrade(existing.grade(), incoming.grade()) == incoming.grade()
                        ? existing.withGrade(incoming.grade()) : existing);
            }
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<RetrievedChunk> result = new ArrayList<>(Math.min(sorted.size(), maxResults));
        for (int i = 0; i < Math.min(sorted.size(), maxResults); i++) {
            Map.Entry<String, Double> entry = sorted.get(i);
            RetrievedChunk original = chunks.get(entry.getKey());
            result.add(new RetrievedChunk(original.content(), original.sourceDocumentId(),
                entry.getValue(), original.metadata(), original.grade()));
        }
        return List.copyOf(result);
    }

    private static String dedupKey(RetrievedChunk c) {
        return c.sourceDocumentId() + "\0" + c.content();
    }

    private static RelevanceGrade betterGrade(RelevanceGrade a, RelevanceGrade b) {
        return gradeRank(a) <= gradeRank(b) ? a : b;
    }

    private static int gradeRank(RelevanceGrade g) {
        return switch (g) {
            case CORRECT -> 0;
            case AMBIGUOUS -> 1;
            case UNGRADED -> 2;
            case INCORRECT -> 3;
        };
    }
}
