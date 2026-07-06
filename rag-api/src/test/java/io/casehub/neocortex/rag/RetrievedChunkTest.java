package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievedChunkTest {

    @Test
    void validConstruction() {
        var metadata = Map.of("section", "intro", "page", "1");
        var chunk = new RetrievedChunk("This is the content", "doc-123", 0.87, metadata);

        assertThat(chunk.content()).isEqualTo("This is the content");
        assertThat(chunk.sourceDocumentId()).isEqualTo("doc-123");
        assertThat(chunk.relevanceScore()).isEqualTo(0.87);
        assertThat(chunk.metadata()).containsExactlyInAnyOrderEntriesOf(metadata);
    }

    @Test
    void nullContentThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RetrievedChunk(null, "doc-1", 0.5, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content must not be null");
    }

    @Test
    void nullSourceDocumentIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RetrievedChunk("content", null, 0.5, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId must not be null");
    }

    @Test
    void nullMetadataDefaultsToEmptyMap() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.5, null);
        assertThat(chunk.metadata()).isEmpty();
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var mutableMetadata = new HashMap<String, String>();
        mutableMetadata.put("key1", "value1");

        var chunk = new RetrievedChunk("content", "doc-1", 0.5, mutableMetadata);

        // Mutating the input map should not affect the record
        mutableMetadata.put("key2", "value2");
        assertThat(chunk.metadata()).containsOnlyKeys("key1");
        assertThat(chunk.metadata()).hasSize(1);
    }

    @Test
    void metadataIsUnmodifiable() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.5, Map.of("key", "value"));

        assertThatThrownBy(() -> chunk.metadata().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void valueBasedEquality() {
        var chunk1 = new RetrievedChunk("text", "doc-1", 0.9, Map.of("k", "v"));
        var chunk2 = new RetrievedChunk("text", "doc-1", 0.9, Map.of("k", "v"));
        var chunk3 = new RetrievedChunk("different", "doc-1", 0.9, Map.of("k", "v"));

        assertThat(chunk1).isEqualTo(chunk2);
        assertThat(chunk1).hasSameHashCodeAs(chunk2);
        assertThat(chunk1).isNotEqualTo(chunk3);
    }

    @Test
    void emptyContentIsValid() {
        // RetrievedChunk allows empty content (only null is rejected)
        var chunk = new RetrievedChunk("", "doc-1", 0.5, Map.of());
        assertThat(chunk.content()).isEmpty();
    }

    @Test
    void fourArgConstructorDefaultsToUngraded() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.9, Map.of("k", "v"));
        assertThat(chunk.grade()).isEqualTo(RelevanceGrade.UNGRADED);
    }

    @Test
    void fiveArgConstructorSetsGrade() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.9, Map.of(), RelevanceGrade.CORRECT);
        assertThat(chunk.grade()).isEqualTo(RelevanceGrade.CORRECT);
    }

    @Test
    void withGradeProducesNewChunkPreservingAllFields() {
        var original = new RetrievedChunk("content", "doc-1", 0.9, Map.of("k", "v"));
        var graded = original.withGrade(RelevanceGrade.INCORRECT);
        assertThat(graded.content()).isEqualTo("content");
        assertThat(graded.sourceDocumentId()).isEqualTo("doc-1");
        assertThat(graded.relevanceScore()).isEqualTo(0.9);
        assertThat(graded.metadata()).containsEntry("k", "v");
        assertThat(graded.grade()).isEqualTo(RelevanceGrade.INCORRECT);
        assertThat(original.grade()).isEqualTo(RelevanceGrade.UNGRADED);
    }

    @Test
    void canonicalConstructorValidatesNulls() {
        assertThatThrownBy(() -> new RetrievedChunk(null, "doc", 0.5, Map.of(), RelevanceGrade.UNGRADED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedChunk("c", null, 0.5, Map.of(), RelevanceGrade.UNGRADED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedChunk("c", "doc", 0.5, Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataNullDefaultsToEmptyMapWithGrade() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.9, null);
        assertThat(chunk.metadata()).isEmpty();
        assertThat(chunk.grade()).isEqualTo(RelevanceGrade.UNGRADED);
    }

    @Test
    void withMetadataReturnsNewChunkWithReplacedMetadata() {
        var chunk = new RetrievedChunk("content", "doc-1", 0.9, Map.of("key", "val"));
        var updated = chunk.withMetadata(Map.of("key", "val", "_trackingId", "abc"));
        assertThat(updated.metadata()).containsEntry("_trackingId", "abc");
        assertThat(updated.content()).isEqualTo("content");
        assertThat(updated.sourceDocumentId()).isEqualTo("doc-1");
        assertThat(updated.relevanceScore()).isEqualTo(0.9);
        assertThat(updated.grade()).isEqualTo(RelevanceGrade.UNGRADED);
        assertThat(chunk.metadata()).doesNotContainKey("_trackingId");
    }
}
