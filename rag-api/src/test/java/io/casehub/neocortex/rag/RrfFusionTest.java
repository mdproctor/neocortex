package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    void fuseSingleListReturnsSameOrder() {
        var chunks = List.of(chunk("a", "doc1", 0.9), chunk("b", "doc2", 0.8));
        var result = RrfFusion.fuse(List.of(chunks), 10);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("a");
        assertThat(result.get(1).content()).isEqualTo("b");
    }

    @Test
    void fuseTwoListsMergesAndDeduplicates() {
        var list1 = List.of(chunk("a", "doc1", 0.9), chunk("b", "doc2", 0.8));
        var list2 = List.of(chunk("b", "doc2", 0.7), chunk("c", "doc3", 0.6));
        var result = RrfFusion.fuse(List.of(list1, list2), 10);
        assertThat(result).hasSize(3);
        // "b" appears in both lists → highest RRF score (rank 1 in list1 + rank 0 in list2)
        assertThat(result.get(0).content()).isEqualTo("b");
    }

    @Test
    void fuseRespectsMaxResults() {
        var list1 = List.of(chunk("a", "doc1", 0.9), chunk("b", "doc2", 0.8));
        var list2 = List.of(chunk("c", "doc3", 0.7), chunk("d", "doc4", 0.6));
        var result = RrfFusion.fuse(List.of(list1, list2), 2);
        assertThat(result).hasSize(2);
    }

    @Test
    void fuseEmptyListsReturnsEmpty() {
        var result = RrfFusion.fuse(List.of(), 10);
        assertThat(result).isEmpty();
    }

    @Test
    void fuseTakesBestGradeForDuplicates() {
        var list1 = List.of(new RetrievedChunk("a", "doc1", 0.9, Map.of(), RelevanceGrade.AMBIGUOUS));
        var list2 = List.of(new RetrievedChunk("a", "doc1", 0.8, Map.of(), RelevanceGrade.CORRECT));
        var result = RrfFusion.fuse(List.of(list1, list2), 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).grade()).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void fusePreservesMetadataFromFirstOccurrence() {
        var list1 = List.of(new RetrievedChunk("a", "doc1", 0.9, Map.of("key", "val1")));
        var list2 = List.of(new RetrievedChunk("a", "doc1", 0.8, Map.of("key", "val2")));
        var result = RrfFusion.fuse(List.of(list1, list2), 10);
        assertThat(result.get(0).metadata().get("key")).isEqualTo("val1");
    }

    @Test
    void fuseWithCustomK() {
        var list1 = List.of(chunk("a", "doc1", 0.9));
        var list2 = List.of(chunk("a", "doc1", 0.8));
        var resultK60 = RrfFusion.fuse(List.of(list1, list2), 10, 60);
        var resultK1 = RrfFusion.fuse(List.of(list1, list2), 10, 1);
        // Lower K amplifies rank differences
        assertThat(resultK1.get(0).relevanceScore()).isGreaterThan(resultK60.get(0).relevanceScore());
    }

    @Test
    void gradeOrderingCorrectBeatsAmbiguous() {
        var list1 = List.of(new RetrievedChunk("a", "doc1", 0.9, Map.of(), RelevanceGrade.INCORRECT));
        var list2 = List.of(new RetrievedChunk("a", "doc1", 0.8, Map.of(), RelevanceGrade.AMBIGUOUS));
        var result = RrfFusion.fuse(List.of(list1, list2), 10);
        assertThat(result.get(0).grade()).isEqualTo(RelevanceGrade.AMBIGUOUS);
    }

    private static RetrievedChunk chunk(String content, String docId, double score) {
        return new RetrievedChunk(content, docId, score, Map.of());
    }
}
