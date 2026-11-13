package io.casehub.neocortex.cognition.prompt;

import io.casehub.neocortex.cognition.appraisal.ActionTendency;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import org.jspecify.annotations.Nullable;

public class AppraisalPromptSection implements CognitionPromptRenderer {

    private final           AppraisalTickParticipant participant;
    private final @Nullable PromptRenderingConfig    config;

    public AppraisalPromptSection(AppraisalTickParticipant participant) {
        this(participant, null);
    }

    public AppraisalPromptSection(AppraisalTickParticipant participant,
                                  @Nullable PromptRenderingConfig config) {
        this.participant = participant;
        this.config      = config;
    }

    private double actionTendencyMinIntensity() {return config != null ? config.actionTendencyMinIntensity() : 0.3;}

    private double emotionStrongThreshold()     {return config != null ? config.emotionStrongThreshold() : 0.7;}

    private double emotionModerateThreshold()   {return config != null ? config.emotionModerateThreshold() : 0.4;}

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        var regulated = participant.currentRegulatedNarrative(context.agentId(), context.tenantId());
        if (regulated.isPresent()) {
            return regulated.get().strip();
        }
        return participant.currentResult(context.agentId(), context.tenantId())
                          .map(this::renderEvocative)
                          .orElse(null);
    }

    private @Nullable String renderEvocative(AppraisalResult result) {
        if (result.narrative() != null && !result.narrative().isBlank()) {
            return result.narrative().strip();
        }

        var sb = new StringBuilder();

        if (!result.emotions().isEmpty()) {
            sb.append("You feel ");
            var emotions = result.emotions();
            for (int i = 0; i < emotions.size(); i++) {
                if (i > 0) {sb.append(i == emotions.size() - 1 ? " and " : ", ");}
                sb.append(describeEmotion(emotions.get(i)));
            }
            sb.append(".\n");
        }

        result.actionTendencies().stream()
              .filter(t -> t.intensity() > actionTendencyMinIntensity())
              .forEach(t -> sb.append(describeTendency(t)).append("\n"));

        return sb.isEmpty() ? null : sb.toString().strip();
    }

    private String describeEmotion(CognitiveEmotion emotion) {
        var intensity = emotion.intensity() > emotionStrongThreshold() ? "strong "
                                                                       : emotion.intensity() > emotionModerateThreshold() ? "" : "mild ";
        return intensity + emotion.type().name().toLowerCase().replace('_', ' ');
    }

    private static String describeTendency(ActionTendency t) {
        return switch (t.readiness()) {
            case APPROACH -> "You feel drawn toward " + t.target() + ".";
            case AVOIDANCE -> "You want to get away from " + t.target() + ".";
            case ATTENDING -> "Your attention is fixed on " + t.target() + ".";
            case REJECTION -> "You feel repelled by " + t.target() + ".";
            case ANTAGONISM -> "You feel combative toward " + t.target() + ".";
            case INTERRUPTION -> "Your attention wanders — you want something new.";
            case SUBMISSION -> "You feel like yielding to " + t.target() + ".";
            case DOMINANCE -> "You feel like asserting control over " + t.target() + ".";
            case INDIFFERENCE -> "You feel nothing about " + t.target() + ".";
        };
    }
}
