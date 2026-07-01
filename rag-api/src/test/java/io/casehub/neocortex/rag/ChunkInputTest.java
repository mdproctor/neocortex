package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
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

    @Test
    void backwardCompatibleConstructor() {
        var chunk = new ChunkInput("content", "doc-1", Map.of("key", "val"));
        assertThat(chunk.listMetadata()).isEmpty();
    }

    @Test
    void fullConstructorWithListMetadata() {
        var chunk = new ChunkInput("content", "doc-1", Map.of("domain", "jvm"),
            Map.of("tags", List.of("cdi", "quarkus")));
        assertThat(chunk.listMetadata().get("tags")).containsExactly("cdi", "quarkus");
    }

    @Test
    void listMetadataIsImmutable() {
        var chunk = new ChunkInput("content", "doc-1", Map.of(),
            Map.of("tags", List.of("one")));
        assertThatThrownBy(() -> chunk.listMetadata().put("new", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metadataRejectsContentKey() {
        assertThatThrownBy(() -> new ChunkInput("text", "doc-1", Map.of("content", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content");
    }

    @Test
    void metadataRejectsSourceDocumentIdKey() {
        assertThatThrownBy(() -> new ChunkInput("text", "doc-1", Map.of("sourceDocumentId", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId");
    }

    @Test
    void metadataAllowsNonReservedKeys() {
        var chunk = new ChunkInput("text", "doc-1", Map.of("domain", "jvm", "author", "mdp"));
        assertThat(chunk.metadata()).containsExactlyInAnyOrderEntriesOf(
            Map.of("domain", "jvm", "author", "mdp"));
    }

    @Test
    void listMetadataRejectsContentKey() {
        assertThatThrownBy(() -> new ChunkInput("text", "doc-1", Map.of(),
            Map.of("content", List.of("x"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content");
    }

    @Test
    void listMetadataRejectsSourceDocumentIdKey() {
        assertThatThrownBy(() -> new ChunkInput("text", "doc-1", Map.of(),
            Map.of("sourceDocumentId", List.of("x"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceDocumentId");
    }

    @Test
    void listMetadataAllowsNonReservedKeys() {
        var chunk = new ChunkInput("text", "doc-1", Map.of(),
            Map.of("tags", List.of("cdi", "quarkus")));
        assertThat(chunk.listMetadata().get("tags")).containsExactly("cdi", "quarkus");
    }
}
