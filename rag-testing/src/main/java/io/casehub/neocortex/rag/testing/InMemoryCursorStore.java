package io.casehub.neocortex.rag.testing;

import io.casehub.neocortex.rag.CursorStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCursorStore implements CursorStore {

    private final ConcurrentHashMap<String, String> cursors = new ConcurrentHashMap<>();

    @Override
    public Optional<String> load(String corpusName) {
        return Optional.ofNullable(cursors.get(corpusName));
    }

    @Override
    public void save(String corpusName, String cursor) {
        cursors.put(corpusName, cursor);
    }

    @Override
    public void delete(String corpusName) {
        cursors.remove(corpusName);
    }

    public void reset() {
        cursors.clear();
    }

    public Map<String, String> getAll() {
        return Map.copyOf(cursors);
    }
}
