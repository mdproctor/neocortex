package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

public abstract class CbrRetrievalTrackerContractTest {

    protected static final MemoryDomain CBR = new MemoryDomain("cbr");
    protected static final MemoryDomain CLINICAL = new MemoryDomain("clinical");

    protected abstract CbrRetrievalTracker tracker();

    private CbrQuery query(String tenantId) {
        return CbrQuery.of(tenantId, CBR, "default", Map.of(), 5);
    }

    private CbrQuery query(String tenantId, MemoryDomain domain) {
        return CbrQuery.of(tenantId, domain, "default", Map.of(), 5);
    }

    private List<ScoredCbrCase<?>> results() {
        var c = new FeatureVectorCbrCase("problem", "solution", null, 0.9, Map.of());
        return List.of(new ScoredCbrCase<>(c, "case-1", 0.85));
    }

    @Test void record_returnsNonBlankTraceId() {
        String id = tracker().record(query("t1"), results());
        assertThat(id).isNotBlank();
    }

    @Test void record_persistsQueryAndResults() {
        tracker().record(query("t1"), results());
        var traces = tracker().findTraces("default", "t1", CBR,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst().results()).hasSize(1);
        assertThat(traces.getFirst().results().getFirst().caseId()).isEqualTo("case-1");
    }

    @Test void findTraces_byCaseTypeAndTenant() {
        tracker().record(query("t1"), results());
        tracker().record(CbrQuery.of("t2", CBR, "default", Map.of(), 5), results());
        var traces = tracker().findTraces("default", "t1", CBR,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(traces).hasSize(1);
    }

    @Test void findTraces_domainIsolation() {
        tracker().record(query("t1", CBR), results());
        tracker().record(query("t1", CLINICAL), results());
        var cbrTraces = tracker().findTraces("default", "t1", CBR,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS));
        var clinicalTraces = tracker().findTraces("default", "t1", CLINICAL,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(cbrTraces).hasSize(1);
        assertThat(clinicalTraces).hasSize(1);
    }

    @Test void findTraces_timeRangeFiltering() {
        tracker().record(query("t1"), results());
        var traces = tracker().findTraces("default", "t1", CBR,
                Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS));
        assertThat(traces).isEmpty();
    }

    @Test void findTraces_emptyWhenNoMatches() {
        var traces = tracker().findTraces("nonexistent", "t1", CBR,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(traces).isEmpty();
    }

    @Test void findTraces_multipleTraces_orderedByTimestamp() {
        tracker().record(query("t1"), results());
        tracker().record(query("t1"), results());
        var traces = tracker().findTraces("default", "t1", CBR,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).timestamp()).isBeforeOrEqualTo(traces.get(1).timestamp());
    }

    @Test void purgeOlderThan_removesOldTraces() {
        tracker().record(query("t1"), results());
        int purged = tracker().purgeOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(purged).isEqualTo(1);
        assertThat(tracker().findTraces("default", "t1", CBR,
                Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS))).isEmpty();
    }

    @Test void purgeOlderThan_preservesRecentTraces() {
        tracker().record(query("t1"), results());
        int purged = tracker().purgeOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(purged).isEqualTo(0);
    }

    @Test void purgeOlderThan_returnsDeletedCount() {
        tracker().record(query("t1"), results());
        tracker().record(query("t1"), results());
        int purged = tracker().purgeOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(purged).isEqualTo(2);
    }
}
