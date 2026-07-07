package io.casehub.neocortex.rag.crossencoder.reranking;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RerankingCaseRetrieverTest {

    static final CorpusRef CORPUS = new CorpusRef("t1", "c1");

    @Test
    void overfetch_usesMaxOfLimitAndPoolSize() {
        AtomicInteger capturedLimit = new AtomicInteger();
        CaseRetriever delegate = (q, c, max, f) -> {
            capturedLimit.set(max);
            return List.of(chunk("a", "d1", 0.5), chunk("b", "d2", 0.6));
        };

        var reranker = new CrossEncoderReranker(RerankingLogicTest.stubModel(0.3f, 0.8f));
        var retriever = new RerankingCaseRetriever(delegate, reranker, config(30));

        retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 10, null);

        assertThat(capturedLimit.get()).isEqualTo(30); // max(10, 30)
    }

    @Test
    void rerank_sortsOutputByCrossEncoderScore() {
        CaseRetriever delegate = (q, c, max, f) -> List.of(
            chunk("low-ce", "d1", 0.9),   // high original, low CE
            chunk("high-ce", "d2", 0.1)); // low original, high CE

        var reranker = new CrossEncoderReranker(RerankingLogicTest.stubModel(0.1f, 0.9f));
        var retriever = new RerankingCaseRetriever(delegate, reranker, config(30));

        var results = retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 2, null);

        assertThat(results.get(0).content()).isEqualTo("high-ce");
        assertThat(results.get(1).content()).isEqualTo("low-ce");
    }

    @Test
    void rerank_truncatesToMaxResults() {
        CaseRetriever delegate = (q, c, max, f) -> List.of(
            chunk("a", "d1", 0.5),
            chunk("b", "d2", 0.6),
            chunk("c", "d3", 0.7));

        var reranker = new CrossEncoderReranker(
            RerankingLogicTest.stubModel(0.3f, 0.8f, 0.5f));
        var retriever = new RerankingCaseRetriever(delegate, reranker, config(30));

        var results = retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 2, null);

        assertThat(results).hasSize(2);
    }

    @Test
    void alreadyReranked_passesThrough() {
        var stamped = RerankingLogic.stamp(List.of(chunk("a", "d1", 0.5)));
        CaseRetriever delegate = (q, c, max, f) -> stamped;

        var retriever = new RerankingCaseRetriever(delegate, null, config(30));

        var results = retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("a");
    }

    @Test
    void stampsOutputToPreventBridgeReentry() {
        CaseRetriever delegate = (q, c, max, f) ->
            List.of(chunk("a", "d1", 0.5));

        var reranker = new CrossEncoderReranker(RerankingLogicTest.stubModel(0.7f));
        var retriever = new RerankingCaseRetriever(delegate, reranker, config(30));

        var results = retriever.retrieve(RetrievalQuery.of("q"), CORPUS, 10, null);

        assertThat(RerankingLogic.isAlreadyReranked(results)).isTrue();
    }

    static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }

    static RerankingConfig config(int poolSize) {
        return new RerankingConfig() {
            @Override public boolean enabled() { return true; }
            @Override public int rerankPoolSize() { return poolSize; }
        };
    }
}
