package io.casehub.neocortex.cognition.drive;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.neocortex.memory.mood.MoodState;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class DriveComposer {

    public record CompositionResult(DriveProfile profile, List<DriveEvaluation> evaluations) {}

    public DriveProfile compose(Map<DriveAxis, DriveIntensity> rawDrives,
                                @Nullable AgentDisposition disposition,
                                @Nullable MoodState mood,
                                java.util.List<ModulationLayer> modulations,
                                DriveConfig config,
                                String agentId, String tenantId, Instant now) {
        return composeWithEvaluations(rawDrives, disposition, mood, modulations, config,
                agentId, tenantId, now).profile();
    }

    public CompositionResult composeWithEvaluations(Map<DriveAxis, DriveIntensity> rawDrives,
                                                    @Nullable AgentDisposition disposition,
                                                    @Nullable MoodState mood,
                                                    java.util.List<ModulationLayer> modulations,
                                                    DriveConfig config,
                                                    String agentId, String tenantId, Instant now) {
        if (rawDrives.isEmpty()) {
            return new CompositionResult(
                    new DriveProfile(agentId, tenantId, Map.of(), 0.0, DriveAxis.CURIOSITY, now),
                    List.of());
        }

        var modulated   = new EnumMap<DriveAxis, DriveIntensity>(DriveAxis.class);
        var evaluations = new ArrayList<DriveEvaluation>();

        for (var entry : rawDrives.entrySet()) {
            var axis = entry.getKey();
            var raw  = entry.getValue();

            double moodDelta_       = mood != null ? moodDelta(axis, mood, config) : 0.0;
            double personalityDelta_ = disposition != null ? personalityDelta(axis, disposition, config) : 0.0;
            double appraisalDelta   = 0.0;
            double otherModDelta    = 0.0;

            for (var layer : modulations) {
                double delta = layer.modulation().getOrDefault(axis, 0.0) * layer.strength();
                if ("appraisal".equals(layer.source())) {
                    appraisalDelta += delta;
                } else {
                    otherModDelta += delta;
                }
            }

            double totalDelta = moodDelta_ + personalityDelta_ + appraisalDelta + otherModDelta;
            double intensity = raw.intensity() + Math.tanh(totalDelta);
            intensity = Math.clamp(intensity, config.minIntensity(), config.maxIntensity());

            String trigger = raw.trigger();
            if (Math.abs(appraisalDelta) > 0.2) {
                String emotionName = resolveAppraisalDescription(axis, modulations);
                if (emotionName != null) {
                    trigger = appraisalDelta > 0
                            ? "elevated by " + emotionName
                            : "dampened by " + emotionName;
                } else {
                    trigger = appraisalDelta > 0 ? "elevated by emotional response" : "dampened by emotional response";
                }
            }
            modulated.put(axis, new DriveIntensity(axis, intensity, trigger));

            evaluations.add(new DriveEvaluation(axis, raw.intensity(),
                    moodDelta_, personalityDelta_, appraisalDelta, otherModDelta,
                    intensity, false, trigger));
        }

        double    composite = computeComposite(modulated, config);
        DriveAxis dominant  = findDominant(modulated);
        var profile = new DriveProfile(agentId, tenantId, modulated, composite, dominant, now);

        return new CompositionResult(profile, List.copyOf(evaluations));
    }

    private double moodDelta(DriveAxis axis, MoodState mood, DriveConfig config) {
        double pleasureMod = mood.pleasure() * config.moodPleasureModulation();
        double arousalMod  = mood.arousal() * config.moodArousalModulation();

        double delta = arousalMod;

        if (axis == DriveAxis.AFFILIATION || axis == DriveAxis.COMPETENCE) {
            delta += pleasureMod;
        } else {
            delta += pleasureMod * config.secondaryPleasureModulationFactor();
        }

        if (axis == DriveAxis.AUTONOMY && mood.dominance() < 0) {
            delta += Math.abs(mood.dominance()) * config.moodPleasureModulation();
        }

        return delta;
    }

    private double personalityDelta(DriveAxis axis,
                                    AgentDisposition disposition,
                                    DriveConfig config) {
        var matchedAxis = switch (axis) {
            case CURIOSITY -> DispositionAxis.RISK_APPETITE;
            case COMPETENCE -> DispositionAxis.RULE_FOLLOWING;
            case AFFILIATION -> DispositionAxis.SOCIAL_ORIENTATION;
            case AUTONOMY -> DispositionAxis.AUTONOMY;
        };

        var values = disposition.get(matchedAxis);
        if (values.isEmpty()) {return 0.0;}

        double weight = values.getFirst().weight();
        return weight * config.personalityModulationStrength();
    }

    private double computeComposite(Map<DriveAxis, DriveIntensity> drives, DriveConfig config) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (var entry : drives.entrySet()) {
            double w = config.axisWeights().getOrDefault(entry.getKey(), 1.0);
            weightedSum += w * entry.getValue().intensity();
            totalWeight += w;
        }
        return totalWeight > 0 ? Math.clamp(weightedSum / totalWeight, 0.0, 1.0) : 0.0;
    }

    private DriveAxis findDominant(Map<DriveAxis, DriveIntensity> drives) {
        return drives.entrySet().stream()
                .max(Map.Entry.comparingByValue(
                        (a, b) -> Double.compare(a.intensity(), b.intensity())))
                .map(Map.Entry::getKey)
                .orElse(DriveAxis.CURIOSITY);
    }

    private static @Nullable String resolveAppraisalDescription(DriveAxis axis, List<ModulationLayer> modulations) {
        for (var layer : modulations) {
            if ("appraisal".equals(layer.source()) && layer.descriptions() != null) {
                return layer.descriptions().get(axis);
            }
        }
        return null;
    }

}
