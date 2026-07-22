package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StepConsensus(
        String bindingName,
        String capabilityName,
        int occurrenceCount,
        int totalPlans,
        Map<String, Integer> workerDistribution,
        Map<String, Integer> outcomeDistribution,
        Map<Integer, Integer> priorityDistribution,
        List<String> contributingCaseIds,
        StepAgreement agreement
) {
    public StepConsensus {
        Objects.requireNonNull(bindingName, "bindingName");
        if (occurrenceCount < 1)
            throw new IllegalArgumentException("occurrenceCount must be >= 1");
        if (totalPlans < 1)
            throw new IllegalArgumentException("totalPlans must be >= 1");
        workerDistribution = workerDistribution != null ? Map.copyOf(workerDistribution) : Map.of();
        outcomeDistribution = outcomeDistribution != null ? Map.copyOf(outcomeDistribution) : Map.of();
        priorityDistribution = priorityDistribution != null ? Map.copyOf(priorityDistribution) : Map.of();
        contributingCaseIds = contributingCaseIds != null ? List.copyOf(contributingCaseIds) : List.of();
        Objects.requireNonNull(agreement, "agreement");
    }
}
