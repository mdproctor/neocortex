package io.casehub.neocortex.rag.tika;

import io.casehub.neocortex.rag.ChunkInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TikaDocumentParserTest {

    @Test
    void parsePlainText() {
        String text = "First sentence of the document. Second sentence continues here. " +
            "Third sentence adds more content for chunking.";
        var input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));

        var parser = new TikaDocumentParser(500, 50);
        List<ChunkInput> chunks = parser.parse(input, "doc-1", "text/plain", Map.of("author", "test"));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sourceDocumentId()).isEqualTo("doc-1");
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.metadata()).containsEntry("author", "test");
        });
    }

    @Test
    void chunksSplitLargeContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("Sentence number ").append(i).append(" with enough words to fill space. ");
        }
        var input = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));

        var parser = new TikaDocumentParser(200, 20);
        List<ChunkInput> chunks = parser.parse(input, "doc-2", "text/plain", Map.of());

        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void metadataMergedWithTikaExtracted() {
        var input = new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));
        var parser = new TikaDocumentParser(500, 50);
        List<ChunkInput> chunks = parser.parse(input, "doc-3", "text/plain", Map.of("custom", "val"));

        assertThat(chunks.get(0).metadata()).containsEntry("custom", "val");
    }
}
