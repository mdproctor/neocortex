package io.casehub.neocortex.corpus;

import java.util.List;

@FunctionalInterface
public interface ChangeListener {
    void onChange(List<ChangedEntry> entries);
}
