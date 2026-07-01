package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryQueryExpander;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveQueryExpandingCaseRetrieverTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void delegatesWithExpandedQuery() {
        var capturedQuery = new AtomicReference<RetrievalQuery>();
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQuery.set(query);
            return Uni.createFrom().item(List.of(chunk("result", "doc1", 0.9)));
        };

        var retriever = new ReactiveQueryExpandingCaseRetriever(delegate, new InMemoryQueryExpander());
        var results = retriever.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(capturedQuery.get().text()).isEqualTo("original");
        assertThat(capturedQuery.get().expandedText()).isEqualTo("hypothetical: original");
    }

    @Test
    void failSafeOnExpanderError() {
        var capturedQuery = new AtomicReference<RetrievalQuery>();
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQuery.set(query);
            return Uni.createFrom().item(List.of(chunk("result", "doc1", 0.9)));
        };
        QueryExpander failingExpander = query -> {
            throw new RuntimeException("LLM timeout");
        };

        var retriever = new ReactiveQueryExpandingCaseRetriever(delegate, failingExpander);
        var results = retriever.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(capturedQuery.get().expandedText()).isNull();
    }

    @Test
    void multiQueryFansOutConcurrently() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return Uni.createFrom().item(List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9)));
        };
        QueryExpander multiExpander = query -> List.of(
            query,
            RetrievalQuery.of("abstract version")
        );

        var decorator = new ReactiveQueryExpandingCaseRetriever(delegate, multiExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(capturedQueries).hasSize(2);
        assertThat(results).hasSize(2);
    }

    @Test
    void emptyExpansionFallsBackToOriginal() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return Uni.createFrom().item(List.of(chunk("result", "doc1", 0.9)));
        };
        QueryExpander emptyExpander = query -> List.of();

        var decorator = new ReactiveQueryExpandingCaseRetriever(delegate, emptyExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(capturedQueries).hasSize(1);
        assertThat(capturedQueries.get(0).expandedText()).isNull();
    }

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }
}
