package io.casehub.neocortex.rag.tracking;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalFeedback;
import io.casehub.neocortex.rag.RetrievalOutcome;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievalRecord;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.casehub.neocortex.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionSchedulerTest {

    @Test
    void purge_callsTrackerWithCorrectCutoff() {
        AtomicReference<Instant> capturedCutoff = new AtomicReference<>();
        AtomicInteger capturedCount = new AtomicInteger();

        RetrievalTracker tracker = new StubTracker() {
            @Override
            public int purgeOlderThan(Instant cutoff) {
                capturedCutoff.set(cutoff);
                capturedCount.incrementAndGet();
                return 5;
            }
        };

        var scheduler = new RetentionScheduler();
        scheduler.tracker = tracker;
        scheduler.retentionDays = 90;

        scheduler.purge();

        assertThat(capturedCount.get()).isEqualTo(1);
        Instant expected = Instant.now().minus(90, ChronoUnit.DAYS);
        assertThat(capturedCutoff.get())
            .isBetween(expected.minusSeconds(2), expected.plusSeconds(2));
    }

    @Test
    void purge_swallowsExceptionWithoutRethrowing() {
        RetrievalTracker tracker = new StubTracker() {
            @Override
            public int purgeOlderThan(Instant cutoff) {
                throw new RuntimeException("simulated failure");
            }
        };

        var scheduler = new RetentionScheduler();
        scheduler.tracker = tracker;
        scheduler.retentionDays = 30;

        // Must not throw — exception is caught and logged
        scheduler.purge();
    }

    @Test
    void start_doesNotCreateExecutorWhenDisabled() {
        var scheduler = new RetentionScheduler();
        scheduler.retentionDays = 0;
        scheduler.start();

        // executor remains null — stop() is a no-op
        scheduler.stop();
    }
}

/**
 * Minimal no-op implementation of RetrievalTracker for testing.
 */
class StubTracker implements RetrievalTracker {

    @Override
    public String record(RetrievalQuery query, CorpusRef corpus,
                         List<RetrievedChunk> results, int maxResults) {
        return "stub-id";
    }

    @Override
    public void feedback(String retrievalId, String sourceDocumentId,
                         RetrievalOutcome outcome) {
        // no-op
    }

    @Override
    public List<RetrievalRecord> findRecords(CorpusRef corpus, Instant since, Instant until) {
        return List.of();
    }

    @Override
    public List<RetrievalFeedback> findFeedback(CorpusRef corpus, Instant since, Instant until) {
        return List.of();
    }

    @Override
    public Set<String> findRetrievedDocumentIds(CorpusRef corpus, Instant since, Instant until) {
        return Set.of();
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        return 0;
    }
}
