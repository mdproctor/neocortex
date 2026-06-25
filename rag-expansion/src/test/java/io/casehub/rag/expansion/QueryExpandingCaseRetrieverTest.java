package io.casehub.rag.expansion;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
import io.casehub.rag.testing.InMemoryQueryExpander;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExpandingCaseRetrieverTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void delegatesWithExpandedQuery() {
        var capturedQuery = new AtomicReference<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQuery.set(query);
            return List.of(chunk("result", "doc1", 0.9));
        };
        var expander = new InMemoryQueryExpander();

        var retriever = new QueryExpandingCaseRetriever(delegate, expander);
        var results = retriever.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(capturedQuery.get().text()).isEqualTo("original");
        assertThat(capturedQuery.get().expandedText()).isEqualTo("hypothetical: original");
        assertThat(capturedQuery.get().searchText()).isEqualTo("hypothetical: original");
    }

    @Test
    void failSafeOnExpanderError() {
        var capturedQuery = new AtomicReference<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQuery.set(query);
            return List.of(chunk("result", "doc1", 0.9));
        };
        QueryExpander failingExpander = query -> {
            throw new RuntimeException("LLM timeout");
        };

        var retriever = new QueryExpandingCaseRetriever(delegate, failingExpander);
        var results = retriever.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(capturedQuery.get().text()).isEqualTo("original");
        assertThat(capturedQuery.get().expandedText()).isNull();
    }

    @Test
    void passesCorpusAndFilterThrough() {
        var capturedCorpus = new AtomicReference<CorpusRef>();
        var capturedMax = new int[1];
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedCorpus.set(corpus);
            capturedMax[0] = maxResults;
            return List.of();
        };

        var retriever = new QueryExpandingCaseRetriever(delegate, new InMemoryQueryExpander());
        retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 7, null);

        assertThat(capturedCorpus.get()).isEqualTo(CORPUS);
        assertThat(capturedMax[0]).isEqualTo(7);
    }

    @Test
    void multiQueryFansOutAndMergesViaRrf() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        QueryExpander multiExpander = query -> List.of(
            query,
            RetrievalQuery.of("abstract version")
        );

        var decorator = new QueryExpandingCaseRetriever(delegate, multiExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        assertThat(capturedQueries).hasSize(2);
        assertThat(capturedQueries.get(0).text()).isEqualTo("original");
        assertThat(capturedQueries.get(1).text()).isEqualTo("abstract version");
        assertThat(results).hasSize(2);
    }

    @Test
    void singleQuerySkipsRrf() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk("result", "doc1", 0.9));
        };
        QueryExpander singleExpander = query -> List.of(query.withExpansion("expanded"));

        var decorator = new QueryExpandingCaseRetriever(delegate, singleExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        assertThat(capturedQueries).hasSize(1);
        assertThat(results).hasSize(1);
    }

    @Test
    void emptyExpansionFallsBackToOriginal() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk("result", "doc1", 0.9));
        };
        QueryExpander emptyExpander = query -> List.of();

        var decorator = new QueryExpandingCaseRetriever(delegate, emptyExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        assertThat(capturedQueries).hasSize(1);
        assertThat(capturedQueries.get(0).text()).isEqualTo("original");
        assertThat(capturedQueries.get(0).expandedText()).isNull();
        assertThat(results).hasSize(1);
    }

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }
}
