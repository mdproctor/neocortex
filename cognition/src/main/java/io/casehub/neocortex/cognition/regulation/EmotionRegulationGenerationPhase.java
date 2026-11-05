package io.casehub.neocortex.cognition.regulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EmotionRegulationGenerationPhase {

    private static final Logger LOG = Logger.getLogger(EmotionRegulationGenerationPhase.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Gross 1998, Gross 2015, Petter et al. 2025 — strategy descriptions and selection factors
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
              "environmentalDirective": "directive text or null"
            }

            Rules:
            - regulationAxis and environmentalDirective are REQUIRED for SITUATION_SELECTION \
            and SITUATION_MODIFICATION, null for all others
            - regulationAxis must be one of: CURIOSITY, COMPETENCE, AFFILIATION, AUTONOMY
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
                                     @Nullable EmotionRegulationStrategy pinnedStrategy) {
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

        return new EmotionRegulationResult(
                strategy, targetEmotions, targetDrives,
                peakOriginalIntensity, regulatedIntensity, effectiveness,
                narrative, regulationAxis, environmentalDirective);
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
