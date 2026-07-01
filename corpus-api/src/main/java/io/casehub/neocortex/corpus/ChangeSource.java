package io.casehub.neocortex.corpus;

public interface ChangeSource {
    ChangeSet changesSince(String cursor);
    ChangeSet fullScan();
}
