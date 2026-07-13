package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CbrOutcomeConsumerTest {

    @Test
    void onCbrOutcome_delegatesToStore() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded));

        var data = new CbrOutcomeData(
            "tenant-1", "case-42", CbrPath.FAULT,
            Map.of("node-a", "SUCCEEDED", "node-b", "FAILED"),
            1, 1, 2, 0.5,
            Instant.parse("2026-07-13T09:00:00Z"),
            Instant.parse("2026-07-13T10:00:00Z"));

        consumer.onCbrOutcome(data);

        assertThat(recorded).hasSize(1);
        var r = recorded.getFirst();
        assertThat(r.caseId).isEqualTo("case-42");
        assertThat(r.tenantId).isEqualTo("tenant-1");
        assertThat(r.outcome.result()).isEqualTo(CbrOutcome.Outcome.PARTIAL);
        assertThat(r.outcome.successRate()).isEqualTo(0.5);
        assertThat(r.outcome.observedAt()).isEqualTo(Instant.parse("2026-07-13T10:00:00Z"));
        assertThat(r.outcome.detail()).contains("node-a");
    }

    @Test
    void onCbrOutcome_fullSuccess_mapsCorrectly() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded));

        var data = new CbrOutcomeData(
            "t1", "case-99", CbrPath.SITUATION,
            Map.of("n1", "SUCCEEDED"),
            1, 0, 1, 1.0,
            Instant.parse("2026-07-13T09:00:00Z"),
            Instant.parse("2026-07-13T10:00:00Z"));

        consumer.onCbrOutcome(data);

        assertThat(recorded.getFirst().outcome.result()).isEqualTo(CbrOutcome.Outcome.SUCCESS);
    }

    @Test
    void onCbrOutcome_emptyNodeOutcomes_nullDetail() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded));

        var data = new CbrOutcomeData(
            "t1", "case-100", CbrPath.FAULT,
            Map.of(),
            0, 0, 0, 0.0,
            Instant.parse("2026-07-13T09:00:00Z"),
            Instant.parse("2026-07-13T10:00:00Z"));

        consumer.onCbrOutcome(data);

        assertThat(recorded.getFirst().outcome.detail()).isNull();
    }

    record RecordedOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

    static class CapturingStore extends NoOpCbrCaseMemoryStore {
        final List<RecordedOutcome> recorded;

        CapturingStore(List<RecordedOutcome> recorded) { this.recorded = recorded; }

        @Override
        public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
            recorded.add(new RecordedOutcome(caseId, tenantId, outcome));
        }
    }
}
