package io.casehub.neocortex.examples.rag;

import io.casehub.neocortex.corpus.zip.ChainManifest;
import io.casehub.neocortex.corpus.zip.CompactionMode;
import io.casehub.neocortex.corpus.zip.Compactor;
import io.casehub.neocortex.corpus.zip.CorpusConfig;
import io.casehub.neocortex.corpus.zip.ZipCorpusStore;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class ZipCorpusIngestSmokeTest {

    @Test
    void zipIngestAndCompaction(@TempDir Path tempDir) throws IOException {
        var config = new CorpusConfig("zip-examples", tempDir, 1024);
        var store = new ZipCorpusStore(config);

        store.append("docs/readme.md", "---\ntitle: Test\ndomain: tech\n---\nHello world.".getBytes());
        store.append("docs/guide.md", "---\ntitle: Guide\ndomain: tech\n---\nA guide.".getBytes());
        store.delete("docs/guide.md");
        store.append("docs/api.md", "---\ntitle: API\ndomain: tech\n---\nAPI reference.".getBytes());

        // Force rollover by exceeding maxZipSize (1KB)
        byte[] payload = new byte[400];
        new java.util.Random(42).nextBytes(payload);
        store.append("rollover1.bin", payload);
        store.append("rollover2.bin", payload);
        store.append("rollover3.bin", payload);

        var manifest = ChainManifest.load(tempDir.resolve("chain.json"));
        var closedEntry = manifest.entries().stream()
            .filter(e -> "closed".equals(e.status()))
            .filter(e -> e.replacedBy() == null)
            .findFirst()
            .orElseThrow();

        Compactor.compact(tempDir.resolve(closedEntry.file()),
            CompactionMode.TOMBSTONES_ONLY, manifest, tempDir);

        var reloadedStore = new ZipCorpusStore(config);
        assertThat(reloadedStore.exists("docs/readme.md")).isTrue();
        assertThat(reloadedStore.exists("docs/api.md")).isTrue();
        assertThat(reloadedStore.exists("docs/guide.md")).isFalse();
    }
}
