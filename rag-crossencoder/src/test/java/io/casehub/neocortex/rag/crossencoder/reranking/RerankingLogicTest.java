package io.casehub.neocortex.rag.crossencoder.reranking;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class RerankingLogicTest {

    @Test
    void rerank_sortsAndTruncatesByScore() {
        var reranker = new CrossEncoderReranker(stubModel(0.9f, 0.1f, 0.5f));
        var chunks = List.of(
            chunk("high", "d1", 0.8),
            chunk("low", "d2", 0.9),
            chunk("mid", "d3", 0.7));

        List<RetrievedChunk> result = RerankingLogic.rerank(reranker, "query", chunks, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("high");  // score 0.9
        assertThat(result.get(1).content()).isEqualTo("mid");   // score 0.5
    }

    @Test
    void rerank_usesPrecomputedScoresWhenAvailable() {
        var chunks = List.of(
            chunkWithScore("a", "d1", 0.3, 0.1f),
            chunkWithScore("b", "d2", 0.8, 0.9f));

        // reranker should NOT be called — pass null to prove it
        List<RetrievedChunk> result = RerankingLogic.rerank(null, "query", chunks, 2);

        assertThat(result.get(0).content()).isEqualTo("b");  // precomputed 0.9
        assertThat(result.get(1).content()).isEqualTo("a");  // precomputed 0.1
    }

    @Test
    void attachScores_writesScoresToMetadata() {
        var chunks = List.of(chunk("a", "d1", 0.5), chunk("b", "d2", 0.6));
        float[] scores = {0.8f, 0.2f};

        List<RetrievedChunk> scored = RerankingLogic.attachScores(chunks, scores);

        assertThat(scored.get(0).metadata()).containsEntry(RerankingLogic.SCORE_KEY, "0.8");
        assertThat(scored.get(1).metadata()).containsEntry(RerankingLogic.SCORE_KEY, "0.2");
    }

    @Test
    void hasPrecomputedScores_trueWhenAllHaveKey() {
        var chunks = List.of(
            chunkWithScore("a", "d1", 0.5, 0.8f),
            chunkWithScore("b", "d2", 0.6, 0.2f));
        assertThat(RerankingLogic.hasPrecomputedScores(chunks)).isTrue();
    }

    @Test
    void hasPrecomputedScores_falseWhenNoneHaveKey() {
        var chunks = List.of(chunk("a", "d1", 0.5));
        assertThat(RerankingLogic.hasPrecomputedScores(chunks)).isFalse();
    }

    @Test
    void hasPrecomputedScores_falseOnPartialScores() {
        var chunks = List.of(
            chunkWithScore("a", "d1", 0.5, 0.8f),
            chunk("b", "d2", 0.6));
        assertThat(RerankingLogic.hasPrecomputedScores(chunks)).isFalse();
    }

    @Test
    void stamp_preventsDoubleApplication() {
        var chunks = List.of(chunk("a", "d1", 0.5));
        assertThat(RerankingLogic.isAlreadyReranked(chunks)).isFalse();

        List<RetrievedChunk> stamped = RerankingLogic.stamp(chunks);
        assertThat(RerankingLogic.isAlreadyReranked(stamped)).isTrue();
    }

    @Test
    void stamp_isDistinctFromScoreKey() {
        var scored = List.of(chunkWithScore("a", "d1", 0.5, 0.8f));
        assertThat(RerankingLogic.isAlreadyReranked(scored)).isFalse();
    }

    @Test
    void rerank_emptyInputReturnsEmpty() {
        List<RetrievedChunk> result = RerankingLogic.rerank(null, "q", List.of(), 10);
        assertThat(result).isEmpty();
    }

    // --- helpers ---

    static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }

    static RetrievedChunk chunkWithScore(String content, String docId,
                                          double relevance, float ceScore) {
        return new RetrievedChunk(content, docId, relevance,
            Map.of(RerankingLogic.SCORE_KEY, String.valueOf(ceScore)));
    }

    static InferenceModel stubModel(float... scores) {
        return new InferenceModel() {
            int idx = 0;
            @Override public InferenceOutput run(InferenceInput input) {
                return InferenceOutput.of(scores[idx++]);
            }
            @Override public List<InferenceOutput> runBatch(List<InferenceInput> inputs) {
                return inputs.stream()
                    .map(i -> InferenceOutput.of(scores[idx++]))
                    .toList();
            }
            @Override public OptionalInt outputSize() { return OptionalInt.of(1); }
            @Override public void close() {}
        };
    }
}
