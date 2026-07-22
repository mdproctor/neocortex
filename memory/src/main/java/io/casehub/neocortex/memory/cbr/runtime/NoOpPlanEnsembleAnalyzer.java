package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.EnsemblePlan;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.StepAgreement;
import io.casehub.neocortex.memory.cbr.StepConsensus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@DefaultBean
@ApplicationScoped
public class NoOpPlanEnsembleAnalyzer implements PlanEnsembleAnalyzer {
    @Override
    public EnsemblePlan analyze(String caseType,
                                List<ScoredCbrCase<PlanCbrCase>> scoredCases,
                                List<AdaptedPlan> adaptedPlans,
                                Map<String, FeatureValue> currentFeatures) {
        Objects.requireNonNull(caseType, "caseType");
        Objects.requireNonNull(scoredCases, "scoredCases");
        Objects.requireNonNull(adaptedPlans, "adaptedPlans");
        Objects.requireNonNull(currentFeatures, "currentFeatures");
        if (scoredCases.size() != adaptedPlans.size())
            throw new IllegalArgumentException(
                    "scoredCases and adaptedPlans must have same size: "
                            + scoredCases.size() + " vs " + adaptedPlans.size());

        if (adaptedPlans.isEmpty()) {
            return new EnsemblePlan(new AdaptedPlan(List.of()), List.of(), List.of(), 0.0, 0);
        }

        int bestIdx = 0;
        for (int i = 1; i < scoredCases.size(); i++) {
            if (scoredCases.get(i).score() > scoredCases.get(bestIdx).score()) {
                bestIdx = i;
            }
        }

        AdaptedPlan best = adaptedPlans.get(bestIdx);
        String caseId = scoredCases.get(bestIdx).caseId();

        List<StepConsensus> analysis = best.steps().stream()
                .map(s -> new StepConsensus(
                        s.bindingName(), s.capabilityName(),
                        1, 1,
                        s.workerName() != null ? Map.of(s.workerName(), 1) : Map.of(),
                        s.stepOutcome() != null ? Map.of(s.stepOutcome(), 1) : Map.of(),
                        Map.of(s.priority(), 1),
                        caseId != null ? List.of(caseId) : List.of(),
                        StepAgreement.UNANIMOUS))
                .toList();

        return new EnsemblePlan(best, analysis,
                caseId != null ? List.of(caseId) : List.of(),
                Math.max(0.0, scoredCases.get(bestIdx).score()),
                1);
    }
}
