package io.casehub.neocortex.cognition.coping;

public record CopingStrategyAttempt(
    CopingStrategy strategy,
    int iteration,
    String reinterpretedNarrative
) {}
