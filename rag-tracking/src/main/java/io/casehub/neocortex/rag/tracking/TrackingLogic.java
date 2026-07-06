package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

final class TrackingLogic {

    static final String TRACKING_ID_KEY = "_trackingId";

    private TrackingLogic() {}

    static boolean isAlreadyTracked(List<RetrievedChunk> chunks) {
        return !chunks.isEmpty()
            && chunks.stream().anyMatch(c -> c.metadata().containsKey(TRACKING_ID_KEY));
    }

    static List<RetrievedDocumentRef> toDocumentRefs(List<RetrievedChunk> chunks) {
        return chunks.stream()
            .collect(Collectors.toMap(
                RetrievedChunk::sourceDocumentId,
                RetrievedChunk::relevanceScore, Math::max))
            .entrySet().stream()
            .map(e -> new RetrievedDocumentRef(e.getKey(), e.getValue()))
            .toList();
    }

    static List<RetrievedChunk> stamp(List<RetrievedChunk> chunks, String trackingId) {
        return chunks.stream()
            .map(c -> {
                var augmented = new HashMap<>(c.metadata());
                augmented.put(TRACKING_ID_KEY, trackingId);
                return c.withMetadata(augmented);
            })
            .toList();
    }
}
