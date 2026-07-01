package io.casehub.neocortex.rag;

import java.util.Optional;

public interface CursorStore {
    Optional<String> load(String corpusName);
    void save(String corpusName, String cursor);
    void delete(String corpusName);
}
