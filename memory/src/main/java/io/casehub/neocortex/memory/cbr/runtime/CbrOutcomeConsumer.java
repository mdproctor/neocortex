package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.desiredstate.api.CbrOutcomeData;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class CbrOutcomeConsumer {

    private final CbrCaseMemoryStore store;

    @Inject
    public CbrOutcomeConsumer(CbrCaseMemoryStore store) {
        this.store = store;
    }

    public void onCbrOutcome(CbrOutcomeData data) {
        CbrOutcome outcome = CbrOutcome.of(
            data.successRate(),
            summarize(data.nodeOutcomes()),
            data.observedAt());
        store.recordOutcome(data.sourceId(), data.tenancyId(), outcome);
    }

    private static String summarize(Map<String, String> nodeOutcomes) {
        if (nodeOutcomes == null || nodeOutcomes.isEmpty()) return null;
        return nodeOutcomes.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));
    }
}
