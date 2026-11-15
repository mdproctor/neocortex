package io.casehub.neocortex.cognition.coping;

import org.jspecify.annotations.Nullable;

public record CopingPrediction(
    CopingStrategy strategy,
    double confidence,
    @Nullable String familyLabel
) {}
