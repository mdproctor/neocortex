package io.casehub.neocortex.memory.cbr;

import java.util.Map;
import java.util.Objects;

public record AdaptedStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters,
    AdaptationAction action,
    String reason
) {
    public AdaptedStep {
        Objects.requireNonNull(bindingName, "bindingName");
                if (priority < 0) throw new IllegalArgumentException("priority must be >= 0");
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        Objects.requireNonNull(action, "action");
    }
}
