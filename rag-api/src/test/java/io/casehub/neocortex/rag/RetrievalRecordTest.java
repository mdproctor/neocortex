package io.casehub.neocortex.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalRecordTest {

    @Test
    void rejectsNullRetrievalId() {
        assertThatThrownBy(() -> new RetrievalRecord(
                null, RetrievalQuery.of("q"), new CorpusRef("t", "c"),
                List.of(), 10, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroMaxResults() {
        assertThatThrownBy(() -> new RetrievalRecord(
                "r1", RetrievalQuery.of("q"), new CorpusRef("t", "c"),
                List.of(), 0, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentsListIsImmutable() {
        var docs = new ArrayList<>(List.of(new RetrievedDocumentRef("d1", 0.9)));
        var record = new RetrievalRecord("r1", RetrievalQuery.of("q"),
            new CorpusRef("t", "c"), docs, 10, Instant.now());
        assertThatThrownBy(() -> record.documents().add(new RetrievedDocumentRef("d2", 0.5)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullDocumentsDefaultsToEmptyList() {
        var record = new RetrievalRecord("r1", RetrievalQuery.of("q"),
            new CorpusRef("t", "c"), null, 10, Instant.now());
        assertThat(record.documents()).isEmpty();
    }
}
