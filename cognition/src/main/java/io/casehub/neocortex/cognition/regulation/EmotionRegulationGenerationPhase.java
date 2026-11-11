package io.casehub.neocortex.cognition.regulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.RegulationDefaults;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import io.casehub.neocortex.cognition.prompt.observation.RegulationTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EmotionRegulationGenerationPhase {

    private static final Logger LOG = Logger.getLogger(EmotionRegulationGenerationPhase.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
                                        You are a character's emotion regulation system — the part that decides \
                                        whether and how to regulate emotional responses after appraisal.
                                        
                                        The five regulation strategies (Gross 1998 process model):
                                        
                                        1. SITUATION_SELECTION — Avoid or approach situations likely to trigger \
                                        specific emotions. Environmental strategy: produces a goal directive.
                                        2. SITUATION_MODIFICATION — Change an existing situation to alter its \
                                        emotional impact. Environmental strategy: produces a goal directive.
                                        3. ATTENTIONAL_DEPLOYMENT — Redirect attention away from (distraction) \
                                        or toward (concentration) emotional aspects. Internal strategy.
                                        4. COGNITIVE_CHANGE — Reappraise the meaning of the situation to change \
                                        its emotional impact. Internal strategy.
                                        5. RESPONSE_MODULATION — Suppress or amplify the emotional expression \
                                        after it has been generated. Internal strategy.
                                        
                                        Social regulation strategies (Zaki & Williams 2013):
                                        
                                        6. SOCIAL_SUPPORT_SEEKING — Seek comfort, reassurance, or advice from \
                                        a specific person present. Intrinsic: regulates YOUR emotions through \
                                        social connection. Produces a goal to approach the target agent.
                                        7. SOCIAL_SHARING — Share your emotional experience with another person \
                                        for relief through disclosure. Intrinsic. Not advice-seeking.
                                        8. SOCIAL_WITHDRAWAL — Remove yourself from social interaction to \
                                        self-regulate alone. Intrinsic. Produces an avoidance goal.
                                        9. CO_REGULATION — Actively comfort or soothe a person you observe \
                                        to be in distress. Extrinsic: regulates THEIR emotions.
                                        10. DELIBERATE_PROVOCATION — Intentionally provoke an emotional response \
                                        in another person for strategic purposes. Extrinsic. Rare.
                                        
                                        For social strategies (6-10), set targetAgentId to one of the available \
                                        agents listed in the context. For non-social strategies (1-5), set \
                                        targetAgentId to null.
                                        
                                        Strategy selection factors (Petter et al. 2025):
                                        - High intensity emotions favor ATTENTIONAL_DEPLOYMENT (distraction) \
                                        because cognitive resources are overwhelmed
                                        - Low-to-moderate intensity favors COGNITIVE_CHANGE (reappraisal) \
                                        because it requires cognitive capacity
                                        - Personality shapes preference: high neuroticism → more RESPONSE_MODULATION, \
                                        high openness → more COGNITIVE_CHANGE, high conscientiousness → more SITUATION_SELECTION
                                        - Cognitive load constrains strategy: HIGH load → prefer ATTENTIONAL_DEPLOYMENT \
                                        or RESPONSE_MODULATION (low cognitive cost); LOW load → COGNITIVE_CHANGE \
                                        or SITUATION_SELECTION are viable (require deliberation)
                                        
                                        Respond with JSON only:
                                        {
                                          "strategy": "STRATEGY_NAME",
                                          "targetEmotions": ["emotion1"],
                                          "targetDrives": ["drive1"],
                                          "peakOriginalIntensity": 0.85,
                                          "regulatedIntensity": 0.4,
                                          "effectiveness": 0.6,
                                          "narrative": "Second-person present-tense description of the regulation",
                                          "regulationAxis": "AXIS_NAME or null",
                                          "environmentalDirective": "directive text or null",
                                          "targetAgentId": "agent-id or null"
                                        }
                                        
                                        Rules:
                                        - regulationAxis and environmentalDirective are REQUIRED for SITUATION_SELECTION \
                                        and SITUATION_MODIFICATION, null for all others
                                        - regulationAxis must be one of: CURIOSITY, COMPETENCE, AFFILIATION, AUTONOMY
                                        - targetAgentId is REQUIRED for social strategies (6-10), null for intrapersonal (1-5)
                                        - narrative should be vivid, second-person, present tense ("You feel the pull...")
                                        - effectiveness is 0.0-1.0 (how much the regulation reduces emotional intensity)
                                        - regulatedIntensity = peakOriginalIntensity * (1 - effectiveness)""";

    private final @Nullable SessionPipeline sessionPipeline;
    private final @Nullable CognitiveLoadEstimator loadEstimator;

    public EmotionRegulationGenerationPhase(@Nullable SessionPipeline sessionPipeline) {
        this(sessionPipeline, null);
    }

    public EmotionRegulationGenerationPhase(@Nullable SessionPipeline sessionPipeline,
                                             @Nullable CognitiveLoadEstimator loadEstimator) {
        this.sessionPipeline = sessionPipeline;
        this.loadEstimator = loadEstimator;
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                       AppraisalResult appraisal,
                                                       List<EmotionRegulationResult> history) {
        return generate(context, appraisal, history, 1.0, 0.0);
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                       AppraisalResult appraisal,
                                                       List<EmotionRegulationResult> history,
                                                       double regulationEffort) {
        return generate(context, appraisal, history, regulationEffort, 0.0);
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                       AppraisalResult appraisal,
                                                       List<EmotionRegulationResult> history,
                                                       double regulationEffort,
                                                       double regulationFatigue) {
        return generate(context, appraisal, history, regulationEffort, regulationFatigue, null);
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                       AppraisalResult appraisal,
                                                       List<EmotionRegulationResult> history,
                                                       double regulationEffort,
                                                       double regulationFatigue,
                                                       @Nullable StrategyFailureSignal failureSignal) {
        return generate(context, appraisal, history, regulationEffort, regulationFatigue, failureSignal, null);
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                       AppraisalResult appraisal,
                                                       List<EmotionRegulationResult> history,
                                                       double regulationEffort,
                                                       double regulationFatigue,
                                                       @Nullable StrategyFailureSignal failureSignal,
                                                       @Nullable EmotionRegulationStrategy pinnedStrategy) {
        if (sessionPipeline == null) return null;

        try {
            String response = invokeLlm(context, appraisal, history, regulationEffort,
                    regulationFatigue, failureSignal, pinnedStrategy);
            if (response == null) return null;
            return parseResponse(response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Emotion regulation generation failed", e);
            return null;
        }
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                      AppraisalResult appraisal,
                                                      List<EmotionRegulationResult> history,
                                                      double regulationEffort,
                                                      double regulationFatigue,
                                                      @Nullable StrategyFailureSignal failureSignal,
                                                      @Nullable EmotionRegulationStrategy pinnedStrategy,
                                                      @Nullable DispositionAxes axes,
                                                      @Nullable EmotionType dominantEmotionType) {
        if (sessionPipeline == null) {return null;}
        try {
            String response = invokeLlm(context, appraisal, history, regulationEffort,
                                        regulationFatigue, failureSignal, pinnedStrategy, axes, dominantEmotionType);
            if (response == null) {return null;}
            return parseResponse(response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Emotion regulation generation failed", e);
            return null;
        }
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                      AppraisalResult appraisal,
                                                      List<EmotionRegulationResult> history,
                                                      double regulationEffort,
                                                      double regulationFatigue,
                                                      @Nullable StrategyFailureSignal failureSignal,
                                                      @Nullable EmotionRegulationStrategy pinnedStrategy,
                                                      @Nullable DispositionAxes axes,
                                                      @Nullable EmotionType dominantEmotionType,
                                                      @Nullable RegulationDefaults regulationDefaults,
                                                      @Nullable ShameContext shameContext) {
        return generate(context, appraisal, history, regulationEffort, regulationFatigue,
                failureSignal, pinnedStrategy, axes, dominantEmotionType, regulationDefaults,
                shameContext, null);
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                      AppraisalResult appraisal,
                                                      List<EmotionRegulationResult> history,
                                                      double regulationEffort,
                                                      double regulationFatigue,
                                                      @Nullable StrategyFailureSignal failureSignal,
                                                      @Nullable EmotionRegulationStrategy pinnedStrategy,
                                                      @Nullable DispositionAxes axes,
                                                      @Nullable EmotionType dominantEmotionType,
                                                      @Nullable RegulationDefaults regulationDefaults,
                                                      @Nullable ShameContext shameContext,
                                                      @Nullable String situationContext) {
        if (sessionPipeline == null) return null;
        var effectiveStrategy = pinnedStrategy;
        if (shameContext != null && shameContext.isSurfaceLevel()) {
            effectiveStrategy = EmotionRegulationStrategy.RESPONSE_MODULATION;
        }
        try {
            String response = invokeLlm(context, appraisal, history, regulationEffort,
                    regulationFatigue, failureSignal, effectiveStrategy, axes, dominantEmotionType,
                    regulationDefaults, shameContext, situationContext);
            if (response == null) return null;
            return parseResponse(response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Emotion regulation generation failed", e);
            return null;
        }
    }

    public @Nullable EmotionRegulationResult generate(CognitionTickContext context,
                                                      AppraisalResult appraisal,
                                                      List<EmotionRegulationResult> history,
                                                      double regulationEffort,
                                                      double regulationFatigue,
                                                      @Nullable StrategyFailureSignal failureSignal,
                                                      @Nullable EmotionRegulationStrategy pinnedStrategy,
                                                      @Nullable DispositionAxes axes,
                                                      @Nullable EmotionType dominantEmotionType,
                                                      @Nullable RegulationDefaults regulationDefaults,
                                                      @Nullable ShameContext shameContext,
                                                      @Nullable String situationContext,
                                                      @Nullable Map<String, RegulationTracker.TrajectoryResult> distressTrajectories) {
        if (sessionPipeline == null) return null;
        var effectiveStrategy = pinnedStrategy;
        if (shameContext != null && shameContext.isSurfaceLevel()) {
            effectiveStrategy = EmotionRegulationStrategy.RESPONSE_MODULATION;
        }
        try {
            String response = invokeLlm(context, appraisal, history, regulationEffort,
                    regulationFatigue, failureSignal, effectiveStrategy, axes, dominantEmotionType,
                    regulationDefaults, shameContext, situationContext, distressTrajectories);
            if (response == null) return null;
            return parseResponse(response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Emotion regulation generation failed", e);
            return null;
        }
    }


    private String invokeLlm(CognitionTickContext context, AppraisalResult appraisal,
                              List<EmotionRegulationResult> history, double regulationEffort,
                              double regulationFatigue,
                              @Nullable StrategyFailureSignal failureSignal,
                              @Nullable EmotionRegulationStrategy pinnedStrategy) {
        var session = sessionPipeline.openSession("emotion-regulation", AgentSessionInit.of(SYSTEM_PROMPT));
        var loadEstimate = (loadEstimator != null)
                ? loadEstimator.estimate(context.agentId(), context.tenantId())
                : null;
        var userMessage = buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                regulationFatigue, failureSignal, pinnedStrategy);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }

    private String invokeLlm(CognitionTickContext context, AppraisalResult appraisal,
                             List<EmotionRegulationResult> history, double regulationEffort,
                             double regulationFatigue,
                             @Nullable StrategyFailureSignal failureSignal,
                             @Nullable EmotionRegulationStrategy pinnedStrategy,
                             @Nullable DispositionAxes axes,
                             @Nullable EmotionType dominantEmotionType) {
        var session = sessionPipeline.openSession("emotion-regulation", AgentSessionInit.of(SYSTEM_PROMPT));
        var loadEstimate = (loadEstimator != null)
                           ? loadEstimator.estimate(context.agentId(), context.tenantId())
                           : null;
        var userMessage = buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                                           regulationFatigue, failureSignal, pinnedStrategy, axes, dominantEmotionType);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
               .filter(e -> e instanceof AgentEvent.TextDelta)
               .map(e -> ((AgentEvent.TextDelta) e).text())
               .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }

    private String invokeLlm(CognitionTickContext context, AppraisalResult appraisal,
                             List<EmotionRegulationResult> history, double regulationEffort,
                             double regulationFatigue,
                             @Nullable StrategyFailureSignal failureSignal,
                             @Nullable EmotionRegulationStrategy pinnedStrategy,
                             @Nullable DispositionAxes axes,
                             @Nullable EmotionType dominantEmotionType,
                             @Nullable RegulationDefaults regulationDefaults,
                             @Nullable ShameContext shameContext) {
        var session = sessionPipeline.openSession("emotion-regulation", AgentSessionInit.of(SYSTEM_PROMPT));
        var loadEstimate = (loadEstimator != null)
                           ? loadEstimator.estimate(context.agentId(), context.tenantId())
                           : null;
        var userMessage = buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                                           regulationFatigue, failureSignal, pinnedStrategy, axes,
                                           dominantEmotionType, regulationDefaults, shameContext, null);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
               .filter(e -> e instanceof AgentEvent.TextDelta)
               .map(e -> ((AgentEvent.TextDelta) e).text())
               .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }

    private String invokeLlm(CognitionTickContext context, AppraisalResult appraisal,
                             List<EmotionRegulationResult> history, double regulationEffort,
                             double regulationFatigue,
                             @Nullable StrategyFailureSignal failureSignal,
                             @Nullable EmotionRegulationStrategy pinnedStrategy,
                             @Nullable DispositionAxes axes,
                             @Nullable EmotionType dominantEmotionType,
                             @Nullable RegulationDefaults regulationDefaults,
                             @Nullable ShameContext shameContext,
                             @Nullable String situationContext) {
        var session = sessionPipeline.openSession("emotion-regulation", AgentSessionInit.of(SYSTEM_PROMPT));
        var loadEstimate = (loadEstimator != null)
                           ? loadEstimator.estimate(context.agentId(), context.tenantId())
                           : null;
        var userMessage = buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                                           regulationFatigue, failureSignal, pinnedStrategy, axes,
                                           dominantEmotionType, regulationDefaults, shameContext,
                                           situationContext);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
               .filter(e -> e instanceof AgentEvent.TextDelta)
               .map(e -> ((AgentEvent.TextDelta) e).text())
               .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }

    private String invokeLlm(CognitionTickContext context, AppraisalResult appraisal,
                             List<EmotionRegulationResult> history, double regulationEffort,
                             double regulationFatigue,
                             @Nullable StrategyFailureSignal failureSignal,
                             @Nullable EmotionRegulationStrategy pinnedStrategy,
                             @Nullable DispositionAxes axes,
                             @Nullable EmotionType dominantEmotionType,
                             @Nullable RegulationDefaults regulationDefaults,
                             @Nullable ShameContext shameContext,
                             @Nullable String situationContext,
                             @Nullable Map<String, RegulationTracker.TrajectoryResult> distressTrajectories) {
        var session = sessionPipeline.openSession("emotion-regulation", AgentSessionInit.of(SYSTEM_PROMPT));
        var loadEstimate = (loadEstimator != null)
                           ? loadEstimator.estimate(context.agentId(), context.tenantId())
                           : null;
        var userMessage = buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                                           regulationFatigue, failureSignal, pinnedStrategy, axes,
                                           dominantEmotionType, regulationDefaults, shameContext,
                                           situationContext, distressTrajectories);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
               .filter(e -> e instanceof AgentEvent.TextDelta)
               .map(e -> ((AgentEvent.TextDelta) e).text())
               .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }


    static String buildUserMessage(CognitionTickContext context, AppraisalResult appraisal,
                                     List<EmotionRegulationResult> history, double regulationEffort,
                                     @Nullable CognitiveLoadEstimate loadEstimate,
                                     double regulationFatigue,
                                     @Nullable StrategyFailureSignal failureSignal) {
        return buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                regulationFatigue, failureSignal, null);
    }

    static String buildUserMessage(CognitionTickContext context, AppraisalResult appraisal,
                                   List<EmotionRegulationResult> history, double regulationEffort,
                                   @Nullable CognitiveLoadEstimate loadEstimate,
                                   double regulationFatigue,
                                   @Nullable StrategyFailureSignal failureSignal,
                                   @Nullable EmotionRegulationStrategy pinnedStrategy) {return buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate, regulationFatigue, failureSignal, pinnedStrategy, null, null, null, null, null);}

    static String buildUserMessage(CognitionTickContext context, AppraisalResult appraisal,
                                   List<EmotionRegulationResult> history, double regulationEffort,
                                   @Nullable CognitiveLoadEstimate loadEstimate,
                                   double regulationFatigue,
                                   @Nullable StrategyFailureSignal failureSignal,
                                   @Nullable EmotionRegulationStrategy pinnedStrategy,
                                   @Nullable DispositionAxes axes,
                                   @Nullable EmotionType dominantEmotionType) {
        return buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                regulationFatigue, failureSignal, pinnedStrategy, axes, dominantEmotionType, null, null, null);
    }

    static String buildUserMessage(CognitionTickContext context, AppraisalResult appraisal,
                                   List<EmotionRegulationResult> history, double regulationEffort,
                                   @Nullable CognitiveLoadEstimate loadEstimate,
                                   double regulationFatigue,
                                   @Nullable StrategyFailureSignal failureSignal,
                                   @Nullable EmotionRegulationStrategy pinnedStrategy,
                                   @Nullable DispositionAxes axes,
                                   @Nullable EmotionType dominantEmotionType,
                                   @Nullable RegulationDefaults regulationDefaults,
                                   @Nullable ShameContext shameContext,
                                   @Nullable String situationContext) {
        return buildUserMessage(context, appraisal, history, regulationEffort, loadEstimate,
                                regulationFatigue, failureSignal, pinnedStrategy, axes, dominantEmotionType,
                                regulationDefaults, shameContext, situationContext, null);
    }

    static String buildUserMessage(CognitionTickContext context, AppraisalResult appraisal,
                                   List<EmotionRegulationResult> history, double regulationEffort,
                                   @Nullable CognitiveLoadEstimate loadEstimate,
                                   double regulationFatigue,
                                   @Nullable StrategyFailureSignal failureSignal,
                                   @Nullable EmotionRegulationStrategy pinnedStrategy,
                                   @Nullable DispositionAxes axes,
                                   @Nullable EmotionType dominantEmotionType,
                                   @Nullable RegulationDefaults regulationDefaults,
                                   @Nullable ShameContext shameContext,
                                   @Nullable String situationContext,
                                   @Nullable Map<String, RegulationTracker.TrajectoryResult> distressTrajectories) {
        var sb = new StringBuilder();

        if (pinnedStrategy != null) {
            sb.append("Strategy: ").append(pinnedStrategy.name())
              .append(" (personality-derived — generate the embodied experience for this strategy)\n\n");
        }

        sb.append("Regulation effort: ").append(String.format("%.2f", regulationEffort))
          .append(" (0=negligible, 1=maximum urgency)\n\n");

        if (regulationFatigue > 0.01) {
            sb.append("Regulation fatigue: ").append(String.format("%.0f%%", regulationFatigue * 100))
              .append(" — repeated regulation is depleting capacity");
            if (regulationFatigue > 0.7) {
                sb.append("; approaching breakdown, suppression may fail entirely");
            }
            sb.append("\n\n");
        }

        if (loadEstimate != null) {
            sb.append("Cognitive load: ").append(loadEstimate.level().name())
              .append(" (drives=").append(loadEstimate.activeDrives())
              .append(", goals=").append(loadEstimate.activeGoals())
              .append(", ToM targets=").append(loadEstimate.mentalModelTargets())
              .append(")\n\n");
            if (loadEstimate.level() == CognitiveLoadLevel.HIGH) {
                sb.append("Under HIGH cognitive load, you cannot hold the multi-step reasoning ")
                  .append("chain needed for COGNITIVE_CHANGE or the environmental assessment ")
                  .append("needed for SITUATION_SELECTION/MODIFICATION. These strategies require ")
                  .append("deliberation you don't have capacity for right now. Only ")
                  .append("ATTENTIONAL_DEPLOYMENT (automatic attention shift) and ")
                  .append("RESPONSE_MODULATION (expression control) are executable — they're ")
                  .append("reflexive, not deliberative. You MUST choose one of these two.\n\n");
            }
        }

        if (failureSignal != null) {
            if (failureSignal.breakthrough()) {
                sb.append("⚠ BREAKTHROUGH: ").append(failureSignal.failingStrategy().name())
                  .append(" has failed entirely — effectiveness dropped to ")
                  .append(String.format("%.2f", failureSignal.currentEffectiveness()))
                  .append(". The held-back emotion is breaking through. Choose a different strategy.\n\n");
            } else {
                sb.append("⚠ STRATEGY FAILING: ").append(failureSignal.failingStrategy().name())
                  .append(" effectiveness declined from ")
                  .append(String.format("%.2f", failureSignal.initialEffectiveness()))
                  .append(" to ").append(String.format("%.2f", failureSignal.currentEffectiveness()))
                  .append(" over ").append(failureSignal.consecutiveDeclines())
                  .append(" ticks. Consider switching strategies.\n\n");
            }
        }

        if (pinnedStrategy == null && axes != null) {
            sb.append("Personality disposition (predictor did not pin a strategy — use these to ")
              .append("select a personality-congruent regulation):\n")
              .append("  conflict-mode: ").append(axes.conflictMode()).append("\n")
              .append("  social-orient: ").append(axes.socialOrient()).append("\n")
              .append("  risk-appetite: ").append(axes.riskAppetite()).append("\n")
              .append("  autonomy: ").append(axes.autonomy()).append("\n")
              .append("  rule-following: ").append(axes.ruleFollowing()).append("\n\n");
        }

        if (pinnedStrategy == null && regulationDefaults != null) {
            sb.append("Regulation capacities (personality-derived, Barańczuk 2019):\n")
              .append("  reappraisal: ").append(capacityLabel(regulationDefaults.reappraisalCapacity()))
              .append(" (").append(String.format("%.2f", regulationDefaults.reappraisalCapacity())).append(")\n")
              .append("  suppression: ").append(capacityLabel(regulationDefaults.suppressionCapacity()))
              .append(" (").append(String.format("%.2f", regulationDefaults.suppressionCapacity())).append(")\n")
              .append("  redirect tendency: ").append(capacityLabel(regulationDefaults.redirectTendency()))
              .append(" (").append(String.format("%.2f", regulationDefaults.redirectTendency())).append(")\n\n");
        }

        if (pinnedStrategy == null && context.regulationContext() != null
                && context.regulationContext().interactingAgents() != null
                && !context.regulationContext().interactingAgents().isEmpty()) {
            sb.append("Available agents: ")
              .append(String.join(", ", context.regulationContext().interactingAgents()))
              .append("\n");
            if (regulationDefaults != null) {
                sb.append("Social regulation tendency: ")
                  .append(capacityLabel(regulationDefaults.socialRegulationTendency()))
                  .append(" (").append(String.format("%.2f", regulationDefaults.socialRegulationTendency()))
                  .append(")\n");
            }
            if (distressTrajectories != null && !distressTrajectories.isEmpty()) {
                sb.append("Distress trajectories (for extrinsic regulation — co-regulation or provocation):\n");
                for (var agentId : context.regulationContext().interactingAgents()) {
                    var trajectory = distressTrajectories.get(agentId);
                    if (trajectory != null) {
                        sb.append("  ").append(agentId).append(": ").append(trajectory.trajectory().name())
                          .append(" (Δ").append(String.format("%+.2f", trajectory.delta())).append(")\n");
                    }
                }
            }
            sb.append("\n");
        }

        boolean isControllableNeg = dominantEmotionType != null
                && RegulationStrategyPredictor.emotionCategory(dominantEmotionType)
                        == RegulationStrategyPredictor.EmotionCategory.CONTROLLABLE_NEGATIVE;
        boolean isSelfConscious = dominantEmotionType != null
                && RegulationStrategyPredictor.emotionCategory(dominantEmotionType)
                        == RegulationStrategyPredictor.EmotionCategory.SELF_CONSCIOUS;

        if ((pinnedStrategy == null || pinnedStrategy == EmotionRegulationStrategy.COGNITIVE_CHANGE)
                && isControllableNeg && regulationDefaults != null
                && regulationDefaults.reappraisalCapacity() >= 0.40) {
            String impulseStrategy = regulationDefaults.redirectTendency() > regulationDefaults.suppressionCapacity()
                    ? "avoidance/withdrawal (redirect)" : "suppression (response modulation)";
            sb.append("Temporal staging (Gross 2015 extended process model): This personality's ")
              .append("first impulse for controllable emotions is ").append(impulseStrategy)
              .append(". But the personality also has reappraisal capacity (")
              .append(String.format("%.2f", regulationDefaults.reappraisalCapacity()))
              .append("). The regulation should show TEMPORAL PROGRESSION: ")
              .append("the impulse manages the immediate emotional response (personality-congruent), ")
              .append("then cognitive flexibility engages for sustained processing (reappraisal). ")
              .append("Both phases should be visible in the narrative — the first response AND ")
              .append("the reflective shift that follows. Choose COGNITIVE_CHANGE as the strategy ")
              .append("to reflect the endpoint of this progression.\n\n");
        }

        if (isSelfConscious && shameContext != null) {
            if (shameContext.isSurfaceLevel()) {
                sb.append("Surface-level shame processing: This personality has high grandiosity (")
                  .append(String.format("%.2f", shameContext.grandiosity()))
                  .append(") and high callousness (")
                  .append(String.format("%.2f", shameContext.callousness()))
                  .append("). Shame does not penetrate deeply — the character deflects, ")
                  .append("externalises blame, or dismisses the self-evaluation. ")
                  .append("Regulation should reflect shallow engagement, not deep processing.\n\n");
            } else {
                if (shameContext.isCompounding()) {
                    sb.append("Shame compounding (Gross & John 2003): ").append(shameContext.episodeCount())
                      .append(" shame episodes in recent history. Each repeated episode compounds ")
                      .append("the self-evaluative weight — prior wounds reopen alongside the current ")
                      .append("one. Serial regulation fatigue: habitual strategies show diminishing ")
                      .append("returns after repeated use on the same emotion. Each attempt at the ")
                      .append("same approach produces weaker emotional control and stronger rebound. ")
                      .append("The character should be shifting toward a DIFFERENT regulation mode ")
                      .append("than their default — the default is failing.\n\n");
                }
                if (shameContext.isRelationallyCharged()) {
                    sb.append("Relationally charged shame: The subject (")
                      .append(shameContext.relationalRole() != null ? shameContext.relationalRole() : "close other")
                      .append(", intimacy=").append(String.format("%.2f", shameContext.relationalCloseness()))
                      .append(") makes this shame relationally loaded. ")
                      .append("Failing in the eyes of someone the character values deeply ")
                      .append("amplifies the self-evaluative weight.\n\n");
                }
            }
        }

        if (pinnedStrategy == null && isSelfConscious
                && (shameContext == null || !shameContext.isSurfaceLevel())) {
            boolean isAvoidant = axes != null && "avoidant".equals(axes.conflictMode());
            if (isAvoidant && shameContext != null && shameContext.isCompounding()) {
                sb.append("Avoidant shame — exhausted withdrawal (Nathanson 1992): ")
                  .append("This avoidant personality's default shame response is withdrawal, ")
                  .append("but after ").append(shameContext.episodeCount())
                  .append(" episodes, momentary withdrawal is no longer sufficient — the character ")
                  .append("keeps stepping back but the shame follows. The avoidance pattern ")
                  .append("must escalate to full SITUATION_SELECTION: avoiding the triggering ")
                  .append("context entirely, restructuring their environment so the shame-producing ")
                  .append("situation cannot recur. Choose SITUATION_SELECTION.\n\n");
            } else if (isAvoidant) {
                sb.append("Avoidant shame response (Nathanson 1992 compass of shame): ")
                  .append("The avoidant personality's natural response to shame is full withdrawal ")
                  .append("from the triggering context — not just redirecting attention, but ")
                  .append("removing themselves from the situation that exposed their failure. ")
                  .append("Choose SITUATION_SELECTION. The character steps away from the context ")
                  .append("that produced the shame, reorganizing their environment to avoid ")
                  .append("re-exposure. This is the avoidant personality's instinctive and ")
                  .append("most executable strategy for self-evaluative emotions.\n\n");
            } else {
                sb.append("Self-conscious emotion guidance: Shame and remorse are self-evaluative — ")
                  .append("the emotion IS about the self's failure against its own standards. ")
                  .append("Pure suppression leaves the self-evaluation unaddressed (Webb et al. 2012). ")
                  .append("Temporal containment is adaptive: ride out the emotional wave with ")
                  .append("self-soothing or attentional strategies, not forced cognitive restructuring. ")
                  .append("This is staged regulation where containment enables later reappraisal.\n\n");
            }
        }

        if (situationContext != null && !situationContext.isBlank()) {
            sb.append("Situation: ").append(situationContext).append("\n\n");
        }

        sb.append("Current emotions (post-appraisal):\n");
        for (var emotion : appraisal.emotions()) {
            sb.append("- ").append(emotion.type().name())
              .append(": intensity=").append(String.format("%.2f", emotion.intensity()))
              .append(", subject=").append(emotion.subjectId())
              .append("\n");
        }

        if (!history.isEmpty()) {
            sb.append("\nRecent regulation history:\n");
            for (var prev : history) {
                sb.append("- ").append(prev.strategy().name())
                  .append(" on ").append(String.join(", ", prev.targetEmotions()))
                  .append(": effectiveness=").append(String.format("%.2f", prev.effectiveness()))
                  .append("\n");
            }
        }

        return sb.toString();
    }


    private static String capacityLabel(double capacity) {
        if (capacity >= 0.75) return "strong";
        if (capacity >= 0.5) return "moderate";
        if (capacity >= 0.3) return "low";
        return "minimal";
    }

    private static @Nullable EmotionRegulationResult parseResponse(String response) throws Exception {
        var jsonStart = response.indexOf('{');
        var jsonEnd = response.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null;

        var json = response.substring(jsonStart, jsonEnd + 1);
        var root = MAPPER.readTree(json);

        var strategyName = root.path("strategy").asText(null);
        if (strategyName == null) return null;

        EmotionRegulationStrategy strategy;
        try {
            strategy = EmotionRegulationStrategy.valueOf(strategyName);
        } catch (IllegalArgumentException e) {
            return null;
        }

        var targetEmotions = extractStringList(root, "targetEmotions");
        var targetDrives = extractStringList(root, "targetDrives");
        double peakOriginalIntensity = root.path("peakOriginalIntensity").asDouble(0.0);
        double regulatedIntensity = root.path("regulatedIntensity").asDouble(0.0);
        double effectiveness = root.path("effectiveness").asDouble(0.0);
        String narrative = root.path("narrative").asText("");

        DriveAxis regulationAxis = null;
        String axisName = root.path("regulationAxis").asText(null);
        if (axisName != null && !axisName.equals("null")) {
            try {
                regulationAxis = DriveAxis.valueOf(axisName);
            } catch (IllegalArgumentException ignored) {}
        }

        String environmentalDirective = root.path("environmentalDirective").asText(null);
        if ("null".equals(environmentalDirective)) environmentalDirective = null;

        String targetAgentId = root.path("targetAgentId").asText(null);
        if ("null".equals(targetAgentId)) targetAgentId = null;

        return new EmotionRegulationResult(
                strategy, targetEmotions, targetDrives,
                peakOriginalIntensity, regulatedIntensity, effectiveness,
                narrative, regulationAxis, environmentalDirective, targetAgentId);
    }

    private static List<String> extractStringList(JsonNode root, String field) {
        var node = root.path(field);
        if (!node.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (JsonNode item : node) {
            if (item.isTextual()) result.add(item.asText());
        }
        return List.copyOf(result);
    }
}
