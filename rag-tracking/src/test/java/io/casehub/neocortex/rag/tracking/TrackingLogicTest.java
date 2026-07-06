package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.RetrievedDocumentRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingLogicTest {

    @Test
    void isAlreadyTracked_falseWhenNoMetadataKey() {
        var chunks = List.of(
            chunk("a", "doc1", 0.9, Map.of()),
            chunk("b", "doc2", 0.8, Map.of("other", "value")));
        assertThat(TrackingLogic.isAlreadyTracked(chunks)).isFalse();
    }

    @Test
    void isAlreadyTracked_trueWhenKeyPresent() {
        var chunks = List.of(
            chunk("a", "doc1", 0.9, Map.of(TrackingLogic.TRACKING_ID_KEY, "abc-123")),
            chunk("b", "doc2", 0.8, Map.of()));
        assertThat(TrackingLogic.isAlreadyTracked(chunks)).isTrue();
    }

    @Test
    void isAlreadyTracked_falseWhenEmpty() {
        assertThat(TrackingLogic.isAlreadyTracked(List.of())).isFalse();
    }

    @Test
    void toDocumentRefs_deduplicatesWithMaxScore() {
        var chunks = List.of(
            chunk("a", "doc1", 0.7, Map.of()),
            chunk("b", "doc1", 0.9, Map.of()),
            chunk("c", "doc2", 0.8, Map.of()));

        List<RetrievedDocumentRef> refs = TrackingLogic.toDocumentRefs(chunks);

        assertThat(refs).hasSize(2);
        var doc1 = refs.stream()
            .filter(r -> r.sourceDocumentId().equals("doc1")).findFirst().orElseThrow();
        assertThat(doc1.relevanceScore()).isEqualTo(0.9);
        var doc2 = refs.stream()
            .filter(r -> r.sourceDocumentId().equals("doc2")).findFirst().orElseThrow();
        assertThat(doc2.relevanceScore()).isEqualTo(0.8);
    }

    @Test
    void stamp_addsTrackingIdToMetadata() {
        var original = List.of(
            chunk("a", "doc1", 0.9, Map.of("existing", "value")));

        List<RetrievedChunk> stamped = TrackingLogic.stamp(original, "track-42");

        assertThat(stamped).hasSize(1);
        assertThat(stamped.get(0).metadata()).containsEntry(TrackingLogic.TRACKING_ID_KEY, "track-42");
        assertThat(stamped.get(0).metadata()).containsEntry("existing", "value");

        // Original unchanged — RetrievedChunk uses Map.copyOf in constructor
        assertThat(original.get(0).metadata()).doesNotContainKey(TrackingLogic.TRACKING_ID_KEY);
    }

    // -- helpers --

    private static RetrievedChunk chunk(String content, String docId,
                                         double score, Map<String, String> metadata) {
        return new RetrievedChunk(content, docId, score, metadata);
    }
}
