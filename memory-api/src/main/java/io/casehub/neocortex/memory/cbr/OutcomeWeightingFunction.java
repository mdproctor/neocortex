package io.casehub.neocortex.memory.cbr;

@FunctionalInterface
public interface OutcomeWeightingFunction {
    double apply(double similarity, double confidence);
}
