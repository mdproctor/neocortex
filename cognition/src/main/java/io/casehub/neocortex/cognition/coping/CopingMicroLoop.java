package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.EmotionMapper;
import io.casehub.neocortex.cognition.appraisal.PerceivedSituation;
import io.casehub.neocortex.cognition.appraisal.SecCheck;
import io.casehub.neocortex.cognition.appraisal.SecResult;
import io.casehub.neocortex.cognition.regulation.CognitiveLoadLevel;
import io.casehub.neocortex.cognition.regulation.RegulationCascadeConfig;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CopingMicroLoop {

    private static final Logger LOG                       = Logger.getLogger(CopingMicroLoop.class.getName());
    private static final String COPING_ATTRACTOR_CATEGORY = "coping";

    private final           CopingStrategyPredictor                                           predictor;
    private final           CopingGenerationPhase                                             generationPhase;
    private final           List<SecCheck>                                                    keywordChecks;
    private final           CopingConfig                                                      config;
    private final @Nullable CapsEngine                              capsEngine;
    private final @Nullable RegulationCascadeConfig cascadeConfig;

    public CopingMicroLoop(CopingStrategyPredictor predictor,
                           CopingGenerationPhase generationPhase,
                           List<SecCheck> keywordChecks) {
        this(predictor, generationPhase, keywordChecks, CopingConfig.defaults(), null, null);
    }

    public CopingMicroLoop(CopingStrategyPredictor predictor,
                           CopingGenerationPhase generationPhase,
                           List<SecCheck> keywordChecks,
                           CopingConfig config) {
        this(predictor, generationPhase, keywordChecks, config, null, null);
    }

    public CopingMicroLoop(CopingStrategyPredictor predictor,
                           CopingGenerationPhase generationPhase,
                           List<SecCheck> keywordChecks,
                           CopingConfig config,
                           @Nullable CapsEngine capsEngine,
                           @Nullable RegulationCascadeConfig cascadeConfig) {
        this.predictor       = predictor;
        this.generationPhase = generationPhase;
        this.keywordChecks   = List.copyOf(keywordChecks);
        this.config          = config;
        this.capsEngine      = capsEngine;
        this.cascadeConfig   = capsEngine != null && cascadeConfig == null
                               ? RegulationCascadeConfig.defaults() : cascadeConfig;
    }

    public @Nullable CopingState execute(AppraisalContext appraisalContext,
                                         AppraisalResult appraisalResult,
                                         @Nullable CopingDefaults copingDefaults,
                                         @Nullable DispositionAxes axes,
                                         @Nullable CognitiveLoadLevel cognitiveLoad) {
        return execute(appraisalContext, appraisalResult, copingDefaults, axes, cognitiveLoad,
                       null, java.util.Map.of());
    }

    public @Nullable CopingState execute(AppraisalContext appraisalContext,
                                         AppraisalResult appraisalResult,
                                         @Nullable CopingDefaults copingDefaults,
                                         @Nullable DispositionAxes axes,
                                         @Nullable CognitiveLoadLevel cognitiveLoad,
                                         @Nullable String tenantId) {
        return execute(appraisalContext, appraisalResult, copingDefaults, axes, cognitiveLoad,
                       tenantId, java.util.Map.of());
    }

    public @Nullable CopingState execute(AppraisalContext appraisalContext,
                                         AppraisalResult appraisalResult,
                                         @Nullable CopingDefaults copingDefaults,
                                         @Nullable DispositionAxes axes,
                                         @Nullable CognitiveLoadLevel cognitiveLoad,
                                         @Nullable String tenantId,
                                         java.util.Map<String, Double> capsInputActivations) {
        var state = CopingState.initial(
                appraisalContext.situation().narrative(),
                appraisalResult.secDimensions(),
                appraisalResult.emotions());

        // Layer 2: CAPS coping attractor resolution (once, before loop — state is stable across iterations)
        CopingStrategy capsPinnedStrategy = null;
        if (capsEngine != null && cascadeConfig != null && tenantId != null) {
            capsPinnedStrategy = resolveCapsAttractor(tenantId, appraisalContext.agentId(), capsInputActivations);
        }

        while (state.iteration() < config.copingMaxIterations()) {
            if (!hasCopingOpportunity(state.workingEmotions())) {
                break;
            }

            CopingStrategy pinnedStrategy = capsPinnedStrategy;

            // Layer 3: mechanical predictor fallback
            if (pinnedStrategy == null) {
                var prediction = predictor.predict(
                        copingDefaults, state.secDimensions(), state.workingEmotions(),
                        axes, cognitiveLoad);
                pinnedStrategy = prediction.map(CopingPrediction::strategy).orElse(null);
            }

            // Layer 4: LLM generation
            var copingResult = generationPhase.generate(
                    appraisalContext, state, pinnedStrategy, copingDefaults);
            if (copingResult == null) {
                LOG.log(Level.WARNING, "Coping generation returned null at iteration {0}", state.iteration());
                break;
            }

            state = state.withInterpretation(copingResult.narrative())
                         .withAccumulatedEffects(copingResult)
                         .withStrategy(new CopingStrategyAttempt(
                                 copingResult.strategy(), state.iteration(),
                                 copingResult.narrative()));

            if (state.strategiesAttempted().stream()
                     .filter(a -> a.strategy() == copingResult.strategy())
                     .count() > 1) {
                break;
            }

            state = reEvaluate(appraisalContext, state);
            state = state.withIteration(state.iteration() + 1);

            // Clear CAPS pin after first iteration — subsequent iterations should use predictor/LLM
            capsPinnedStrategy = null;
        }

        return state;
    }


    private @Nullable CopingStrategy resolveCapsAttractor(String tenantId, String agentId,
                                                          java.util.Map<String, Double> inputActivations) {
        var capsState      = capsEngine.loadState(tenantId, agentId);
        var settlingResult = capsEngine.settle(capsState, inputActivations);
        var copingAttractors = settlingResult.attractors().stream()
                                             .filter(a -> COPING_ATTRACTOR_CATEGORY.equals(a.category()))
                                             .sorted(java.util.Comparator.comparingDouble(CapsAttractor::strength).reversed())
                                             .toList();
        if (copingAttractors.isEmpty()) {return null;}
        var top = copingAttractors.get(0);
        if (top.strength() < cascadeConfig.regulationConfidenceThreshold()) {return null;}
        boolean dominant = copingAttractors.size() < 2
                           || (top.strength() - copingAttractors.get(1).strength())
                              >= cascadeConfig.regulationDominanceMargin();
        if (!dominant) {return null;}
        try {
            var strategy = CopingStrategy.valueOf(top.nodeId().toUpperCase());
            LOG.fine(() -> String.format("CAPS coping attractor: agent=%s → %s (strength=%.3f)",
                                         agentId, strategy, top.strength()));
            return strategy;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    boolean hasCopingOpportunity(List<CognitiveEmotion> emotions) {
        return emotions.stream()
                       .anyMatch(e -> e.intensity() > config.copingOpportunityThreshold());
    }

    CopingState reEvaluate(AppraisalContext originalContext, CopingState state) {
        var syntheticContext = new AppraisalContext(
                originalContext.agentId(),
                PerceivedSituation.passThrough(state.interpretationNarrative()),
                originalContext.drives(),
                originalContext.weights(),
                originalContext.habituationConfig(),
                originalContext.habituation(),
                originalContext.currentMood(),
                originalContext.dispositionAxes());

        var secResults = new ArrayList<SecResult>();
        for (var check : keywordChecks) {
            secResults.add(check.evaluate(syntheticContext));
        }

        var newDimensions = EmotionMapper.mergeDimensions(secResults);
        var newEmotions   = EmotionMapper.mapEmotions(secResults, originalContext.agentId());

        return state.withDimensions(newDimensions).withEmotions(newEmotions);
    }
}
