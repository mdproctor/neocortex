package io.casehub.rag;

import java.util.List;

public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults);
}
