package io.casehub.neocortex.rag.expansion;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryQueryExpander;
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
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        var expander = new InMemoryQueryExpander();

        var retriever = new QueryExpandingCaseRetriever(delegate, expander);
        var results = retriever.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        // Original + expanded = 2 queries fanned out
        assertThat(capturedQueries).hasSize(2);
        // First query is the original (no expansion)
        assertThat(capturedQueries.get(0).text()).isEqualTo("original");
        assertThat(capturedQueries.get(0).expandedText()).isNull();
        // Second is the expanded
        assertThat(capturedQueries.get(1).text()).isEqualTo("original");
        assertThat(capturedQueries.get(1).expandedText()).isEqualTo("hypothetical: original");
        assertThat(capturedQueries.get(1).searchText()).isEqualTo("hypothetical: original");
        // Results fused via RRF
        assertThat(results).hasSize(2);
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
    void singleExpandedQueryGetsPrependedOriginal() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        QueryExpander singleExpander = query -> List.of(query.withExpansion("expanded"));

        var decorator = new QueryExpandingCaseRetriever(delegate, singleExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        // Original prepended + expanded = 2 queries, RRF fusion
        assertThat(capturedQueries).hasSize(2);
        assertThat(capturedQueries.get(0).expandedText()).isNull();
        assertThat(capturedQueries.get(1).expandedText()).isEqualTo("expanded");
        assertThat(results).hasSize(2);
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

    @Test
    void prependsOriginalWhenExpanderOmitsIt() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        // Expander returns only the expanded query (like LlmQueryExpander)
        QueryExpander hydeExpander = query -> List.of(query.withExpansion("hypothetical"));

        var decorator = new QueryExpandingCaseRetriever(delegate, hydeExpander);
        var results = decorator.retrieve(RetrievalQuery.of("original"), CORPUS, 10, null);

        // Original + expanded = 2 queries fanned out
        assertThat(capturedQueries).hasSize(2);
        // First query is the original (no expansion)
        assertThat(capturedQueries.get(0).expandedText()).isNull();
        assertThat(capturedQueries.get(0).text()).isEqualTo("original");
        // Second is the expanded
        assertThat(capturedQueries.get(1).expandedText()).isEqualTo("hypothetical");
        assertThat(results).hasSize(2);
    }

    @Test
    void doesNotDuplicateOriginalWhenExpanderIncludesIt() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        // StepBack-style: expander already includes original
        var original = RetrievalQuery.of("original");
        QueryExpander stepBackExpander = query -> List.of(query, RetrievalQuery.of("abstract"));

        var decorator = new QueryExpandingCaseRetriever(delegate, stepBackExpander);
        decorator.retrieve(original, CORPUS, 10, null);

        assertThat(capturedQueries).hasSize(2);
        assertThat(capturedQueries.get(0)).isEqualTo(original);
    }

    @Test
    void prependsOriginalForReformulatedQueryWithoutExpansion() {
        var capturedQueries = new ArrayList<RetrievalQuery>();
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            capturedQueries.add(query);
            return List.of(chunk(query.searchText(), "doc-" + capturedQueries.size(), 0.9));
        };
        // Custom expander returns a reformulated query (no withExpansion)
        QueryExpander reformulator = query -> List.of(RetrievalQuery.of("reformulated"));

        var original = RetrievalQuery.of("original");
        var decorator = new QueryExpandingCaseRetriever(delegate, reformulator);
        decorator.retrieve(original, CORPUS, 10, null);

        // Original prepended because contains() uses record equality
        assertThat(capturedQueries).hasSize(2);
        assertThat(capturedQueries.get(0)).isEqualTo(original);
        assertThat(capturedQueries.get(1).text()).isEqualTo("reformulated");
    }

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }
}
