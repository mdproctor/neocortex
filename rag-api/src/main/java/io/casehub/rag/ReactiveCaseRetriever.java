package io.casehub.rag;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface ReactiveCaseRetriever {
    Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults);
}
