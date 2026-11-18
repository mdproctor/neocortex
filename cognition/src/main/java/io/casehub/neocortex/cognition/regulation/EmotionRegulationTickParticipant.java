package io.casehub.neocortex.cognition.regulation;

import io.casehub.neocortex.caps.AgentCapsState;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.coping.CopingMicroLoop;
import io.casehub.neocortex.cognition.coping.CopingState;
import io.casehub.neocortex.cognition.coping.AttentionDirectiveType;
import io.casehub.neocortex.cognition.core.CognitiveAttentionMediator;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.mindmap.AttentionBriefing;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.cognition.coping.PreCopingSafetyGate;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.core.CognitionTickParticipant;
import io.casehub.neocortex.cognition.core.CognitiveResourcePool;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.prompt.observation.RegulationTracker;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.OverlayRef;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class EmotionRegulationTickParticipant implements CognitionTickParticipant {

    private static final Logger LOG = Logger.getLogger(EmotionRegulationTickParticipant.class.getName());

    private final           AppraisalTickParticipant         appraisalParticipant;
    private final           CognitionConfig                  config;
    private final @Nullable EmotionRegulationGenerationPhase generationPhase;
    private final @Nullable MindMapStore                     mindMapStore;
    private final @Nullable String                           cognitiveSubgraphId;
    private final @Nullable CognitiveResourcePool            cognitiveResourcePool;
    private final           RegulationMonitor                regulationMonitor;
    private final @Nullable RegulationStrategyPredictor      strategyPredictor;
    private final @Nullable EffectiveCognitiveDefaults        effectiveDefaults;
    private final @Nullable CognitiveLoadEstimator           loadEstimator;
    private final @Nullable CapsEngine                       capsEngine;
    private final @Nullable RegulationCascadeConfig          cascadeConfig;
    private final @Nullable RegulationTracker                regulationTracker;
    private final @Nullable CopingMicroLoop copingMicroLoop;
    private final @Nullable GoalProposalOrchestrator goalOrchestrator;
    private final @Nullable DriveOrchestrator driveOrchestrator;
    private final @Nullable CognitiveAttentionMediator attentionMediator;


    private final ConcurrentHashMap<String, StrategyFailureSignal> failureSignals = new ConcurrentHashMap<>();

    // Bosse et al. 2010 — homeostatic baseline intensity (ERL_norm = 0.25 on [0,2], normalized to 0.3 on [0,1])
    static final double BASELINE_INTENSITY = 0.3;
    // Bosse et al. 2010 — maximum possible discrepancy from baseline
    static final double D_MAX = 1.0 - BASELINE_INTENSITY;
    // Default willingness to regulate (α_n in Bosse). Personality-dependent: high neuroticism → higher α.
    static final double DEFAULT_ALPHA = 0.5;
    // Noise floor: skip regulation when effort is negligible (cost optimization, not a theoretical gate)
    static final double EFFORT_NOISE_FLOOR = 0.01;
    static final double SAFETY_FATIGUE_THRESHOLD = 0.7;
    // Petter 2025 — cognitive resources for reappraisal exhausted above this intensity
    static final double SAFETY_INTENSITY_THRESHOLD = 0.75;
    static final double REGULATION_CONTRIBUTION_RATE = 0.15;

    private static final int HISTORY_LIMIT = 5;

    private final ConcurrentHashMap<String, EmotionRegulationResult> results = new ConcurrentHashMap<>();

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config) {
        this(appraisalParticipant, config, null, null, null, null, null, null, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase) {
        this(appraisalParticipant, config, generationPhase, null, null, null, null, null, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId, null, null, null, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId,
             effectiveDefaults, loadEstimator, null, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator,
                                            @Nullable CapsEngine capsEngine,
                                            @Nullable RegulationCascadeConfig cascadeConfig) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId,
             effectiveDefaults, loadEstimator, capsEngine, cascadeConfig, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator,
                                            @Nullable CapsEngine capsEngine,
                                            @Nullable RegulationCascadeConfig cascadeConfig,
                                            @Nullable RegulationTracker regulationTracker) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId,
             effectiveDefaults, loadEstimator, capsEngine, cascadeConfig, regulationTracker, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator,
                                            @Nullable CapsEngine capsEngine,
                                            @Nullable RegulationCascadeConfig cascadeConfig,
                                            @Nullable RegulationTracker regulationTracker,
                                            @Nullable CopingMicroLoop copingMicroLoop) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId,
             effectiveDefaults, loadEstimator, capsEngine, cascadeConfig, regulationTracker,
             copingMicroLoop, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator,
                                            @Nullable CapsEngine capsEngine,
                                            @Nullable RegulationCascadeConfig cascadeConfig,
                                            @Nullable RegulationTracker regulationTracker,
                                            @Nullable CopingMicroLoop copingMicroLoop,
                                            @Nullable GoalProposalOrchestrator goalOrchestrator) {
        this(appraisalParticipant, config, generationPhase, mindMapStore, cognitiveSubgraphId,
             effectiveDefaults, loadEstimator, capsEngine, cascadeConfig, regulationTracker,
             copingMicroLoop, goalOrchestrator, null, null);
    }

    public EmotionRegulationTickParticipant(AppraisalTickParticipant appraisalParticipant,
                                            CognitionConfig config,
                                            @Nullable EmotionRegulationGenerationPhase generationPhase,
                                            @Nullable MindMapStore mindMapStore,
                                            @Nullable String cognitiveSubgraphId,
                                            @Nullable EffectiveCognitiveDefaults effectiveDefaults,
                                            @Nullable CognitiveLoadEstimator loadEstimator,
                                            @Nullable CapsEngine capsEngine,
                                            @Nullable RegulationCascadeConfig cascadeConfig,
                                            @Nullable RegulationTracker regulationTracker,
                                            @Nullable CopingMicroLoop copingMicroLoop,
                                            @Nullable GoalProposalOrchestrator goalOrchestrator,
                                            @Nullable DriveOrchestrator driveOrchestrator,
                                            @Nullable CognitiveAttentionMediator attentionMediator) {
        this.appraisalParticipant  = appraisalParticipant;
        this.config                = config;
        this.generationPhase       = generationPhase;
        this.mindMapStore          = mindMapStore;
        this.cognitiveSubgraphId   = cognitiveSubgraphId;
        this.cognitiveResourcePool = null;
        this.regulationMonitor     = new RegulationMonitor();
        this.effectiveDefaults      = effectiveDefaults;
        this.loadEstimator         = loadEstimator;
        this.strategyPredictor     = (effectiveDefaults != null) ? new RegulationStrategyPredictor() : null;
        this.capsEngine            = capsEngine;
        this.cascadeConfig         = capsEngine != null && cascadeConfig == null
                                     ? RegulationCascadeConfig.defaults() : cascadeConfig;
        this.regulationTracker     = regulationTracker;
        this.copingMicroLoop       = copingMicroLoop;
        this.goalOrchestrator      = goalOrchestrator;
        this.driveOrchestrator     = driveOrchestrator;
        this.attentionMediator     = attentionMediator;
    }


    @Override
    public void tick(CognitionTickContext context) {
        if (!config.regulationEnabled() || !config.appraisalEnabled()) {return;}

        var appraisal = appraisalParticipant.currentResult(
                context.agentId(), context.tenantId());
        if (appraisal.isEmpty()) {return;}

        if (appraisal.get().emotions().isEmpty()) {return;}

        double maxDiscrepancy = appraisal.get().emotions().stream()
                                         .mapToDouble(e -> Math.abs(e.intensity() - BASELINE_INTENSITY))
                                         .max().orElse(0.0);

        // Bosse et al. 2010: regulation effort v_n = α_n × d / d_max (proportional to discrepancy)
        double depletion = cognitiveResourcePool != null
                ? cognitiveResourcePool.currentDepletion(context.agentId(), context.tenantId()) : 0.0;
        double alpha = DEFAULT_ALPHA * (1.0 - depletion);
        double regulationEffort = alpha * maxDiscrepancy / D_MAX;

        if (regulationEffort < EFFORT_NOISE_FLOOR) {
            if (cognitiveResourcePool != null && effectiveDefaults != null) {
                var poolConfig = effectiveDefaults.forAgentOrDefaults(context.tenantId(), context.agentId()).cognitiveResourcePoolConfig();
                if (poolConfig != null) cognitiveResourcePool.recordRecovery(context.agentId(), context.tenantId(), poolConfig.recoveryRate());
            }
            return;
        }

        double currentFatigue = depletion;
        var    pendingSignal  = failureSignals.remove(context.agentId() + ":" + context.tenantId());

        if (generationPhase == null) {return;}

        var peakEmotion = appraisal.get().emotions().stream()
                                   .max(java.util.Comparator.comparingDouble(e -> e.intensity()))
                                   .orElse(null);

        if (config.copingEnabled() && copingMicroLoop != null) {
            tickCopingPath(context, appraisal.get(), regulationEffort, currentFatigue, peakEmotion, pendingSignal);
            return;
        }

        EmotionRegulationStrategy                            pinnedStrategy       = null;
        String                                               strategySource       = "LLM";
        io.casehub.neocortex.cognitive.index.DispositionAxes axes                 = null;
        Map<String, Double>                                  capsInputActivations = null;
        AgentCapsState                                       capsState            = null;

        // Layer 1: Safety hard gate — resource-based override, fires unconditionally
        if (peakEmotion != null && (currentFatigue > SAFETY_FATIGUE_THRESHOLD
                                    || peakEmotion.intensity() > SAFETY_INTENSITY_THRESHOLD)) {
            pinnedStrategy = EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT;
            strategySource = "SAFETY_GATE";
            var intensity = peakEmotion.intensity();
            LOG.fine(() -> String.format("safety hard gate: agent=%s → AD (fatigue=%.2f intensity=%.2f)",
                                         context.agentId(), currentFatigue, intensity));
        }

        // Layer 2: CAPS settling — connectionist regulation attractor resolution
        if (pinnedStrategy == null && capsEngine != null && cascadeConfig != null) {
            capsState            = capsEngine.loadState(context.tenantId(), context.agentId());
            capsInputActivations = assembleSocialContextInputs(context);
            var settlingResult = capsEngine.settle(capsState, capsInputActivations);
            var regulationAttractors = settlingResult.attractors().stream()
                                                     .filter(a -> "regulation".equals(a.category()))
                                                     .sorted(Comparator.comparingDouble(CapsAttractor::strength).reversed())
                                                     .toList();
            if (!regulationAttractors.isEmpty()) {
                var top = regulationAttractors.get(0);
                if (top.strength() >= cascadeConfig.regulationConfidenceThreshold()) {
                    boolean dominant = regulationAttractors.size() < 2
                                       || (top.strength() - regulationAttractors.get(1).strength())
                                          >= cascadeConfig.regulationDominanceMargin();
                    if (dominant) {
                        pinnedStrategy = EmotionRegulationStrategy.valueOf(top.nodeId().toUpperCase());
                        strategySource = "CAPS";
                        var pinned = pinnedStrategy;
                        LOG.fine(() -> String.format("CAPS cascade: agent=%s → %s (strength=%.3f)",
                                                     context.agentId(), pinned, top.strength()));
                    }
                }
            }
        }

        // Layer 3: Predictor fallback
        if (pinnedStrategy == null && strategyPredictor != null && effectiveDefaults != null && pendingSignal == null) {
            pinnedStrategy = tryPredictStrategy(context, appraisal.get(), currentFatigue);
            if (pinnedStrategy != null) {
                strategySource = "PREDICTOR";
                var pinned = pinnedStrategy;
                LOG.fine(() -> String.format("regulation predictor: agent=%s strategy=%s (pinned)",
                                             context.agentId(), pinned));
            } else {
                LOG.fine(() -> String.format("regulation predictor: agent=%s fell-through → LLM",
                                             context.agentId()));
                var defaults = effectiveDefaults.forAgent(context.tenantId(), context.agentId());
                if (defaults != null && defaults.descriptor() != null) {
                    axes = defaults.descriptor().disposition();
                }
            }
        }

        var dominantType = peakEmotion != null ? peakEmotion.type() : null;
        var history = (mindMapStore != null)
                      ? EmotionRegulationMindMapAdapter.loadHistory(
                mindMapStore, context.agentId(), context.tenantId(), HISTORY_LIMIT)
                      : List.<EmotionRegulationResult>of();
        RegulationDefaults rd           = null;
        ShameContext       shameContext = null;
        if (effectiveDefaults != null) {
            var defs = effectiveDefaults.forAgent(context.tenantId(), context.agentId());
            if (defs != null) {
                rd = defs.regulationDefaults();
            }
        }
        if (dominantType != null && RegulationStrategyPredictor.emotionCategory(dominantType)
                                    == RegulationStrategyPredictor.EmotionCategory.SELF_CONSCIOUS) {
            shameContext = assembleShameContext(context, history);
            if (shameContext != null && shameContext.isSurfaceLevel()) {
                pinnedStrategy = EmotionRegulationStrategy.RESPONSE_MODULATION;
                strategySource = "SHAME_CONTEXT";
            }
        }

        Map<String, RegulationTracker.TrajectoryResult> distressTrajectories = assembleDistressTrajectories(context);

        var result = generationPhase.generate(context, appraisal.get(), history,
                                              regulationEffort, currentFatigue, pendingSignal, pinnedStrategy,
                                              axes, dominantType, rd, shameContext, null, distressTrajectories);
        if (result != null && result.isSocialStrategy() && result.targetAgentId() != null) {
            var regCtx = context.regulationContext();
            if (regCtx == null || regCtx.interactingAgents() == null
                || !regCtx.interactingAgents().contains(result.targetAgentId())) {
                var discarded = result;
                LOG.fine(() -> "Discarding social regulation result — targetAgentId '"
                               + discarded.targetAgentId() + "' not in interactingAgents");
                result = null;
            }
        }
        if (result != null) {
            String key = context.agentId() + ":" + context.tenantId();
            results.put(key, result);
            if (result.narrative() != null && !result.narrative().isBlank()) {
                appraisalParticipant.setRegulatedNarrative(
                        context.agentId(), context.tenantId(), result.narrative());
            }
            if (cognitiveResourcePool != null) {
                cognitiveResourcePool.recordEffort(context.agentId(), context.tenantId(),
                        regulationEffort * REGULATION_CONTRIBUTION_RATE);
            }
            var signal = regulationMonitor.record(context.agentId(), context.tenantId(), result);
            if (signal != null) {
                failureSignals.put(key, signal);
            }
            if (mindMapStore != null && cognitiveSubgraphId != null) {
                EmotionRegulationMindMapAdapter.write(
                        mindMapStore, context.agentId(), context.tenantId(),
                        cognitiveSubgraphId, result,
                        dominantType != null ? dominantType.name() : null,
                        context.regulationContext(), strategySource);
            }

            // CAPS weight update with regulation effectiveness (Rescorla-Wagner)
            if (capsEngine != null && capsState != null && capsInputActivations != null) {
                double outcomeIntensity   = Math.abs(result.peakOriginalIntensity() - result.regulatedIntensity());
                double outcomeValence     = result.effectiveness() > 0.5 ? 1.0 : -1.0;
                double salienceMultiplier = result.peakOriginalIntensity();
                capsEngine.updateWeights(capsState, new io.casehub.neocortex.caps.WeightUpdateContext(
                                         capsInputActivations, outcomeIntensity, outcomeValence,
                                         salienceMultiplier, "variable_ratio"));
            }
        } else {
            if (cognitiveResourcePool != null && effectiveDefaults != null) {
                var poolConfig = effectiveDefaults.forAgentOrDefaults(context.tenantId(), context.agentId()).cognitiveResourcePoolConfig();
                if (poolConfig != null) cognitiveResourcePool.recordRecovery(context.agentId(), context.tenantId(), poolConfig.recoveryRate());
            }
        }
    }

    private void tickCopingPath(CognitionTickContext context,
                                AppraisalResult appraisal,
                                double regulationEffort, double currentFatigue,
                                @Nullable CognitiveEmotion peakEmotion,
                                @Nullable StrategyFailureSignal pendingSignal) {
        var safetyGate = new PreCopingSafetyGate();
        if (safetyGate.shouldSkipCoping(currentFatigue, appraisal.emotions())) {
            LOG.fine(() -> String.format("pre-coping safety gate: agent=%s → AD (fatigue=%.2f)",
                                         context.agentId(), currentFatigue));
            var history = (mindMapStore != null)
                          ? EmotionRegulationMindMapAdapter.loadHistory(
                    mindMapStore, context.agentId(), context.tenantId(), HISTORY_LIMIT)
                          : List.<EmotionRegulationResult>of();
            var result = generationPhase.generate(context, appraisal, history,
                                                  regulationEffort, currentFatigue, pendingSignal,
                                                  EmotionRegulationStrategy.ATTENTIONAL_DEPLOYMENT);
            handleRegulationResult(context, appraisal, result, regulationEffort,
                                   peakEmotion != null ? peakEmotion.type() : null, "SAFETY_GATE");
            return;
        }

        var appraisalContext = appraisalParticipant.currentContext(context.agentId(), context.tenantId());
        if (appraisalContext.isEmpty()) {
            LOG.fine(() -> "Coping path: no AppraisalContext available for agent=" + context.agentId());
            return;
        }

        CopingDefaults  copingDefaults = null;
        DispositionAxes axes           = null;
        if (effectiveDefaults != null) {
            var defs = effectiveDefaults.forAgent(context.tenantId(), context.agentId());
            if (defs != null) {
                copingDefaults = defs.copingDefaults();
                if (defs.descriptor() != null) {
                    axes = defs.descriptor().disposition();
                }
            }
        }

        CognitiveLoadLevel loadLevel = (loadEstimator != null)
                                       ? loadEstimator.estimate(context.agentId(), context.tenantId()).level()
                                       : null;

        Map<String, Double> capsInputActivations = assembleSocialContextInputs(context);

        var copingState = copingMicroLoop.execute(
                appraisalContext.get(), appraisal, copingDefaults, axes, loadLevel,
                context.tenantId(), capsInputActivations);

        if (copingState == null || copingState.strategiesAttempted().isEmpty()) {
            LOG.fine(() -> "Coping micro-loop produced no strategies for agent=" + context.agentId());
            return;
        }

        var                     lastAttempt    = copingState.strategiesAttempted().get(copingState.strategiesAttempted().size() - 1);
        var                     copingStrategy = lastAttempt.strategy();
        EmotionRegulationResult result         = null;

        if (copingStrategy.isRegulationRouted()) {
            var regulationTarget = copingStrategy.regulationStrategy().orElseThrow();
            LOG.fine(() -> String.format("coping → regulation: agent=%s coping=%s → %s",
                                         context.agentId(), copingStrategy, regulationTarget));

            var history = (mindMapStore != null)
                          ? EmotionRegulationMindMapAdapter.loadHistory(
                    mindMapStore, context.agentId(), context.tenantId(), HISTORY_LIMIT)
                          : List.<EmotionRegulationResult>of();
            result = generationPhase.generateForCoping(context, appraisal, history,
                                                       regulationEffort, currentFatigue, copingStrategy, regulationTarget,
                                                       copingState.interpretationNarrative());
            handleRegulationResult(context, appraisal, result, regulationEffort,
                                   peakEmotion != null ? peakEmotion.type() : null,
                                   "COPING:" + copingStrategy.name());
        } else {
            LOG.fine(() -> String.format("coping → direct effect: agent=%s coping=%s (no regulation)",
                                         context.agentId(), copingStrategy));
        }

        // CAPS weight update on coping attractor (not regulation attractor)
        if (capsEngine != null && result != null) {
            var    capsState          = capsEngine.loadState(context.tenantId(), context.agentId());
            double outcomeIntensity   = Math.abs(result.peakOriginalIntensity() - result.regulatedIntensity());
            double outcomeValence     = result.effectiveness() > 0.5 ? 1.0 : -1.0;
            double salienceMultiplier = result.peakOriginalIntensity();
            capsEngine.updateWeights(capsState, new io.casehub.neocortex.caps.WeightUpdateContext(
                    capsInputActivations, outcomeIntensity, outcomeValence,
                    salienceMultiplier, "variable_ratio"));
        }

        // Dispatch accumulated effects from CopingState
        double peakIntensity = peakEmotion != null ? peakEmotion.intensity() : 0.5;
        dispatchCopingEffects(context, copingState, peakIntensity);
    }

    private void handleRegulationResult(CognitionTickContext context,
                                        AppraisalResult appraisal,
                                        @Nullable EmotionRegulationResult result,
                                        double regulationEffort,
                                        @Nullable EmotionType dominantType,
                                        String strategySource) {
        if (result != null && result.isSocialStrategy() && result.targetAgentId() != null) {
            var regCtx = context.regulationContext();
            if (regCtx == null || regCtx.interactingAgents() == null
                || !regCtx.interactingAgents().contains(result.targetAgentId())) {
                var discarded = result;
                LOG.fine(() -> "Discarding social regulation result — targetAgentId '"
                               + discarded.targetAgentId() + "' not in interactingAgents");
                result = null;
            }
        }
        if (result != null) {
            String key = context.agentId() + ":" + context.tenantId();
            results.put(key, result);
            if (result.narrative() != null && !result.narrative().isBlank()) {
                appraisalParticipant.setRegulatedNarrative(
                        context.agentId(), context.tenantId(), result.narrative());
            }
            if (cognitiveResourcePool != null) {
                cognitiveResourcePool.recordEffort(context.agentId(), context.tenantId(),
                                                   regulationEffort * REGULATION_CONTRIBUTION_RATE);
            }
            var signal = regulationMonitor.record(context.agentId(), context.tenantId(), result);
            if (signal != null) {
                failureSignals.put(key, signal);
            }
            if (mindMapStore != null && cognitiveSubgraphId != null) {
                EmotionRegulationMindMapAdapter.write(
                        mindMapStore, context.agentId(), context.tenantId(),
                        cognitiveSubgraphId, result,
                        dominantType != null ? dominantType.name() : null,
                        context.regulationContext(), strategySource);
            }
        } else {
            if (cognitiveResourcePool != null && effectiveDefaults != null) {
                var poolConfig = effectiveDefaults.forAgentOrDefaults(context.tenantId(), context.agentId()).cognitiveResourcePoolConfig();
                if (poolConfig != null) {
                    cognitiveResourcePool.recordRecovery(context.agentId(), context.tenantId(), poolConfig.recoveryRate());
                }
            }
        }
    }

    private void dispatchCopingEffects(CognitionTickContext context, CopingState copingState,
                                       double peakIntensity) {
        if (goalOrchestrator != null) {
            for (var op : copingState.goalOperations()) {
                switch (op.type()) {
                    case CREATE -> {
                        var strategies = copingState.strategiesAttempted();
                        var strategyName = strategies.isEmpty()
                                ? "unknown"
                                : strategies.get(strategies.size() - 1).strategy().name();
                        var proposal = new io.casehub.neocortex.cognition.goal.DriveGoalProposal(
                                op.axis(), op.goalName(), op.goalDescription(),
                                op.formationReason(), peakIntensity, null,
                                Map.of("source", "coping", "copingStrategy", strategyName));
                        goalOrchestrator.appendGoals(context.agentId(), context.tenantId(), List.of(proposal));
                    }
                    case DROP -> goalOrchestrator.requestAbandonment(
                            context.agentId(), context.tenantId(), op.goalName(), op.formationReason());
                    case DEFER -> goalOrchestrator.requestDemotion(
                            context.agentId(), context.tenantId(), op.goalName(),
                            io.casehub.eidos.api.GoalPriority.SECONDARY, op.formationReason());
                }
            }
        }

        if (driveOrchestrator != null && !copingState.driveModulations().isEmpty()) {
            var modMap = new HashMap<DriveAxis, Double>();
            for (var mod : copingState.driveModulations()) {
                modMap.merge(mod.axis(), mod.intensityDelta(), Double::sum);
            }
            driveOrchestrator.recordAppraisalModulation(context.agentId(), context.tenantId(), modMap);
        }

        if (attentionMediator != null && !copingState.attentionDirectives().isEmpty()) {
            var signals = new java.util.ArrayList<AttentionSignal>();
            for (var dir : copingState.attentionDirectives()) {
                var category = dir.type() == AttentionDirectiveType.FOCUS
                               ? SignalCategory.COPING_FOCUS
                               : SignalCategory.COPING_SUPPRESS;
                signals.add(new AttentionSignal(
                        context.agentId(), context.tenantId(), category,
                        dir.target(), dir.target(), 0.8, dir.reason()));
            }
            var briefing = new AttentionBriefing(
                    context.agentId(), context.tenantId(), signals, 0.0, java.time.Instant.now());
            attentionMediator.queueBriefing(briefing);
        }
    }


    private @Nullable EmotionRegulationStrategy tryPredictStrategy(
            CognitionTickContext context,
            AppraisalResult appraisal,
            double currentFatigue) {
        var defaults = effectiveDefaults.forAgent(context.tenantId(), context.agentId());
        if (defaults == null || defaults.descriptor() == null) {return null;}
        var axes = defaults.descriptor().disposition();
        if (axes == null) {return null;}
        var rd = defaults.regulationDefaults();

        var peakEmotion = appraisal.emotions().stream()
                                   .max(java.util.Comparator.comparingDouble(e -> e.intensity()))
                                   .orElse(null);
        if (peakEmotion == null) {return null;}

        if (rd != null && mindMapStore != null) {
            var emotionOutcomes = EmotionRegulationMindMapAdapter.loadOutcomes(
                    mindMapStore, context.agentId(), context.tenantId(), peakEmotion.type(), HISTORY_LIMIT);
            rd = RegulationCapacityModulator.modulate(rd, emotionOutcomes);
        }

        CognitiveLoadLevel loadLevel = (loadEstimator != null)
                                       ? loadEstimator.estimate(context.agentId(), context.tenantId()).level()
                                       : null;

        var prediction = strategyPredictor.predict(
                rd, axes, peakEmotion.type(), peakEmotion.intensity(), currentFatigue, loadLevel);

        if (LOG.isLoggable(java.util.logging.Level.FINE)) {
            if (prediction.isPresent()) {
                LOG.fine(String.format("regulation predictor detail: agent=%s emotion=%s intensity=%.2f → %s (confidence=%.2f)",
                                       context.agentId(), peakEmotion.type(), peakEmotion.intensity(),
                                       prediction.get().strategy(), prediction.get().confidence()));
            } else {
                LOG.fine(String.format("regulation predictor detail: agent=%s emotion=%s intensity=%.2f → fell-through (personality=%s/%s/%s/%s/%s)",
                                       context.agentId(), peakEmotion.type(), peakEmotion.intensity(),
                                       axes.socialOrient(), axes.ruleFollowing(), axes.riskAppetite(),
                                       axes.autonomy(), axes.conflictMode()));
            }
        }

        return prediction.map(RegulationPrediction::strategy).orElse(null);
    }


    private Map<String, Double> assembleSocialContextInputs(CognitionTickContext context) {
        var    regCtx   = context.regulationContext();
        double maxTrust = 0.0;

        if (mindMapStore != null && regCtx != null && regCtx.interactingAgents() != null) {
            for (var targetId : regCtx.interactingAgents()) {
                var schema = resolveRelationalSchema(context.agentId(), targetId, context.tenantId());
                if (schema != null) {
                    maxTrust = Math.max(maxTrust, (schema.trust() + 1.0) / 2.0);
                }
            }
        }

        return SocialContextInputComputer.computeFromRawInputs(
                regCtx, maxTrust, null, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private @Nullable Map<String, RegulationTracker.TrajectoryResult> assembleDistressTrajectories(
            CognitionTickContext context) {
        if (regulationTracker == null || context.regulationContext() == null
                || context.regulationContext().interactingAgents() == null) {
            return null;
        }
        var trajectories = new HashMap<String, RegulationTracker.TrajectoryResult>();
        for (var targetId : context.regulationContext().interactingAgents()) {
            regulationTracker.computeTrajectory(targetId).ifPresent(t -> trajectories.put(targetId, t));
        }
        return trajectories.isEmpty() ? null : trajectories;
    }

    private @Nullable ShameContext assembleShameContext(
            CognitionTickContext context,
            List<EmotionRegulationResult> history) {
        int               shameCount = 0;
        java.time.Instant mostRecent = null;
        var               cutoff     = java.time.Instant.now().minus(java.time.Duration.ofMinutes(30));
        for (var entry : history) {
            boolean isSelfConscious = entry.targetEmotions().stream()
                                           .anyMatch(e -> "SHAME".equals(e) || "REMORSE".equals(e));
            if (!isSelfConscious) {continue;}
            shameCount++;
            if (mostRecent == null) {mostRecent = java.time.Instant.now();}
        }

        double closeness = 0.0;
        String role      = null;
        if (context.targetAgentId() != null
            && !context.targetAgentId().equals(context.agentId())
            && mindMapStore != null) {
            var schema = resolveRelationalSchema(
                    context.agentId(), context.targetAgentId(), context.tenantId());
            if (schema != null) {
                closeness = schema.intimacy();
                role      = schema.role();
            }
        }

        double grandiosity = 0.0;
        double callousness = 0.0;
        if (effectiveDefaults != null) {
            var defaults = effectiveDefaults.forAgent(context.tenantId(), context.agentId());
            if (defaults != null && defaults.personalityProfile() != null) {
                var facets = defaults.personalityProfile().facets();
                if (facets != null) {
                    for (var f : facets) {
                        if (PersonalityFacet.GRANDIOSITY.equals(f.facet())) {
                            grandiosity = f.score();
                        } else if (PersonalityFacet.CALLOUSNESS.equals(f.facet())) {
                            callousness = f.score();
                        }
                    }
                }
            }
        }

        return new ShameContext(shameCount, mostRecent, closeness, role, grandiosity, callousness);
    }

    private @Nullable RelationalSchema resolveRelationalSchema(
            String observerAgentId, String targetAgentId, String tenantId) {
        var peopleSg = mindMapStore.listSubgraphs(tenantId).stream()
                                   .filter(sg -> SubgraphTypes.PERSON.equals(sg.type())
                                                 || SubgraphTypes.PERSON.equals(sg.name()))
                                   .findFirst();
        if (peopleSg.isEmpty()) {return null;}

        var nodes = mindMapStore.nodesIn(peopleSg.get().id(), tenantId);

        String targetNodeId = null;
        for (var node : nodes) {
            if (node.traits().contains("overlay")) {continue;}
            if (targetAgentId.equals(node.property("agentId").orElse(null))) {
                targetNodeId = node.id();
                break;
            }
        }
        if (targetNodeId == null) {return null;}

        for (var overlay : nodes) {
            if (!overlay.traits().contains("overlay")) {continue;}
            if (!observerAgentId.equals(overlay.property(OverlayRef.AGENT_ID).orElse(null))) {
                continue;
            }
            var sharedId = OverlayRef.sharedNodeId(overlay).orElse(null);
            if (targetNodeId.equals(sharedId)) {
                return RelationalSchema.fromProperties(overlay.properties());
            }
        }
        return null;
    }


    public Optional<EmotionRegulationResult> currentResult(String agentId, String tenantId) {
        return Optional.ofNullable(results.get(agentId + ":" + tenantId));
    }
}
