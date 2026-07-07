package io.casehub.neocortex.rag.crossencoder.corrective;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.crossencoder.reranking.RerankingLogic;
import io.casehub.neocortex.rag.testing.InMemoryCaseRetriever;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScorePropagationTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void correctiveCaseRetriever_attachesScoresToSurvivingChunks() {
        var evaluator = contentScoringEvaluator(Map.of(
            "good1", 0.9f, "good2", 0.8f, "bad", 0.1f));

        var delegate = InMemoryCaseRetriever.returning(List.of(
            chunk("good1", "d1", 0.9),
            chunk("good2", "d2", 0.8),
            chunk("bad", "d3", 0.7)));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("test"), CORPUS, 10, null);

        assertThat(results).hasSize(2);
        assertThat(RerankingLogic.hasPrecomputedScores(results)).isTrue();
        assertThat(results.get(0).metadata().get(RerankingLogic.SCORE_KEY))
            .isEqualTo("0.9");
        assertThat(results.get(1).metadata().get(RerankingLogic.SCORE_KEY))
            .isEqualTo("0.8");
    }

    @Test
    void correctiveCaseRetriever_noScoresWithNonCrossEncoderEvaluator() {
        var delegate = InMemoryCaseRetriever.returning(List.of(
            chunk("good", "d1", 0.9)));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate,
            (query, content) -> io.casehub.neocortex.rag.RelevanceGrade.CORRECT,
            stubConfig(3),
            capturingEvent(quality));

        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("test"), CORPUS, 10, null);

        assertThat(results).hasSize(1);
        assertThat(RerankingLogic.hasPrecomputedScores(results)).isFalse();
    }

    @Test
    void correctiveCaseRetriever_attachesScoresAtExpansionPath() {
        var callCount = new int[]{0};
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return List.of(chunk("bad", "d1", 0.9));
            }
            return List.of(
                chunk("bad", "d1", 0.9),
                chunk("expanded", "d2", 0.8));
        };

        var evaluator = contentScoringEvaluator(Map.of(
            "bad", 0.1f, "expanded", 0.85f));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        List<RetrievedChunk> results = retriever.retrieve(
            RetrievalQuery.of("test"), CORPUS, 5, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("expanded");
        assertThat(results.get(0).metadata().get(RerankingLogic.SCORE_KEY))
            .isEqualTo("0.85");
    }

    // -- helpers --

    private static CrossEncoderRelevanceEvaluator contentScoringEvaluator(
            Map<String, Float> contentToScore) {
        var model = InMemoryInferenceModel.withFunction(1, input -> {
            String candidate = input.texts().get(1);
            Float score = contentToScore.get(candidate);
            return new float[]{score != null ? score : 0.0f};
        });
        var reranker = new CrossEncoderReranker(model);
        return new CrossEncoderRelevanceEvaluator(reranker, 0.7, 0.3);
    }

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }

    private static CragConfig stubConfig(int expansionMultiplier) {
        return new CragConfig() {
            @Override public double correctThreshold() { return 0.7; }
            @Override public double incorrectThreshold() { return 0.3; }
            @Override public int expansionMultiplier() { return expansionMultiplier; }
            @Override public boolean enabled() { return true; }
        };
    }

    private static Event<RetrievalQuality> capturingEvent(
            AtomicReference<RetrievalQuality> ref) {
        return new Event<>() {
            @Override public void fire(RetrievalQuality event) { ref.set(event); }
            @Override public Event<RetrievalQuality> select(Annotation... a) { return this; }
            @Override public <U extends RetrievalQuality> Event<U> select(Class<U> c, Annotation... a) { return null; }
            @Override public <U extends RetrievalQuality> Event<U> select(TypeLiteral<U> t, Annotation... a) { return null; }
            @Override public CompletionStage<RetrievalQuality> fireAsync(RetrievalQuality event) { ref.set(event); return CompletableFuture.completedFuture(event); }
            @Override public CompletionStage<RetrievalQuality> fireAsync(RetrievalQuality event, NotificationOptions options) { return fireAsync(event); }
        };
    }
}
