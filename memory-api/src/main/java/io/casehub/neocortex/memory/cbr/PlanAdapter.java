package io.casehub.neocortex.memory.cbr;

import java.util.Map;

public interface PlanAdapter {
    AdaptedPlan adapt(String caseType, ScoredCbrCase<PlanCbrCase> retrieved,
                      Map<String, FeatureValue> currentFeatures);
}
