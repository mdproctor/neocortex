package io.casehub.rag;

import java.util.List;

public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter);

    default List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
