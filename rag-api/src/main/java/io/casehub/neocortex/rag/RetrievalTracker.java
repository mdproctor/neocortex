package io.casehub.neocortex.rag;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface RetrievalTracker {

    String record(RetrievalQuery query, CorpusRef corpus,
                  List<RetrievedChunk> results, int maxResults);

    void feedback(String retrievalId, String sourceDocumentId,
                  RetrievalOutcome outcome);

    List<RetrievalRecord> findRecords(CorpusRef corpus, Instant since, Instant until);

    List<RetrievalFeedback> findFeedback(CorpusRef corpus, Instant since, Instant until);

    Set<String> findRetrievedDocumentIds(CorpusRef corpus, Instant since, Instant until);

    int purgeOlderThan(Instant cutoff);
}
