package io.casehub.neocortex.examples.rag;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryCaseRetriever;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class HybridSearchSmokeTest {

    @Test
    void allQueriesProduceFormattedOutput() {
        List<RetrievedChunk> mockResults = List.of(
            new RetrievedChunk(
                "CDI performs dependency injection at runtime.",
                "tech/cdi-injection.md", 0.85, Map.of("domain", "tech")),
            new RetrievedChunk(
                "The Federal Reserve held rates steady.",
                "news/central-bank-rates.md", 0.72, Map.of("domain", "news")),
            new RetrievedChunk(
                "Early termination requires 90 days notice.",
                "legal/lease-termination.md", 0.68, Map.of("domain", "legal"))
        );
        var retriever = InMemoryCaseRetriever.returning(mockResults);
        var corpus = new CorpusRef("demo-tenant", "examples");

        List<HybridSearchDemo.SearchResult> results = HybridSearchDemo.run(retriever, corpus);

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.query()).isNotBlank();
            assertThat(r.chunks()).isNotEmpty();
        });
    }
}
