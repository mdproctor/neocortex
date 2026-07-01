package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class YamlFrontmatterExtractorTest {

    private final YamlFrontmatterExtractor extractor = new YamlFrontmatterExtractor();

    @Test
    void extractsFrontmatterAndBody() {
        String content = """
                ---
                id: doc-123
                title: Example Document
                tags: guide, tutorial
                ---
                # Main Content

                This is the actual document body.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo("# Main Content\n\nThis is the actual document body.");
        assertThat(result.metadata())
                .containsOnly(
                        entry("id", "doc-123"),
                        entry("title", "Example Document"),
                        entry("tags", "guide, tutorial")
                );
    }

    @Test
    void noFrontmatterReturnsEntireContentAsBody() {
        String content = """
                # Regular Document

                No frontmatter here, just content.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo(content);
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void emptyContentReturnsEmptyBodyAndMetadata() {
        byte[] content = new byte[0];

        ExtractionResult result = extractor.extract("test.md", content);

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void onlyFrontmatterNoBody() {
        String content = """
                ---
                id: doc-123
                title: Just Metadata
                ---
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata())
                .containsOnly(
                        entry("id", "doc-123"),
                        entry("title", "Just Metadata")
                );
    }

    @Test
    void frontmatterWithColonsInValues() {
        String content = """
                ---
                url: https://example.com/path
                note: key: value pair
                ---
                Body content.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo("Body content.");
        assertThat(result.metadata())
                .containsOnly(
                        entry("url", "https://example.com/path"),
                        entry("note", "key: value pair")
                );
    }

    @Test
    void frontmatterWithQuotedValues() {
        String content = """
                ---
                title: "Double Quoted Title"
                subtitle: 'Single Quoted Subtitle'
                plain: No Quotes Here
                ---
                Body content.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo("Body content.");
        assertThat(result.metadata())
                .containsOnly(
                        entry("title", "Double Quoted Title"),
                        entry("subtitle", "Single Quoted Subtitle"),
                        entry("plain", "No Quotes Here")
                );
    }

    @Test
    void unclosedFrontmatterTreatsAsNoFrontmatter() {
        String content = """
                ---
                id: doc-123
                title: Unclosed

                This looks like it might be frontmatter but never closes.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo(content);
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void frontmatterMustStartAtLineOne() {
        String content = """
                Some content first.
                ---
                id: doc-123
                ---
                More content.
                """;

        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.body()).isEqualTo(content);
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void handlesWindowsLineEndings() {
        String content = "---\r\ntitle: Win Doc\r\ntags: [java]\r\n---\r\nBody here.";
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("title", "Win Doc");
        assertThat(result.metadata()).containsEntry("tags", "[java]");
        assertThat(result.body()).isEqualTo("Body here.");
    }
}
