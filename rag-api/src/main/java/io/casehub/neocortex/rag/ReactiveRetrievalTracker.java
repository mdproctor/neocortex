package io.casehub.neocortex.rag;

import io.smallrye.mutiny.Uni;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface ReactiveRetrievalTracker {

    Uni<String> record(RetrievalQuery query, CorpusRef corpus,
                       List<RetrievedChunk> results, int maxResults);

    Uni<Void> feedback(String retrievalId, String sourceDocumentId,
                       RetrievalOutcome outcome);

    Uni<List<RetrievalRecord>> findRecords(CorpusRef corpus, Instant since, Instant until);

    Uni<List<RetrievalFeedback>> findFeedback(CorpusRef corpus, Instant since, Instant until);

    Uni<Set<String>> findRetrievedDocumentIds(CorpusRef corpus, Instant since, Instant until);
}
