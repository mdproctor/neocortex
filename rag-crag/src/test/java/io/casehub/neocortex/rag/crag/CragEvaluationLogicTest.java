package io.casehub.neocortex.rag.crag;

import io.casehub.neocortex.rag.RelevanceGrade;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CragEvaluationLogicTest {

    @Test
    void isAlreadyGraded_emptyList() {
        assertThat(CragEvaluationLogic.isAlreadyGraded(List.of())).isFalse();
    }

    @Test
    void isAlreadyGraded_allGraded() {
        var chunks = List.of(
            chunk("a", "d1", RelevanceGrade.CORRECT),
            chunk("b", "d2", RelevanceGrade.AMBIGUOUS),
            chunk("c", "d3", RelevanceGrade.INCORRECT));
        assertThat(CragEvaluationLogic.isAlreadyGraded(chunks)).isTrue();
    }

    @Test
    void isAlreadyGraded_allUngraded() {
        var chunks = List.of(
            chunk("a", "d1", RelevanceGrade.UNGRADED),
            chunk("b", "d2", RelevanceGrade.UNGRADED));
        assertThat(CragEvaluationLogic.isAlreadyGraded(chunks)).isFalse();
    }

    @Test
    void isAlreadyGraded_mixed() {
        var chunks = List.of(
            chunk("a", "d1", RelevanceGrade.CORRECT),
            chunk("b", "d2", RelevanceGrade.UNGRADED));
        assertThat(CragEvaluationLogic.isAlreadyGraded(chunks)).isFalse();
    }

    @Test
    void gradeChunks_countsAccumulate() {
        var chunks = List.of(
            chunk("a", "d1"), chunk("b", "d2"), chunk("c", "d3"));
        var grades = List.of(
            RelevanceGrade.CORRECT, RelevanceGrade.INCORRECT, RelevanceGrade.AMBIGUOUS);

        var result = CragEvaluationLogic.gradeChunks(chunks, grades);

        assertThat(result.correct()).isEqualTo(1);
        assertThat(result.incorrect()).isEqualTo(1);
        assertThat(result.ambiguous()).isEqualTo(1);
        assertThat(result.graded()).hasSize(3);
        assertThat(result.graded().get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
        assertThat(result.graded().get(1).grade()).isEqualTo(RelevanceGrade.INCORRECT);
        assertThat(result.graded().get(2).grade()).isEqualTo(RelevanceGrade.AMBIGUOUS);
    }

    @Test
    void gradeChunks_dedupKeysUseFullContent() {
        var chunks = List.of(chunk("content-a", "doc1"), chunk("content-b", "doc1"));
        var grades = List.of(RelevanceGrade.CORRECT, RelevanceGrade.CORRECT);

        var result = CragEvaluationLogic.gradeChunks(chunks, grades);

        assertThat(result.seen()).containsExactlyInAnyOrder(
            "doc1\0content-a", "doc1\0content-b");
    }

    @Test
    void filterIncorrect_removesIncorrect() {
        var graded = List.of(
            chunk("a", "d1", RelevanceGrade.CORRECT),
            chunk("b", "d2", RelevanceGrade.INCORRECT),
            chunk("c", "d3", RelevanceGrade.AMBIGUOUS));

        var survivors = CragEvaluationLogic.filterIncorrect(graded);

        assertThat(survivors).hasSize(2);
        assertThat(survivors).extracting(RetrievedChunk::content)
            .containsExactly("a", "c");
    }

    @Test
    void deduplicateExpanded_filtersSeen() {
        Set<String> seen = new HashSet<>(Set.of("doc1\0already-seen"));
        var expanded = List.of(
            chunk("already-seen", "doc1"),
            chunk("new-chunk", "doc2"));

        var deduped = CragEvaluationLogic.deduplicateExpanded(expanded, seen);

        assertThat(deduped).hasSize(1);
        assertThat(deduped.get(0).content()).isEqualTo("new-chunk");
    }

    @Test
    void sortAndTruncate_correctBeforeAmbiguous() {
        var chunks = List.of(
            chunk("ambig", "d1", RelevanceGrade.AMBIGUOUS),
            chunk("correct", "d2", RelevanceGrade.CORRECT));

        var sorted = CragEvaluationLogic.sortAndTruncate(chunks, 10);

        assertThat(sorted.get(0).content()).isEqualTo("correct");
        assertThat(sorted.get(1).content()).isEqualTo("ambig");
    }

    @Test
    void sortAndTruncate_truncatesAtMax() {
        var chunks = List.of(
            chunk("a", "d1", RelevanceGrade.CORRECT),
            chunk("b", "d2", RelevanceGrade.CORRECT),
            chunk("c", "d3", RelevanceGrade.CORRECT));

        var truncated = CragEvaluationLogic.sortAndTruncate(chunks, 2);

        assertThat(truncated).hasSize(2);
    }

    @Test
    void needsExpansion_thresholdBoundaries() {
        assertThat(CragEvaluationLogic.needsExpansion(5, 5, 1)).isFalse();
        assertThat(CragEvaluationLogic.needsExpansion(3, 5, 0)).isFalse();
        assertThat(CragEvaluationLogic.needsExpansion(3, 5, 1)).isTrue();
    }

    @Test
    void buildQualityEvent_fields() {
        var event = CragEvaluationLogic.buildQualityEvent(10, 5, 3, 2, true);

        assertThat(event.totalRetrieved()).isEqualTo(10);
        assertThat(event.totalCorrect()).isEqualTo(5);
        assertThat(event.totalAmbiguous()).isEqualTo(3);
        assertThat(event.totalIncorrect()).isEqualTo(2);
        assertThat(event.evaluated()).isTrue();
        assertThat(event.expandedSearch()).isTrue();
    }

    // -- helpers --

    private static RetrievedChunk chunk(String content, String docId) {
        return new RetrievedChunk(content, docId, 0.9, Map.of());
    }

    private static RetrievedChunk chunk(String content, String docId, RelevanceGrade grade) {
        return new RetrievedChunk(content, docId, 0.9, Map.of(), grade);
    }
}
