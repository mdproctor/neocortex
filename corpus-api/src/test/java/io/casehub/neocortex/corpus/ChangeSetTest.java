package io.casehub.neocortex.corpus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetTest {

    @Test
    void validConstruction() {
        var entries = List.of(
            new ChangedEntry("a.txt", ChangeType.ADDED),
            new ChangedEntry("b.txt", ChangeType.MODIFIED)
        );
        var changeSet = new ChangeSet(entries, "cursor123");
        assertThat(changeSet.entries()).hasSize(2);
        assertThat(changeSet.newCursor()).isEqualTo("cursor123");
    }

    @Test
    void nullEntriesBecomesEmptyList() {
        var changeSet = new ChangeSet(null, "cursor");
        assertThat(changeSet.entries()).isEmpty();
    }

    @Test
    void emptyEntriesAllowed() {
        var changeSet = new ChangeSet(List.of(), null);
        assertThat(changeSet.entries()).isEmpty();
        assertThat(changeSet.newCursor()).isNull();
    }

    @Test
    void entriesListIsDefensivelyCopied() {
        var mutable = new ArrayList<ChangedEntry>();
        mutable.add(new ChangedEntry("a.txt", ChangeType.ADDED));
        var changeSet = new ChangeSet(mutable, "cursor");

        mutable.add(new ChangedEntry("b.txt", ChangeType.DELETED));

        assertThat(changeSet.entries()).hasSize(1);
        assertThatThrownBy(() -> changeSet.entries().add(new ChangedEntry("c.txt", ChangeType.MODIFIED)))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
