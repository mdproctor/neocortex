package io.casehub.neocortex.examples.rag;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

public final class HybridSearchDemo {

    public record SearchResult(String query, List<RetrievedChunk> chunks) {}

    static final List<String> QUERIES = List.of(
        "How does dependency injection work?",
        "What happened with interest rates?",
        "Can I end my lease early?"
    );

    public static List<SearchResult> run(CaseRetriever retriever, CorpusRef corpus) {
        List<SearchResult> results = new ArrayList<>();
        for (String query : QUERIES) {
            List<RetrievedChunk> chunks = retriever.retrieve(RetrievalQuery.of(query), corpus, 5, null);
            results.add(new SearchResult(query, chunks));
        }
        return results;
    }

    public static void printResults(List<SearchResult> results) {
        for (SearchResult sr : results) {
            System.out.printf("%n=== Query: %s ===%n", sr.query());
            System.out.println("Under the hood: dense top-20 + sparse top-20 → RRF fusion → cross-encoder rerank → top-5");
            System.out.printf("%-4s %6s  %-30s %-8s  %s%n", "Rank", "Score", "Source", "Domain", "Snippet");
            System.out.println("-".repeat(120));
            for (int i = 0; i < sr.chunks().size(); i++) {
                RetrievedChunk chunk = sr.chunks().get(i);
                String domain = chunk.metadata().getOrDefault("domain", "?");
                System.out.printf("%-4d %6.3f  %-30s %-8s  %s%n",
                    i + 1, chunk.relevanceScore(),
                    truncate(chunk.sourceDocumentId(), 28),
                    domain,
                    truncate(chunk.content(), 50));
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
