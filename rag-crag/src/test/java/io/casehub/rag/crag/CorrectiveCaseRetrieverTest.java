package io.casehub.rag.crag;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RelevanceEvaluator;
import io.casehub.rag.RelevanceGrade;
import io.casehub.rag.RetrievalQuality;
import io.casehub.rag.RetrievedChunk;
import io.casehub.rag.testing.InMemoryCaseRetriever;
import io.casehub.rag.testing.InMemoryRelevanceEvaluator;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectiveCaseRetrieverTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant-1", "test-corpus");

    @Test
    void allCorrect_returnsAllChunksWithGrades() {
        var delegate = fixedRetriever(
            chunk("good1", "doc1", 0.9), chunk("good2", "doc2", 0.8));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = corrective(delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve("query", CORPUS, 10, null);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(c -> assertThat(c.grade()).isEqualTo(RelevanceGrade.CORRECT));
        assertThat(quality.get().evaluated()).isTrue();
        assertThat(quality.get().expandedSearch()).isFalse();
        assertThat(quality.get().totalCorrect()).isEqualTo(2);
        assertThat(quality.get().totalIncorrect()).isEqualTo(0);
    }

    @Test
    void allIncorrect_filtersAll_triggersExpansion() {
        var delegate = fixedRetriever(
            chunk("bad1", "doc1", 0.9), chunk("bad2", "doc2", 0.8));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = corrective(delegate, RelevanceGrade.INCORRECT, quality);
        var results = retriever.retrieve("query", CORPUS, 5, null);

        assertThat(results).isEmpty();
        assertThat(quality.get().expandedSearch()).isTrue();
        assertThat(quality.get().totalIncorrect()).isGreaterThan(0);
    }

    @Test
    void mixedGrades_filtersIncorrect_keepsCorrectAndAmbiguous() {
        List<RetrievedChunk> chunks = List.of(
            chunk("good", "doc1", 0.9),
            chunk("bad", "doc2", 0.8),
            chunk("maybe", "doc3", 0.7));
        var delegate = InMemoryCaseRetriever.returning(chunks);

        var evaluator = gradeByContent(Map.of(
            "good", RelevanceGrade.CORRECT,
            "bad", RelevanceGrade.INCORRECT,
            "maybe", RelevanceGrade.AMBIGUOUS));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        var results = retriever.retrieve("query", CORPUS, 10, null);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(RetrievedChunk::content)
            .containsExactly("good", "maybe");
        assertThat(results.get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
        assertThat(results.get(1).grade()).isEqualTo(RelevanceGrade.AMBIGUOUS);
        assertThat(quality.get().totalCorrect()).isEqualTo(1);
        assertThat(quality.get().totalAmbiguous()).isEqualTo(1);
        assertThat(quality.get().totalIncorrect()).isEqualTo(1);
    }

    @Test
    void truncation_prefersCorrectOverAmbiguous() {
        List<RetrievedChunk> chunks = List.of(
            chunk("ambig1", "doc1", 0.9),
            chunk("ambig2", "doc2", 0.8),
            chunk("correct1", "doc3", 0.7));
        var delegate = InMemoryCaseRetriever.returning(chunks);

        var evaluator = gradeByContent(Map.of(
            "ambig1", RelevanceGrade.AMBIGUOUS,
            "ambig2", RelevanceGrade.AMBIGUOUS,
            "correct1", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        var results = retriever.retrieve("query", CORPUS, 2, null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("correct1");
        assertThat(results.get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void emptyInitialRetrieval_noExpansion_noIncorrectChunksToCorrect() {
        var delegate = InMemoryCaseRetriever.returning(List.of());
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = corrective(delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve("query", CORPUS, 5, null);

        assertThat(results).isEmpty();
        assertThat(quality.get().expandedSearch()).isFalse();
        assertThat(quality.get().totalRetrieved()).isEqualTo(0);
    }

    @Test
    void smallCorpus_allCorrect_noExpansion() {
        var delegate = fixedRetriever(chunk("only", "doc1", 0.9));
        var quality = new AtomicReference<RetrievalQuality>();

        var retriever = corrective(delegate, RelevanceGrade.CORRECT, quality);
        var results = retriever.retrieve("query", CORPUS, 5, null);

        assertThat(results).hasSize(1);
        assertThat(quality.get().expandedSearch()).isFalse();
        assertThat(quality.get().totalCorrect()).isEqualTo(1);
    }

    @Test
    void qualityEvent_countsReflectInitialAndExpansion() {
        var callCount = new int[]{0};
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return List.of(chunk("bad", "doc1", 0.9));
            }
            return List.of(
                chunk("bad", "doc1", 0.9),
                chunk("good", "doc2", 0.8));
        };

        var evaluator = gradeByContent(Map.of(
            "bad", RelevanceGrade.INCORRECT,
            "good", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        var results = retriever.retrieve("query", CORPUS, 5, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("good");
        assertThat(quality.get().totalRetrieved()).isEqualTo(2);
        assertThat(quality.get().totalCorrect()).isEqualTo(1);
        assertThat(quality.get().totalIncorrect()).isEqualTo(1);
        assertThat(quality.get().expandedSearch()).isTrue();
    }

    @Test
    void expansion_deduplicates_alreadySeenChunks() {
        var callCount = new int[]{0};
        CaseRetriever delegate = (query, corpus, maxResults, filter) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return List.of(chunk("seen", "doc1", 0.9));
            }
            return List.of(
                chunk("seen", "doc1", 0.9),
                chunk("new", "doc2", 0.8));
        };

        var evaluator = gradeByContent(Map.of(
            "seen", RelevanceGrade.INCORRECT,
            "new", RelevanceGrade.CORRECT));

        var quality = new AtomicReference<RetrievalQuality>();
        var retriever = new CorrectiveCaseRetriever(
            delegate, evaluator, stubConfig(3), capturingEvent(quality));

        var results = retriever.retrieve("query", CORPUS, 5, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("new");
        assertThat(quality.get().totalRetrieved()).isEqualTo(2);
    }

    // -- helpers --

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }

    private static CaseRetriever fixedRetriever(RetrievedChunk... chunks) {
        return InMemoryCaseRetriever.returning(List.of(chunks));
    }

    private static CorrectiveCaseRetriever corrective(
            CaseRetriever delegate, RelevanceGrade fixedGrade,
            AtomicReference<RetrievalQuality> qualityCapture) {
        return new CorrectiveCaseRetriever(
            delegate,
            InMemoryRelevanceEvaluator.returning(fixedGrade),
            stubConfig(3),
            capturingEvent(qualityCapture));
    }

    private static CragConfig stubConfig(int expansionMultiplier) {
        return new CragConfig() {
            @Override public double correctThreshold() { return 0.7; }
            @Override public double incorrectThreshold() { return 0.3; }
            @Override public int expansionMultiplier() { return expansionMultiplier; }
        };
    }

    private static Event<RetrievalQuality> capturingEvent(AtomicReference<RetrievalQuality> ref) {
        return new Event<>() {
            @Override public void fire(RetrievalQuality event) { ref.set(event); }
            @Override public Event<RetrievalQuality> select(Annotation... a) { return this; }
            @Override public <U extends RetrievalQuality> Event<U> select(Class<U> c, Annotation... a) { return null; }
            @Override public <U extends RetrievalQuality> Event<U> select(TypeLiteral<U> t, Annotation... a) { return null; }
            @Override public CompletionStage<RetrievalQuality> fireAsync(RetrievalQuality event) { ref.set(event); return CompletableFuture.completedFuture(event); }
            @Override public CompletionStage<RetrievalQuality> fireAsync(RetrievalQuality event, NotificationOptions options) { return fireAsync(event); }
        };
    }

    private static RelevanceEvaluator gradeByContent(Map<String, RelevanceGrade> contentToGrade) {
        return (query, chunkContent) -> contentToGrade.getOrDefault(chunkContent, RelevanceGrade.UNGRADED);
    }
}
