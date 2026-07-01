package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.neocortex.corpus.ChangeSource;
import io.casehub.neocortex.corpus.CorpusReader;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusIngestionBindingTest {

    private static final CorpusRef CORPUS = new CorpusRef("tenant", "corpus");
    private static final ChangeSource CHANGE_SOURCE = new ChangeSource() {
        @Override
        public ChangeSet changesSince(String cursor) {
            return new ChangeSet(List.of(), "{}");
        }

        @Override
        public ChangeSet fullScan() {
            return new ChangeSet(List.of(), "{}");
        }
    };
    private static final CorpusReader READER = new CorpusReader() {
        @Override
        public Optional<byte[]> read(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<java.io.InputStream> readStream(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> readVersion(String path, int version) {
            return Optional.empty();
        }

        @Override
        public List<io.casehub.neocortex.corpus.VersionInfo> versions(String path) {
            return List.of();
        }

        @Override
        public List<String> list() {
            return List.of();
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        @Override
        public boolean exists(String path) {
            return false;
        }
    };
    private static final MetadataExtractor EXTRACTOR = (path, content) ->
            new ExtractionResult(new String(content), Map.of());

    @Test
    void validConstruction() {
        var binding = new CorpusIngestionBinding(
                "my-corpus",
                CORPUS,
                CHANGE_SOURCE,
                READER,
                EXTRACTOR
        );

        assertThat(binding.name()).isEqualTo("my-corpus");
        assertThat(binding.corpusRef()).isEqualTo(CORPUS);
        assertThat(binding.changeSource()).isEqualTo(CHANGE_SOURCE);
        assertThat(binding.corpusReader()).isEqualTo(READER);
        assertThat(binding.metadataExtractor()).isEqualTo(EXTRACTOR);
    }

    @Test
    void nullNameThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                null,
                CORPUS,
                CHANGE_SOURCE,
                READER,
                EXTRACTOR
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void blankNameThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                "  ",
                CORPUS,
                CHANGE_SOURCE,
                READER,
                EXTRACTOR
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void nullCorpusRefThrowsNullPointerException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                "my-corpus",
                null,
                CHANGE_SOURCE,
                READER,
                EXTRACTOR
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("corpusRef");
    }

    @Test
    void nullChangeSourceThrowsNullPointerException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                "my-corpus",
                CORPUS,
                null,
                READER,
                EXTRACTOR
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("changeSource");
    }

    @Test
    void nullCorpusReaderThrowsNullPointerException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                "my-corpus",
                CORPUS,
                CHANGE_SOURCE,
                null,
                EXTRACTOR
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("corpusReader");
    }

    @Test
    void nullMetadataExtractorThrowsNullPointerException() {
        assertThatThrownBy(() -> new CorpusIngestionBinding(
                "my-corpus",
                CORPUS,
                CHANGE_SOURCE,
                READER,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadataExtractor");
    }
}
