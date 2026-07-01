package io.casehub.neocortex.rag;

import java.util.List;

public interface CaseRetriever {
    List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults, PayloadFilter filter);

    default List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus, int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
