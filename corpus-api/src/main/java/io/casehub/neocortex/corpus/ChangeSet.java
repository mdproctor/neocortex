package io.casehub.neocortex.corpus;

import java.util.List;

public record ChangeSet(List<ChangedEntry> entries, String newCursor) {
    public ChangeSet {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
