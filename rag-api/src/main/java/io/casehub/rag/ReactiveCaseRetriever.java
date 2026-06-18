package io.casehub.rag;

import io.smallrye.mutiny.Uni;
import java.util.List;

/** Non-blocking counterpart of {@link CaseRetriever}. Safe to subscribe to from the Vert.x event loop. */
public interface ReactiveCaseRetriever {
    Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter);

    default Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
