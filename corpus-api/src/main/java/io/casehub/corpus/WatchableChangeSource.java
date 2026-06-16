package io.casehub.corpus;

public interface WatchableChangeSource extends ChangeSource, AutoCloseable {
    void watch(ChangeListener listener);
    String currentCursor();
    @Override void close();
}
