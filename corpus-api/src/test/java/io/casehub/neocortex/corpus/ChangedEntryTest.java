package io.casehub.neocortex.corpus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangedEntryTest {

    @Test
    void validConstruction() {
        var entry = new ChangedEntry("foo/bar.txt", ChangeType.ADDED);
        assertThat(entry.path()).isEqualTo("foo/bar.txt");
        assertThat(entry.type()).isEqualTo(ChangeType.ADDED);
    }

    @Test
    void nullPathThrows() {
        assertThatThrownBy(() -> new ChangedEntry(null, ChangeType.ADDED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path must not be null or blank");
    }

    @Test
    void blankPathThrows() {
        assertThatThrownBy(() -> new ChangedEntry("  ", ChangeType.MODIFIED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path must not be null or blank");
    }

    @Test
    void nullTypeThrows() {
        assertThatThrownBy(() -> new ChangedEntry("foo.txt", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type must not be null");
    }
}
