package io.casehub.neocortex.corpus.zip;

import io.casehub.neocortex.corpus.VersionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MasterIndexTest {

    private MasterIndex index;

    @BeforeEach
    void setUp() {
        index = new MasterIndex();
    }

    // --- put and get ---

    @Test
    void putAndGet_singleEntry() {
        var loc = new EntryLocation("garden-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc);

        var result = index.get("tools/maven.md");
        assertThat(result).isPresent();
        assertThat(result.get().zipFile()).isEqualTo("garden-001.zip");
        assertThat(result.get().version()).isEqualTo(1);
        assertThat(result.get().timestamp()).isEqualTo(1000L);
    }

    @Test
    void get_absentPath_returnsEmpty() {
        assertThat(index.get("nonexistent")).isEmpty();
    }

    // --- put same path twice: current is latest ---

    @Test
    void putSamePathTwice_currentIsLatest() {
        var v1 = new EntryLocation("garden-001.zip", 1, 1000L);
        var v2 = new EntryLocation("garden-002.zip", 2, 2000L);

        index.put("tools/maven.md", v1);
        index.put("tools/maven.md", v2);

        var current = index.get("tools/maven.md");
        assertThat(current).isPresent();
        assertThat(current.get().version()).isEqualTo(2);
        assertThat(current.get().zipFile()).isEqualTo("garden-002.zip");
    }

    // --- versions ---

    @Test
    void versions_returnsAllVersionsOrdered() {
        var v1 = new EntryLocation("garden-001.zip", 1, 1000L);
        var v2 = new EntryLocation("garden-002.zip", 2, 2000L);
        var v3 = new EntryLocation("garden-003.zip", 3, 3000L);

        index.put("tools/maven.md", v1);
        index.put("tools/maven.md", v2);
        index.put("tools/maven.md", v3);

        List<VersionInfo> versions = index.versions("tools/maven.md");
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
        assertThat(versions.get(2).version()).isEqualTo(3);
    }

    @Test
    void versions_absentPath_returnsEmpty() {
        assertThat(index.versions("nonexistent")).isEmpty();
    }

    // --- tombstone ---

    @Test
    void tombstone_existsReturnsFalse() {
        var loc = new EntryLocation("garden-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc);
        index.tombstone("tools/maven.md");

        assertThat(index.exists("tools/maven.md")).isFalse();
    }

    @Test
    void tombstone_getReturnsEmpty() {
        var loc = new EntryLocation("garden-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc);
        index.tombstone("tools/maven.md");

        assertThat(index.get("tools/maven.md")).isEmpty();
    }

    @Test
    void tombstone_listExcludesPath() {
        var loc1 = new EntryLocation("garden-001.zip", 1, 1000L);
        var loc2 = new EntryLocation("garden-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc1);
        index.put("tools/gradle.md", loc2);
        index.tombstone("tools/maven.md");

        assertThat(index.list()).containsExactly("tools/gradle.md");
    }

    @Test
    void tombstone_versionsStillReturnsHistory() {
        var v1 = new EntryLocation("garden-001.zip", 1, 1000L);
        var v2 = new EntryLocation("garden-002.zip", 2, 2000L);

        index.put("tools/maven.md", v1);
        index.put("tools/maven.md", v2);
        index.tombstone("tools/maven.md");

        List<VersionInfo> versions = index.versions("tools/maven.md");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
    }

    // --- list with prefix ---

    @Test
    void listWithPrefix_filtersCorrectly() {
        index.put("tools/maven.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("tools/gradle.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("jvm/gc.md", new EntryLocation("g-001.zip", 1, 1000L));

        assertThat(index.list("tools/")).containsExactlyInAnyOrder("tools/maven.md", "tools/gradle.md");
        assertThat(index.list("jvm/")).containsExactly("jvm/gc.md");
        assertThat(index.list("nonexistent/")).isEmpty();
    }

    @Test
    void listWithPrefix_excludesTombstoned() {
        index.put("tools/maven.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("tools/gradle.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.tombstone("tools/maven.md");

        assertThat(index.list("tools/")).containsExactly("tools/gradle.md");
    }

    // --- exists ---

    @Test
    void exists_trueForPresentPath() {
        index.put("tools/maven.md", new EntryLocation("g-001.zip", 1, 1000L));
        assertThat(index.exists("tools/maven.md")).isTrue();
    }

    @Test
    void exists_falseForAbsentPath() {
        assertThat(index.exists("nonexistent")).isFalse();
    }

    // --- clear ---

    @Test
    void clear_resetsEverything() {
        index.put("tools/maven.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("jvm/gc.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.tombstone("jvm/gc.md");

        index.clear();

        assertThat(index.list()).isEmpty();
        assertThat(index.exists("tools/maven.md")).isFalse();
        assertThat(index.versions("tools/maven.md")).isEmpty();
        assertThat(index.versions("jvm/gc.md")).isEmpty();
    }

    // --- removeTombstone ---

    @Test
    void removeTombstone_pathVisibleAgain() {
        var loc = new EntryLocation("g-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc);
        index.tombstone("tools/maven.md");

        assertThat(index.exists("tools/maven.md")).isFalse();

        index.removeTombstone("tools/maven.md");

        assertThat(index.exists("tools/maven.md")).isTrue();
        assertThat(index.get("tools/maven.md")).isPresent();
        assertThat(index.list()).contains("tools/maven.md");
    }

    @Test
    void removeTombstone_noOpIfNotTombstoned() {
        var loc = new EntryLocation("g-001.zip", 1, 1000L);
        index.put("tools/maven.md", loc);

        // Should not throw
        index.removeTombstone("tools/maven.md");
        assertThat(index.exists("tools/maven.md")).isTrue();
    }

    // --- list returns sorted paths ---

    @Test
    void list_returnsSortedPaths() {
        index.put("c/file.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("a/file.md", new EntryLocation("g-001.zip", 1, 1000L));
        index.put("b/file.md", new EntryLocation("g-001.zip", 1, 1000L));

        assertThat(index.list()).containsExactly("a/file.md", "b/file.md", "c/file.md");
    }
}
