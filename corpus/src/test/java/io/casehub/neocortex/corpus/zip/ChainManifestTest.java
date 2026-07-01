package io.casehub.neocortex.corpus.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainManifestTest {

    @TempDir
    Path tempDir;

    // --- round-trip: create, add entries, save, load, verify ---

    @Test
    void roundTrip_preservesAllFields() throws IOException {
        var manifest = ChainManifest.create("garden");

        var entry = new ChainEntry(
                "550e8400-e29b-41d4-a716-446655440000",
                "garden-001.zip",
                0,
                "closed",
                null,
                5200,
                5200,
                "sha256:abc123",
                Map.of("tools", 2100, "jvm", 1800, "quarkus", 1300),
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 3, 22),
                null
        );
        manifest.addEntry(entry);

        var active = new ChainEntry(
                "660e8400-e29b-41d4-a716-446655440001",
                "garden-002.zip",
                1,
                "active",
                "550e8400-e29b-41d4-a716-446655440000",
                0,
                5200,
                null,
                null,
                null,
                null,
                null
        );
        manifest.addEntry(active);

        Path chainJson = tempDir.resolve("chain.json");
        manifest.save(chainJson);

        assertThat(chainJson).exists();

        var loaded = ChainManifest.load(chainJson);

        assertThat(loaded.corpusName()).isEqualTo("garden");
        assertThat(loaded.schemaVersion()).isEqualTo(1);
        assertThat(loaded.entries()).hasSize(2);

        var first = loaded.entries().get(0);
        assertThat(first.uuid()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(first.file()).isEqualTo("garden-001.zip");
        assertThat(first.sequence()).isEqualTo(0);
        assertThat(first.status()).isEqualTo("closed");
        assertThat(first.predecessor()).isNull();
        assertThat(first.entryCount()).isEqualTo(5200);
        assertThat(first.cumulativeEntryCount()).isEqualTo(5200);
        assertThat(first.contentHash()).isEqualTo("sha256:abc123");
        assertThat(first.domains()).containsEntry("tools", 2100);
        assertThat(first.domains()).containsEntry("jvm", 1800);
        assertThat(first.domains()).containsEntry("quarkus", 1300);
        assertThat(first.earliest()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(first.latest()).isEqualTo(LocalDate.of(2026, 3, 22));
        assertThat(first.replacedBy()).isNull();

        var second = loaded.entries().get(1);
        assertThat(second.uuid()).isEqualTo("660e8400-e29b-41d4-a716-446655440001");
        assertThat(second.file()).isEqualTo("garden-002.zip");
        assertThat(second.sequence()).isEqualTo(1);
        assertThat(second.status()).isEqualTo("active");
        assertThat(second.predecessor()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(second.entryCount()).isEqualTo(0);
        assertThat(second.cumulativeEntryCount()).isEqualTo(5200);
        assertThat(second.contentHash()).isNull();
        assertThat(second.domains()).isEmpty();
        assertThat(second.earliest()).isNull();
        assertThat(second.latest()).isNull();
        assertThat(second.replacedBy()).isNull();
    }

    // --- atomic write: verify file exists after save ---

    @Test
    void save_createsFileAtomically() throws IOException {
        var manifest = ChainManifest.create("test-corpus");
        Path chainJson = tempDir.resolve("chain.json");

        manifest.save(chainJson);

        assertThat(chainJson).exists();
        String content = Files.readString(chainJson);
        assertThat(content).contains("\"corpus\": \"test-corpus\"");
        assertThat(content).contains("\"schemaVersion\": 1");
    }

    // --- close entry ---

    @Test
    void closeEntry_setsStatusAndHash() throws IOException {
        var manifest = ChainManifest.create("garden");

        var entry = new ChainEntry(
                "uuid-1", "garden-001.zip", 0, "active", null,
                0, 0, null, null, null, null, null
        );
        manifest.addEntry(entry);

        manifest.closeEntry("uuid-1", "sha256:deadbeef", 42);

        var closed = manifest.entries().get(0);
        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.contentHash()).isEqualTo("sha256:deadbeef");
        assertThat(closed.entryCount()).isEqualTo(42);
    }

    // --- retire entry ---

    @Test
    void retireEntry_setsCompactedAndReplacedBy() {
        var manifest = ChainManifest.create("garden");

        var entry = new ChainEntry(
                "uuid-old", "garden-001.zip", 0, "closed", null,
                100, 100, "sha256:abc", null, null, null, null
        );
        manifest.addEntry(entry);

        manifest.retireEntry("uuid-old", "uuid-new");

        var retired = manifest.entries().get(0);
        assertThat(retired.status()).isEqualTo("compacted");
        assertThat(retired.replacedBy()).isEqualTo("uuid-new");
    }

    // --- active entry ---

    @Test
    void activeEntry_returnsCurrentActive() {
        var manifest = ChainManifest.create("garden");

        assertThat(manifest.activeEntry()).isEmpty();

        var entry = new ChainEntry(
                "uuid-1", "garden-001.zip", 0, "active", null,
                0, 0, null, null, null, null, null
        );
        manifest.addEntry(entry);

        assertThat(manifest.activeEntry()).isPresent();
        assertThat(manifest.activeEntry().get().uuid()).isEqualTo("uuid-1");
    }

    @Test
    void activeEntry_emptyAfterClose() {
        var manifest = ChainManifest.create("garden");

        var entry = new ChainEntry(
                "uuid-1", "garden-001.zip", 0, "active", null,
                0, 0, null, null, null, null, null
        );
        manifest.addEntry(entry);
        manifest.closeEntry("uuid-1", "sha256:abc", 10);

        assertThat(manifest.activeEntry()).isEmpty();
    }

    // --- empty chain ---

    @Test
    void loadEmptyChain() throws IOException {
        var manifest = ChainManifest.create("empty");
        Path chainJson = tempDir.resolve("chain.json");
        manifest.save(chainJson);

        var loaded = ChainManifest.load(chainJson);

        assertThat(loaded.corpusName()).isEqualTo("empty");
        assertThat(loaded.schemaVersion()).isEqualTo(1);
        assertThat(loaded.entries()).isEmpty();
    }

    // --- schema version ---

    @Test
    void schemaVersion_isOne() {
        var manifest = ChainManifest.create("test");
        assertThat(manifest.schemaVersion()).isEqualTo(1);
    }

    // --- entries is unmodifiable ---

    @Test
    void entries_isUnmodifiable() {
        var manifest = ChainManifest.create("test");
        var entry = new ChainEntry(
                "uuid-1", "test-001.zip", 0, "active", null,
                0, 0, null, null, null, null, null
        );
        manifest.addEntry(entry);

        assertThatThrownBy(() -> manifest.entries().add(entry))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- JSON content verification ---

    @Test
    void save_writesWellFormedJson() throws IOException {
        var manifest = ChainManifest.create("garden");

        var entry = new ChainEntry(
                "uuid-1", "garden-001.zip", 0, "closed", null,
                100, 100, "sha256:abc",
                Map.of("tools", 50),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 1),
                null
        );
        manifest.addEntry(entry);

        Path chainJson = tempDir.resolve("chain.json");
        manifest.save(chainJson);

        String content = Files.readString(chainJson);
        assertThat(content).contains("\"corpus\": \"garden\"");
        assertThat(content).contains("\"uuid\": \"uuid-1\"");
        assertThat(content).contains("\"file\": \"garden-001.zip\"");
        assertThat(content).contains("\"sequence\": 0");
        assertThat(content).contains("\"status\": \"closed\"");
        assertThat(content).contains("\"predecessor\": null");
        assertThat(content).contains("\"entryCount\": 100");
        assertThat(content).contains("\"contentHash\": \"sha256:abc\"");
        assertThat(content).contains("\"tools\": 50");
        assertThat(content).contains("\"earliest\": \"2026-01-01\"");
        assertThat(content).contains("\"latest\": \"2026-06-01\"");
        assertThat(content).contains("\"replacedBy\": null");
    }

    // --- save overwrites existing file ---

    @Test
    void save_overwritesExistingFile() throws IOException {
        var manifest = ChainManifest.create("v1");
        Path chainJson = tempDir.resolve("chain.json");
        manifest.save(chainJson);

        var manifest2 = ChainManifest.create("v2");
        manifest2.save(chainJson);

        var loaded = ChainManifest.load(chainJson);
        assertThat(loaded.corpusName()).isEqualTo("v2");
    }
}
