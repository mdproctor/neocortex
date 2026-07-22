package io.casehub.neocortex.memory.cbr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EnsembleTrace(
        String traceId,
        String retrievalTraceId,
        String caseType,
        List<String> sourceCaseIds,
        List<StepConsensus> stepAnalysis,
        List<AdaptedStep> synthesizedSteps,
        int inputPlanCount,
        double ensembleConfidence,
        Map<String, FeatureValue> currentFeatures,
        Instant timestamp
) {
    public EnsembleTrace {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(caseType, "caseType");
        Objects.requireNonNull(sourceCaseIds, "sourceCaseIds");
        sourceCaseIds = List.copyOf(sourceCaseIds);
        Objects.requireNonNull(stepAnalysis, "stepAnalysis");
        stepAnalysis = List.copyOf(stepAnalysis);
        Objects.requireNonNull(synthesizedSteps, "synthesizedSteps");
        synthesizedSteps = List.copyOf(synthesizedSteps);
        Objects.requireNonNull(currentFeatures, "currentFeatures");
        currentFeatures = Map.copyOf(currentFeatures);
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
