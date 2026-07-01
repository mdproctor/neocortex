package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusBindingProducerTest {

    @TempDir Path tempDir;

    private MetadataExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = (path, content) -> new ExtractionResult(new String(content), Map.of());
    }

    // --- flat mode ---

    @Test
    void producesBindingForFlatCorpus() throws IOException {
        Path corpusDir = tempDir.resolve("garden");
        Files.createDirectories(corpusDir);
        Files.writeString(corpusDir.resolve("test.md"), "# Test");

        var ingestionConfig = stubIngestionConfig(Map.of(
                "garden", stubCorpusIngestionConfig("default", "garden", IngestionMode.AUTO)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "garden", stubInstanceConfig(corpusDir.toString(), "FLAT", 100 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        assertThat(bindings).hasSize(1);
        CorpusIngestionBinding binding = bindings.getFirst();
        assertThat(binding.name()).isEqualTo("garden");
        assertThat(binding.corpusRef().tenantId()).isEqualTo("default");
        assertThat(binding.corpusRef().corpusName()).isEqualTo("garden");
        assertThat(binding.metadataExtractor()).isSameAs(extractor);

        // Reader works — can read the file we created
        Optional<byte[]> content = binding.corpusReader().read("test.md");
        assertThat(content).isPresent();
        assertThat(new String(content.get())).isEqualTo("# Test");

        // ChangeSource works — detects the file
        var changeSet = binding.changeSource().fullScan();
        assertThat(changeSet.entries()).hasSize(1);
        assertThat(changeSet.entries().getFirst().path()).isEqualTo("test.md");
    }

    // --- zip mode ---

    @Test
    void producesBindingForZipCorpus() throws IOException {
        Path corpusDir = tempDir.resolve("archive");
        Files.createDirectories(corpusDir);

        var ingestionConfig = stubIngestionConfig(Map.of(
                "archive", stubCorpusIngestionConfig("tenant-a", "archive", IngestionMode.AUTO)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "archive", stubInstanceConfig(corpusDir.toString(), "ZIP", 50 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        assertThat(bindings).hasSize(1);
        CorpusIngestionBinding binding = bindings.getFirst();
        assertThat(binding.name()).isEqualTo("archive");
        assertThat(binding.corpusRef().tenantId()).isEqualTo("tenant-a");
        assertThat(binding.corpusRef().corpusName()).isEqualTo("archive");
    }

    // --- composite mode ---

    @Test
    void producesBindingForCompositeCorpus() throws IOException {
        Path corpusDir = tempDir.resolve("composite");
        Files.createDirectories(corpusDir);

        var ingestionConfig = stubIngestionConfig(Map.of(
                "hybrid", stubCorpusIngestionConfig("default", "hybrid", IngestionMode.AUTO)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "hybrid", stubInstanceConfig(corpusDir.toString(), "COMPOSITE", 100 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        assertThat(bindings).hasSize(1);
        CorpusIngestionBinding binding = bindings.getFirst();
        assertThat(binding.name()).isEqualTo("hybrid");
    }

    // --- NONE mode is skipped ---

    @Test
    void skipsCorpusWithNoneMode() {
        var ingestionConfig = stubIngestionConfig(Map.of(
                "disabled", stubCorpusIngestionConfig("default", "disabled", IngestionMode.NONE)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "disabled", stubInstanceConfig("/tmp/whatever", "FLAT", 100 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        assertThat(bindings).isEmpty();
    }

    // --- missing storage config logs warning, skips corpus ---

    @Test
    void skipsCorpusWithNoMatchingStorageConfig() {
        var ingestionConfig = stubIngestionConfig(Map.of(
                "orphan", stubCorpusIngestionConfig("default", "orphan", IngestionMode.AUTO)
        ));
        var storageConfig = stubStorageConfig(Map.of()); // no storage entry

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        assertThat(bindings).isEmpty();
    }

    // --- multiple corpora ---

    @Test
    void producesMultipleBindings() throws IOException {
        Path gardenDir = tempDir.resolve("garden");
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(gardenDir);
        Files.createDirectories(docsDir);

        var ingestionConfig = stubIngestionConfig(Map.of(
                "garden", stubCorpusIngestionConfig("t1", "garden", IngestionMode.AUTO),
                "docs", stubCorpusIngestionConfig("t2", "docs", IngestionMode.MANUAL),
                "disabled", stubCorpusIngestionConfig("t3", "disabled", IngestionMode.NONE)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "garden", stubInstanceConfig(gardenDir.toString(), "FLAT", 100 * 1024 * 1024L),
                "docs", stubInstanceConfig(docsDir.toString(), "FLAT", 100 * 1024 * 1024L),
                "disabled", stubInstanceConfig("/tmp/nope", "FLAT", 100 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> bindings = producer.bindings();

        // 2 active, 1 NONE skipped
        assertThat(bindings).hasSize(2);
        assertThat(bindings.stream().map(CorpusIngestionBinding::name).toList())
                .containsExactlyInAnyOrder("garden", "docs");
    }

    // --- bindings are cached (same list on second call) ---

    @Test
    void bindingsAreCached() throws IOException {
        Path corpusDir = tempDir.resolve("cached");
        Files.createDirectories(corpusDir);

        var ingestionConfig = stubIngestionConfig(Map.of(
                "cached", stubCorpusIngestionConfig("default", "cached", IngestionMode.AUTO)
        ));
        var storageConfig = stubStorageConfig(Map.of(
                "cached", stubInstanceConfig(corpusDir.toString(), "FLAT", 100 * 1024 * 1024L)
        ));

        var producer = new CorpusBindingProducer(ingestionConfig, storageConfig, extractor);
        List<CorpusIngestionBinding> first = producer.bindings();
        List<CorpusIngestionBinding> second = producer.bindings();

        assertThat(first).isSameAs(second);
    }

    // --- stub helpers ---

    private IngestionConfig stubIngestionConfig(Map<String, IngestionConfig.CorpusIngestionConfig> corpora) {
        return new IngestionConfig() {
            @Override public java.time.Duration interval() { return java.time.Duration.ofSeconds(30); }
            @Override public String cursorDir() { return "/tmp/cursors"; }
            @Override public Map<String, CorpusIngestionConfig> corpora() { return corpora; }
        };
    }

    private IngestionConfig.CorpusIngestionConfig stubCorpusIngestionConfig(
            String tenantId, String corpusName, IngestionMode mode) {
        return new IngestionConfig.CorpusIngestionConfig() {
            @Override public IngestionMode mode() { return mode; }
            @Override public String tenantId() { return tenantId; }
            @Override public String corpusName() { return corpusName; }
            @Override public String chunking() { return "none"; }
            @Override public Optional<Integer> chunkingMaxSize() { return Optional.empty(); }
            @Override public Optional<Integer> chunkingOverlapSize() { return Optional.empty(); }
        };
    }

    private CorpusStorageConfig stubStorageConfig(Map<String, CorpusStorageConfig.CorpusInstanceConfig> corpora) {
        return () -> corpora;
    }

    private CorpusStorageConfig.CorpusInstanceConfig stubInstanceConfig(String source, String mode, long maxZipSize) {
        return new CorpusStorageConfig.CorpusInstanceConfig() {
            @Override public String source() { return source; }
            @Override public String mode() { return mode; }
            @Override public long maxZipSize() { return maxZipSize; }
        };
    }
}
