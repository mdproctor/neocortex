package io.casehub.corpus;

import java.util.List;

@FunctionalInterface
public interface ChangeListener {
    void onChange(List<ChangedEntry> entries);
}
