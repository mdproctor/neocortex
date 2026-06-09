package io.casehub.rag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkInputTest {

    @Test
    void validConstruction() {
        var metadata = Map.of("section", "intro", "page", "1");
        var chunk = new ChunkInput("This is the content", "doc-123", metadata);

        assertThat(chunk.content()).isEqualTo("This is the content");
        assertThat(chunk.sourceDocumentId()).isEqualTo("doc-123");
        assertThat(chunk.metadata()).containsExactlyInAnyOrderEntriesOf(metadata);
    }

    @Test
    void nullContentThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChunkInput(null, "doc-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content must not be null or blank");
    }

    @Test
    void blankContentThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChunkInput("", "doc-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content must not be null or blank");

        assertThatThrownBy(() -> new ChunkInput("  ", "doc-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content must not be null or blank");
    }

    @Test
    void nullSourceDocumentIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChunkInput("content", null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId must not be null or blank");
    }

    @Test
    void blankSourceDocumentIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChunkInput("content", "", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId must not be null or blank");

        assertThatThrownBy(() -> new ChunkInput("content", "  ", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId must not be null or blank");
    }

    @Test
    void nullMetadataDefaultsToEmptyMap() {
        var chunk = new ChunkInput("content", "doc-1", null);
        assertThat(chunk.metadata()).isEmpty();
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var mutableMetadata = new HashMap<String, String>();
        mutableMetadata.put("key1", "value1");

        var chunk = new ChunkInput("content", "doc-1", mutableMetadata);

        // Mutating the input map should not affect the record
        mutableMetadata.put("key2", "value2");
        assertThat(chunk.metadata()).containsOnlyKeys("key1");
        assertThat(chunk.metadata()).hasSize(1);
    }

    @Test
    void metadataIsUnmodifiable() {
        var chunk = new ChunkInput("content", "doc-1", Map.of("key", "value"));

        assertThatThrownBy(() -> chunk.metadata().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void valueBasedEquality() {
        var chunk1 = new ChunkInput("text", "doc-1", Map.of("k", "v"));
        var chunk2 = new ChunkInput("text", "doc-1", Map.of("k", "v"));
        var chunk3 = new ChunkInput("different", "doc-1", Map.of("k", "v"));

        assertThat(chunk1).isEqualTo(chunk2);
        assertThat(chunk1).hasSameHashCodeAs(chunk2);
        assertThat(chunk1).isNotEqualTo(chunk3);
    }
}
