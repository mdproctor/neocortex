package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Objects;

public record EnsemblePlan(
        AdaptedPlan synthesizedPlan,
        List<StepConsensus> stepAnalysis,
        List<String> sourceCaseIds,
        double ensembleConfidence,
        int inputPlanCount
) {
    public EnsemblePlan {
        Objects.requireNonNull(synthesizedPlan, "synthesizedPlan");
        Objects.requireNonNull(stepAnalysis, "stepAnalysis");
        stepAnalysis = List.copyOf(stepAnalysis);
        Objects.requireNonNull(sourceCaseIds, "sourceCaseIds");
        sourceCaseIds = List.copyOf(sourceCaseIds);
        if (!(ensembleConfidence >= 0.0 && ensembleConfidence <= 1.0))
            throw new IllegalArgumentException("ensembleConfidence must be in [0,1]");
        if (inputPlanCount < 0)
            throw new IllegalArgumentException("inputPlanCount must be >= 0");
        if (inputPlanCount == 0 && !stepAnalysis.isEmpty())
            throw new IllegalArgumentException("stepAnalysis must be empty when inputPlanCount is 0");
    }
}
