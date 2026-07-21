package io.casehub.neocortex.memory.cbr.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.api.event.CloudEventType;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class CbrOutcomeConsumer {

    private static final Logger LOG = Logger.getLogger(CbrOutcomeConsumer.class);

    private final CbrCaseMemoryStore store;
    private final ObjectMapper       objectMapper;

    @Inject
    public CbrOutcomeConsumer(CbrCaseMemoryStore store, ObjectMapper objectMapper) {
        this.store        = store;
        this.objectMapper = objectMapper;
    }

    public void onCloudEvent(
            @ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME) CloudEvent event) {
        if (event.getData() == null) {
            LOG.warnf("CloudEvent %s has no data payload — skipping", event.getId());
            return;
        }
        CbrOutcomeData data;
        try {
            data = objectMapper.readValue(event.getData().toBytes(), CbrOutcomeData.class);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to deserialize CloudEvent %s — skipping", event.getId());
            return;
        }
        onCbrOutcome(data);
    }

    public void onCbrOutcome(CbrOutcomeData data) {
        CbrOutcome outcome = CbrOutcome.of(
                data.successRate(),
                summarize(data.nodeOutcomes()),
                data.observedAt());
        store.recordOutcome(data.sourceId(), data.tenancyId(), outcome);
    }

    private static String summarize(Map<String, String> nodeOutcomes) {
        if (nodeOutcomes == null || nodeOutcomes.isEmpty()) {return null;}
        return nodeOutcomes.entrySet().stream()
                           .map(e -> e.getKey() + "=" + e.getValue())
                           .collect(Collectors.joining(", "));
    }
}
