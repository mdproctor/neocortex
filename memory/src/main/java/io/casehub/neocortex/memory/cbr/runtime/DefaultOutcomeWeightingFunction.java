package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.OutcomeWeightingFunction;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@DefaultBean
@ApplicationScoped
public class DefaultOutcomeWeightingFunction implements OutcomeWeightingFunction {

    private final double alpha;

    @Inject
    DefaultOutcomeWeightingFunction(OutcomeWeightingConfig config) {
        this.alpha = config.influence();
    }

    DefaultOutcomeWeightingFunction(double alpha) {
        this.alpha = alpha;
    }

    @Override
    public double apply(double similarity, double confidence) {
        return similarity * (1.0 - alpha + alpha * confidence);
    }
}
