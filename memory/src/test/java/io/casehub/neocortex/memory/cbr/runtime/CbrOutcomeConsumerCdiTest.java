package io.casehub.neocortex.memory.cbr.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.api.event.CloudEventType;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;


@QuarkusTest
class CbrOutcomeConsumerCdiTest {

    @Inject
    Event<CloudEvent> cloudEventBus;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CapturingCbrStore capturingStore;

    @Test
    void observer_receives_typed_event() throws Exception {
        var data = new CbrOutcomeData(
                "tenant-cdi", "case-cdi-1", CbrPath.FAULT,
                Map.of("n1", "SUCCEEDED"),
                1, 0, 1, 1.0,
                Instant.parse("2026-07-21T09:00:00Z"),
                Instant.parse("2026-07-21T10:00:00Z"));

        CloudEvent event = CloudEventBuilder.v1()
                .withId("cdi-test-1")
                .withSource(URI.create("/test"))
                .withType(CbrEventTypes.CBR_OUTCOME)
                .withDataContentType("application/json")
                .withData(objectMapper.writeValueAsBytes(data))
                .build();

        cloudEventBus.select(new TestCloudEventTypeLiteral(CbrEventTypes.CBR_OUTCOME))
                .fireAsync(event)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertThat(capturingStore.recorded).hasSize(1);
        var r = capturingStore.recorded.getFirst();
        assertThat(r.caseId()).isEqualTo("case-cdi-1");
        assertThat(r.tenantId()).isEqualTo("tenant-cdi");
        assertThat(r.outcome().result()).isEqualTo(CbrOutcome.Outcome.SUCCESS);
    }

    public record RecordedOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

    @Alternative
    @Singleton
    @jakarta.annotation.Priority(100)
    public static class CapturingCbrStore extends NoOpCbrCaseMemoryStore {
        final List<RecordedOutcome> recorded = new ArrayList<>();

        @Override
        public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
            recorded.add(new RecordedOutcome(caseId, tenantId, outcome));
        }
    }

    static final class TestCloudEventTypeLiteral
            extends AnnotationLiteral<CloudEventType> implements CloudEventType {
        private final String value;

        TestCloudEventTypeLiteral(String value) {this.value = value;}

        @Override
        public String value()                   {return value;}
    }

}
