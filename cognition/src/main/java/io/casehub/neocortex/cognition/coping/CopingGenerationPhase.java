package io.casehub.neocortex.cognition.coping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.cognition.appraisal.AppraisalContext;
import io.casehub.neocortex.cognition.appraisal.SecDimensions;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognitive.index.CopingDefaults;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CopingGenerationPhase {

    private static final Logger LOG = Logger.getLogger(CopingGenerationPhase.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            You are a character's coping system — the part that decides how to cope with \
            stressful situations by selecting a strategy and reinterpreting the situation.

            The 16 EMA coping strategies (Marsella & Gratch 2009), by mental representation:

            ATTENTION — change what you attend to:
            - SEEK_INFORMATION: Monitor uncertain aspects. Produce AttentionDirective(FOCUS).
            - SUPPRESS_INFORMATION: Stop monitoring threats. Produce AttentionDirective(SUPPRESS).

            BELIEF — change what you believe about the situation:
            - SHIFT_RESPONSIBILITY: Reattribute cause externally. Narrative transformation.
            - WISHFUL_THINKING: Alter perceived likelihood. Narrative transformation.
            - DENIAL: Refuse to acknowledge the stressor. Narrative transformation.

            DESIRE — change what you want or care about:
            - MENTAL_DISENGAGEMENT: Reduce caring about the outcome. Produce DriveModulation (negative delta).
            - POSITIVE_REINTERPRETATION: Find growth or silver lining. Narrative transformation.
            - ACCEPTANCE: Accept the situation, reduce striving. Produce DriveModulation + narrative.

            INTENTION — change what you plan to do:
            - PLANNING: Form a concrete plan. Produce GoalOperation(CREATE).
            - SEEK_INSTRUMENTAL_SUPPORT: Seek practical help. Produce GoalOperation(CREATE).
            - SEEK_EMOTIONAL_SUPPORT: Share feelings for relief.
            - ACTIVE_CONFRONTATION: Directly challenge the source. Produce GoalOperation(CREATE).
            - SUPPRESSION: Control emotional expression while feeling it internally.
            - PROCRASTINATION: Defer the goal. Produce GoalOperation(DEFER).
            - RESIGNATION: Abandon the goal. Produce GoalOperation(DROP).
            - AVOIDANCE: Avoid the stressor. Produce GoalOperation(CREATE avoidance action).

            Strategy selection principles:
            - High controllability (situation can be changed) favors INTENTION strategies
            - Low controllability (situation is fixed) favors BELIEF/DESIRE strategies
            - Personality shapes preference: conscientious → PLANNING, neurotic → AVOIDANCE/DENIAL, \
            open → POSITIVE_REINTERPRETATION, agreeable+extraverted → support-seeking

            Respond with JSON only:
            {
              "strategy": "STRATEGY_NAME",
              "narrative": "The reinterpreted situation from the character's perspective",
              "goalOperations": [
                {"type": "CREATE|DROP|DEFER", "goalName": "...", "goalDescription": "...", \
            "axis": "CURIOSITY|COMPETENCE|AFFILIATION|AUTONOMY", "formationReason": "..."}
              ],
              "driveModulations": [
                {"axis": "CURIOSITY|COMPETENCE|AFFILIATION|AUTONOMY", "intensityDelta": -0.2, "reason": "..."}
              ],
              "attentionDirectives": [
                {"type": "FOCUS|SUPPRESS", "target": "what to attend to or suppress", "reason": "..."}
              ]
            }

            Rules:
            - strategy must be one of the 16 strategy names listed above
            - narrative MUST express the reinterpreted agency level in concrete, direct language — \
            use words like "control", "manage", "choose", "decide", "can", "able" for higher agency, \
            or "trapped", "helpless", "stuck", "powerless", "forced" for lower agency. \
            This is critical: the reinterpretation must shift the language detectably.
            - goalOperations: include only when the strategy produces goal effects (see list above). \
            Empty array otherwise.
            - driveModulations: include only for MENTAL_DISENGAGEMENT and ACCEPTANCE. \
            intensityDelta is negative (reduce) typically -0.1 to -0.3. Empty array otherwise.
            - attentionDirectives: include only for SEEK_INFORMATION and SUPPRESS_INFORMATION. \
            Empty array otherwise.
            - If a pinned strategy is provided, use that strategy. Otherwise select the most \
            appropriate strategy given the situation, personality, and controllability.""";

    private final @Nullable SessionPipeline sessionPipeline;

    public CopingGenerationPhase(@Nullable SessionPipeline sessionPipeline) {
        this.sessionPipeline = sessionPipeline;
    }

    public @Nullable CopingResult generate(AppraisalContext appraisalContext,
                                            CopingState state,
                                            @Nullable CopingStrategy pinnedStrategy) {
        return generate(appraisalContext, state, pinnedStrategy, null);
    }

    public @Nullable CopingResult generate(AppraisalContext appraisalContext,
                                            CopingState state,
                                            @Nullable CopingStrategy pinnedStrategy,
                                            @Nullable CopingDefaults copingDefaults) {
        if (sessionPipeline == null) return null;
        try {
            String response = invokeLlm(appraisalContext, state, pinnedStrategy, copingDefaults);
            if (response == null) return null;
            return parseResponse(response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Coping generation failed", e);
            return null;
        }
    }

    private String invokeLlm(AppraisalContext appraisalContext, CopingState state,
                              @Nullable CopingStrategy pinnedStrategy,
                              @Nullable CopingDefaults copingDefaults) {
        var session = sessionPipeline.openSession(
                "coping-generation-" + appraisalContext.agentId(),
                AgentSessionInit.of(SYSTEM_PROMPT));
        var userMessage = buildUserMessage(appraisalContext, state, pinnedStrategy, copingDefaults);
        var responseText = new StringBuilder();
        session.query(userMessage).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseText::append);
        return responseText.isEmpty() ? null : responseText.toString();
    }

    static String buildUserMessage(AppraisalContext appraisalContext,
                                    CopingState state,
                                    @Nullable CopingStrategy pinnedStrategy,
                                    @Nullable CopingDefaults copingDefaults) {
        var sb = new StringBuilder();

        if (pinnedStrategy != null) {
            sb.append("Pinned strategy: ").append(pinnedStrategy.name())
              .append(" (").append(pinnedStrategy.category().name().toLowerCase())
              .append(" strategy — generate the coping response using this strategy)\n\n");
        }

        sb.append("Situation narrative:\n").append(state.interpretationNarrative()).append("\n\n");

        sb.append("SEC dimensions:\n");
        double controllability = state.secDimensions().getOrDefault(SecDimensions.CONTROLLABILITY, 0.5);
        double adjustability = state.secDimensions().getOrDefault(SecDimensions.ADJUSTABILITY, 0.5);
        sb.append("  controllability: ").append(String.format("%.2f", controllability));
        if (controllability > 0.6) sb.append(" (high — situation can be changed)");
        else if (controllability < 0.3) sb.append(" (low — situation is fixed)");
        sb.append("\n");
        sb.append("  adjustability: ").append(String.format("%.2f", adjustability)).append("\n");
        state.secDimensions().entrySet().stream()
                .filter(e -> !SecDimensions.CONTROLLABILITY.equals(e.getKey())
                        && !SecDimensions.ADJUSTABILITY.equals(e.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                        .append(String.format("%.2f", e.getValue())).append("\n"));
        sb.append("\n");

        sb.append("Current emotions:\n");
        for (var emotion : state.workingEmotions()) {
            sb.append("- ").append(emotion.type().name())
              .append(": intensity=").append(String.format("%.2f", emotion.intensity()))
              .append(", subject=").append(emotion.subjectId())
              .append("\n");
        }
        sb.append("\n");

        if (!appraisalContext.drives().isEmpty()) {
            sb.append("Active drives:\n");
            for (var drive : appraisalContext.drives()) {
                sb.append("- ").append(drive.name())
                  .append(" (").append(drive.category().name().toLowerCase()).append(")")
                  .append(": intensity=").append(String.format("%.2f", drive.intensity()))
                  .append("\n");
            }
            sb.append("\n");
        }

        DispositionAxes axes = appraisalContext.dispositionAxes();
        if (axes != null) {
            sb.append("Personality disposition:\n")
              .append("  conflict-mode: ").append(axes.conflictMode()).append("\n")
              .append("  social-orient: ").append(axes.socialOrient()).append("\n")
              .append("  risk-appetite: ").append(axes.riskAppetite()).append("\n")
              .append("  autonomy: ").append(axes.autonomy()).append("\n")
              .append("  rule-following: ").append(axes.ruleFollowing()).append("\n\n");
        }

        if (copingDefaults != null) {
            sb.append("Coping tendencies (personality-derived, Connor-Smith & Flachsbart 2007):\n")
              .append("  planful: ").append(tendencyLabel(copingDefaults.planfulTendency()))
              .append(" (").append(String.format("%.2f", copingDefaults.planfulTendency())).append(")\n")
              .append("  reinterpretation: ").append(tendencyLabel(copingDefaults.reinterpretationTendency()))
              .append(" (").append(String.format("%.2f", copingDefaults.reinterpretationTendency())).append(")\n")
              .append("  avoidance: ").append(tendencyLabel(copingDefaults.avoidanceTendency()))
              .append(" (").append(String.format("%.2f", copingDefaults.avoidanceTendency())).append(")\n")
              .append("  support-seeking: ").append(tendencyLabel(copingDefaults.supportSeekingTendency()))
              .append(" (").append(String.format("%.2f", copingDefaults.supportSeekingTendency())).append(")\n")
              .append("  acceptance: ").append(tendencyLabel(copingDefaults.acceptanceTendency()))
              .append(" (").append(String.format("%.2f", copingDefaults.acceptanceTendency())).append(")\n\n");
        }

        if (state.iteration() > 0) {
            sb.append("Micro-loop iteration: ").append(state.iteration()).append("\n");
            if (!state.strategiesAttempted().isEmpty()) {
                sb.append("Previously attempted strategies:\n");
                for (var attempt : state.strategiesAttempted()) {
                    sb.append("- iteration ").append(attempt.iteration())
                      .append(": ").append(attempt.strategy().name()).append("\n");
                }
                sb.append("Choose a DIFFERENT strategy — the previous ones did not sufficiently ")
                  .append("reduce emotional intensity.\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    static @Nullable CopingResult parseResponse(String response) throws Exception {
        var jsonStart = response.indexOf('{');
        var jsonEnd = response.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null;

        var json = response.substring(jsonStart, jsonEnd + 1);
        var root = MAPPER.readTree(json);

        var strategyName = root.path("strategy").asText(null);
        if (strategyName == null) return null;

        CopingStrategy strategy;
        try {
            strategy = CopingStrategy.valueOf(strategyName);
        } catch (IllegalArgumentException e) {
            LOG.warning("Unknown coping strategy from LLM: " + strategyName);
            return null;
        }

        var narrative = root.path("narrative").asText(null);
        if (narrative == null || narrative.isBlank()) return null;

        var goalOperations = parseGoalOperations(root.path("goalOperations"));
        var driveModulations = parseDriveModulations(root.path("driveModulations"));
        var attentionDirectives = parseAttentionDirectives(root.path("attentionDirectives"));

        return new CopingResult(strategy, narrative, goalOperations, driveModulations, attentionDirectives);
    }

    private static List<GoalOperation> parseGoalOperations(JsonNode node) {
        if (!node.isArray()) return List.of();
        var ops = new ArrayList<GoalOperation>();
        for (JsonNode item : node) {
            var typeName = item.path("type").asText(null);
            var goalName = item.path("goalName").asText(null);
            var goalDescription = item.path("goalDescription").asText("");
            var axisName = item.path("axis").asText(null);
            var reason = item.path("formationReason").asText("");
            if (typeName == null || goalName == null || axisName == null) continue;
            try {
                var type = GoalOperationType.valueOf(typeName);
                var axis = DriveAxis.valueOf(axisName);
                ops.add(new GoalOperation(type, goalName, goalDescription, axis, reason));
            } catch (IllegalArgumentException ignored) {}
        }
        return List.copyOf(ops);
    }

    private static List<DriveModulation> parseDriveModulations(JsonNode node) {
        if (!node.isArray()) return List.of();
        var mods = new ArrayList<DriveModulation>();
        for (JsonNode item : node) {
            var axisName = item.path("axis").asText(null);
            if (axisName == null) continue;
            try {
                var axis = DriveAxis.valueOf(axisName);
                double delta = item.path("intensityDelta").asDouble(0.0);
                var reason = item.path("reason").asText("");
                mods.add(new DriveModulation(axis, delta, reason));
            } catch (IllegalArgumentException ignored) {}
        }
        return List.copyOf(mods);
    }

    private static List<AttentionDirective> parseAttentionDirectives(JsonNode node) {
        if (!node.isArray()) return List.of();
        var dirs = new ArrayList<AttentionDirective>();
        for (JsonNode item : node) {
            var typeName = item.path("type").asText(null);
            var target = item.path("target").asText(null);
            if (typeName == null || target == null) continue;
            try {
                var type = AttentionDirectiveType.valueOf(typeName);
                var reason = item.path("reason").asText("");
                dirs.add(new AttentionDirective(type, target, reason));
            } catch (IllegalArgumentException ignored) {}
        }
        return List.copyOf(dirs);
    }

    private static String tendencyLabel(double value) {
        if (value >= 0.75) return "strong";
        if (value >= 0.5) return "moderate";
        if (value >= 0.3) return "low";
        return "minimal";
    }
}
