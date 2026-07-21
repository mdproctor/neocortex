package io.casehub.neocortex.memory.cbr.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrOutcomeConsumerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
                                                       .registerModule(new JavaTimeModule());

    private static CloudEvent outcomeEvent(CbrOutcomeData data) throws Exception {
        return CloudEventBuilder.v1()
                                .withId("test-1")
                                .withSource(URI.create("/casehub-desiredstate"))
                                .withType(CbrEventTypes.CBR_OUTCOME)
                                .withDataContentType("application/json")
                                .withData(MAPPER.writeValueAsBytes(data))
                                .build();
    }


    @Test
    void onCbrOutcome_delegatesToStore() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

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
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

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
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

        var data = new CbrOutcomeData(
            "t1", "case-100", CbrPath.FAULT,
            Map.of(),
            0, 0, 0, 0.0,
            Instant.parse("2026-07-13T09:00:00Z"),
            Instant.parse("2026-07-13T10:00:00Z"));

        consumer.onCbrOutcome(data);

        assertThat(recorded.getFirst().outcome.detail()).isNull();
    }

    @Test
    void onCloudEvent_deserializesAndDelegates() throws Exception {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

        var data = new CbrOutcomeData(
                "tenant-1", "case-42", CbrPath.FAULT,
                Map.of("node-a", "SUCCEEDED", "node-b", "FAILED"),
                1, 1, 2, 0.5,
                Instant.parse("2026-07-13T09:00:00Z"),
                Instant.parse("2026-07-13T10:00:00Z"));

        consumer.onCloudEvent(outcomeEvent(data));

        assertThat(recorded).hasSize(1);
        var r = recorded.getFirst();
        assertThat(r.caseId).isEqualTo("case-42");
        assertThat(r.tenantId).isEqualTo("tenant-1");
        assertThat(r.outcome.successRate()).isEqualTo(0.5);
    }

    @Test
    void onCloudEvent_nullData_skips() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

        var event = CloudEventBuilder.v1()
                                     .withId("test-null")
                                     .withSource(URI.create("/test"))
                                     .withType(CbrEventTypes.CBR_OUTCOME)
                                     .build();

        consumer.onCloudEvent(event);

        assertThat(recorded).isEmpty();
    }

    @Test
    void onCloudEvent_invalidJson_skips() {
        var recorded = new ArrayList<RecordedOutcome>();
        var consumer = new CbrOutcomeConsumer(new CapturingStore(recorded), MAPPER);

        var event = CloudEventBuilder.v1()
                                     .withId("test-bad")
                                     .withSource(URI.create("/test"))
                                     .withType(CbrEventTypes.CBR_OUTCOME)
                                     .withDataContentType("application/json")
                                     .withData("not valid json".getBytes())
                                     .build();

        consumer.onCloudEvent(event);

        assertThat(recorded).isEmpty();
    }

    @Test
    void onCloudEvent_storeThrows_propagates() throws Exception {
        var consumer = new CbrOutcomeConsumer(new ThrowingStore(), MAPPER);

        var data = new CbrOutcomeData(
                "t1", "case-99", CbrPath.SITUATION,
                Map.of(), 1, 0, 1, 1.0,
                Instant.parse("2026-07-13T09:00:00Z"),
                Instant.parse("2026-07-13T10:00:00Z"));

        var event = outcomeEvent(data);
        assertThatThrownBy(() -> consumer.onCloudEvent(event))
                .isInstanceOf(RuntimeException.class);
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

    static class ThrowingStore extends NoOpCbrCaseMemoryStore {
        @Override
        public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
            throw new RuntimeException("store failure");
        }
    }

}
