package io.casehub.neocortex.cognitive.observability;

public interface DerivationObserver {

    DerivationObserver NOOP = new DerivationObserver() {};

    record PipelineInput(String axes, java.util.List<String> profileTerms,
                         java.util.List<String> facetNames, java.util.List<String> goals) {}

    record StageComputation(String method,
                            java.util.Map<String, Double> baseValues,
                            java.util.List<FacetEffect> facetEffects,
                            java.util.Map<String, Double> finalValues) {}

    record FacetEffect(String facet, double facetScore, String field, double before, double after) {
        public double delta() {return after - before;}
    }

    record DataFlow(String sourceMethod, String sourceField,
                    String targetMethod, String targetField, double value) {}

    default void onPipelineStart(String agentId, String eventRef, PipelineInput input)       {}

    default void onStageComputation(String agentId, String eventRef, StageComputation stage) {}

    default void onDataFlow(String agentId, String eventRef, DataFlow flow)                  {}

    default void onPipelineComplete(String agentId, String eventRef)                         {}
}
