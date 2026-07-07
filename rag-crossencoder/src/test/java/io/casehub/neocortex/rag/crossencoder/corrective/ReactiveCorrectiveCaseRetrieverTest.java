package io.casehub.neocortex.rag.crossencoder.corrective;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.ReactiveCaseRetriever;
import io.casehub.neocortex.rag.RelevanceEvaluator;
import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievalQuality;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryRelevanceEvaluator;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReactiveCorrectiveCaseRetrieverTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void alreadyGradedChunksPassThrough() {
        var preGraded = List.of(
            chunk("good", "doc1", 0.9).withGrade(RelevanceGrade.CORRECT),
            chunk("maybe", "doc2", 0.8).withGrade(RelevanceGrade.AMBIGUOUS));
        var delegate = fixedReactiveRetriever(preGraded);

        var evaluatorCalled = new AtomicBoolean(false);
        RelevanceEvaluator evaluator = (query, content) -> {
            evaluatorCalled.set(true);
            return RelevanceGrade.CORRECT;
        };

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(results).hasSize(2);
        assertThat(results).isEqualTo(preGraded);
        assertThat(evaluatorCalled.get()).isFalse();
        assertThat(quality.get()).isNull();
    }

    @Test
    void allCorrectChunksPassThrough() {
        var delegate = fixedReactiveRetriever(
            chunk("good1", "doc1", 0.9), chunk("good2", "doc2", 0.8));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = reactiveCorrective(
            delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(
            c -> assertThat(c.grade()).isEqualTo(RelevanceGrade.CORRECT));
        assertThat(quality.get().evaluated()).isTrue();
        assertThat(quality.get().expandedSearch()).isFalse();
        assertThat(quality.get().totalCorrect()).isEqualTo(2);
    }

    @Test
    void allIncorrectTriggersExpansion() {
        var delegate = fixedReactiveRetriever(
            chunk("bad1", "doc1", 0.9), chunk("bad2", "doc2", 0.8));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = reactiveCorrective(
            delegate, RelevanceGrade.INCORRECT, quality);
        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null)
            .await().indefinitely();

        assertThat(results).isEmpty();
        assertThat(quality.get().expandedSearch()).isTrue();
        assertThat(quality.get().totalIncorrect()).isGreaterThan(0);
    }

    @Test
    void mixedGradesFilterIncorrect() {
        List<RetrievedChunk> chunks = List.of(
            chunk("good", "doc1", 0.9),
            chunk("bad", "doc2", 0.8),
            chunk("maybe", "doc3", 0.7));
        var delegate = fixedReactiveRetriever(chunks);

        var evaluator = gradeByContent(Map.of(
            "good", RelevanceGrade.CORRECT,
            "bad", RelevanceGrade.INCORRECT,
            "maybe", RelevanceGrade.AMBIGUOUS));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 10, null)
            .await().indefinitely();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(RetrievedChunk::content)
            .containsExactly("good", "maybe");
        assertThat(results.get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
        assertThat(results.get(1).grade()).isEqualTo(RelevanceGrade.AMBIGUOUS);
    }

    @Test
    void truncationPrefersCORRECT() {
        List<RetrievedChunk> chunks = List.of(
            chunk("ambig1", "doc1", 0.9),
            chunk("ambig2", "doc2", 0.8),
            chunk("correct1", "doc3", 0.7));
        var delegate = fixedReactiveRetriever(chunks);

        var evaluator = gradeByContent(Map.of(
            "ambig1", RelevanceGrade.AMBIGUOUS,
            "ambig2", RelevanceGrade.AMBIGUOUS,
            "correct1", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 2, null)
            .await().indefinitely();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("correct1");
    }

    @Test
    void emptyCorpusReturnsEmpty() {
        var delegate = fixedReactiveRetriever(List.of());
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = reactiveCorrective(
            delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null)
            .await().indefinitely();

        assertThat(results).isEmpty();
        assertThat(quality.get().expandedSearch()).isFalse();
        assertThat(quality.get().totalRetrieved()).isEqualTo(0);
    }

    @Test
    void smallCorpusNoExpansion() {
        var delegate = fixedReactiveRetriever(chunk("only", "doc1", 0.9));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = reactiveCorrective(
            delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null)
            .await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(quality.get().expandedSearch()).isFalse();
    }

    @Test
    void qualityEventFired() {
        var callCount = new int[]{0};
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return Uni.createFrom().item(List.of(chunk("bad", "doc1", 0.9)));
            }
            return Uni.createFrom().item(List.of(
                chunk("bad", "doc1", 0.9), chunk("good", "doc2", 0.8)));
        };

        var evaluator = gradeByContent(Map.of(
            "bad", RelevanceGrade.INCORRECT,
            "good", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null)
            .await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("good");
        assertThat(quality.get().totalRetrieved()).isEqualTo(2);
        assertThat(quality.get().expandedSearch()).isTrue();
    }

    @Test
    void expansionDeduplicates() {
        var callCount = new int[]{0};
        ReactiveCaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return Uni.createFrom().item(List.of(chunk("seen", "doc1", 0.9)));
            }
            return Uni.createFrom().item(List.of(
                chunk("seen", "doc1", 0.9), chunk("new", "doc2", 0.8)));
        };

        var evaluator = gradeByContent(Map.of(
            "seen", RelevanceGrade.INCORRECT,
            "new", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        var results = retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null)
            .await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("new");
    }

    @Test
    void evaluatorRunsOnWorkerThread() {
        var evaluatorThreadId = new AtomicLong(Thread.currentThread().getId());
        RelevanceEvaluator evaluator = new RelevanceEvaluator() {
            @Override
            public RelevanceGrade evaluate(String query, String chunkContent) {
                evaluatorThreadId.set(Thread.currentThread().getId());
                return RelevanceGrade.CORRECT;
            }
        };

        var delegate = fixedReactiveRetriever(chunk("a", "doc1", 0.9));
        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingAsyncEvent(quality));

        retriever.retrieve(RetrievalQuery.of("query"), CORPUS, 5, null).await().indefinitely();

        assertNotEquals(Thread.currentThread().getId(), evaluatorThreadId.get(),
            "evaluator must execute on a worker thread, not the subscribing thread");
    }

    @Test
    void evaluatesAgainstOriginalQueryNotExpansion() {
        var delegate = fixedReactiveRetriever(chunk("content", "doc1", 0.9));
        var capturedQuery = new AtomicReference<String>();
        RelevanceEvaluator capturingEvaluator = (query, content) -> {
            capturedQuery.set(query);
            return RelevanceGrade.CORRECT;
        };
        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new ReactiveCorrectiveCaseRetriever(
            delegate, capturingEvaluator, stubConfig(3), capturingAsyncEvent(quality));

        var expandedQuery = new RetrievalQuery("original question", "hypothetical document about original question");
        retriever.retrieve(expandedQuery, CORPUS, 10, null)
            .await().indefinitely();

        assertThat(capturedQuery.get()).isEqualTo("original question");
    }

    // -- helpers --

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }

    private static ReactiveCaseRetriever fixedReactiveRetriever(RetrievedChunk... chunks) {
        return fixedReactiveRetriever(List.of(chunks));
    }

    private static ReactiveCaseRetriever fixedReactiveRetriever(List<RetrievedChunk> chunks) {
        return (query, corpus, maxResults, filter) ->
            Uni.createFrom().item(chunks);
    }

    private static ReactiveCorrectiveCaseRetriever reactiveCorrective(
            ReactiveCaseRetriever delegate, RelevanceGrade fixedGrade,
            AtomicReference<RetrievalQuality> qualityCapture) {
        return new ReactiveCorrectiveCaseRetriever(
            delegate,
            InMemoryRelevanceEvaluator.returning(fixedGrade),
            stubConfig(3),
            capturingAsyncEvent(qualityCapture));
    }

    private static CragConfig stubConfig(int expansionMultiplier) {
        return new CragConfig() {
            @Override public double correctThreshold() { return 0.7; }
            @Override public double incorrectThreshold() { return 0.3; }
            @Override public int expansionMultiplier() { return expansionMultiplier; }
            @Override public boolean enabled() { return true; }
        };
    }

    private static Event<RetrievalQuality> capturingAsyncEvent(
            AtomicReference<RetrievalQuality> ref) {
        return new Event<>() {
            @Override public void fire(RetrievalQuality event) { ref.set(event); }
            @Override public Event<RetrievalQuality> select(Annotation... a) { return this; }
            @Override public <U extends RetrievalQuality> Event<U> select(
                Class<U> c, Annotation... a) { return null; }
            @Override public <U extends RetrievalQuality> Event<U> select(
                TypeLiteral<U> t, Annotation... a) { return null; }
            @Override public CompletionStage<RetrievalQuality> fireAsync(
                RetrievalQuality event) {
                ref.set(event);
                return CompletableFuture.completedFuture(event);
            }
            @Override public CompletionStage<RetrievalQuality> fireAsync(
                RetrievalQuality event, NotificationOptions options) {
                return fireAsync(event);
            }
        };
    }

    private static RelevanceEvaluator gradeByContent(
            Map<String, RelevanceGrade> contentToGrade) {
        return (query, chunkContent) ->
            contentToGrade.getOrDefault(chunkContent, RelevanceGrade.UNGRADED);
    }
}
