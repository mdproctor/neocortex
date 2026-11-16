package io.casehub.neocortex.cognition.drive;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.neocortex.memory.mood.MoodState;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public class DriveComposer {

    public DriveProfile compose(Map<DriveAxis, DriveIntensity> rawDrives,
                                @Nullable AgentDisposition disposition,
                                @Nullable MoodState mood,
                                java.util.List<ModulationLayer> modulations,
                                DriveConfig config,
                                String agentId, String tenantId, Instant now) {
        if (rawDrives.isEmpty()) {
            return new DriveProfile(agentId, tenantId, Map.of(), 0.0,
                                    DriveAxis.CURIOSITY, now);
        }

        var modulated = new EnumMap<DriveAxis, DriveIntensity>(DriveAxis.class);

        for (var entry : rawDrives.entrySet()) {
            var axis = entry.getKey();
            var raw  = entry.getValue();

            double totalDelta     = 0.0;
            double appraisalDelta = 0.0;

            if (mood != null) {
                totalDelta += moodDelta(axis, mood, config);
            }
            if (disposition != null) {
                totalDelta += personalityDelta(axis, disposition, config);
            }
            for (var layer : modulations) {
                double delta = layer.modulation().getOrDefault(axis, 0.0) * layer.strength();
                totalDelta += delta;
                if ("appraisal".equals(layer.source())) {appraisalDelta += delta;}
            }

            double intensity = raw.intensity() + Math.tanh(totalDelta);
            intensity = Math.clamp(intensity, config.minIntensity(), config.maxIntensity());

            String trigger = raw.trigger();
            if (Math.abs(appraisalDelta) > 0.2) {
                trigger = appraisalDelta > 0 ? "elevated by emotional response" : "dampened by emotional response";
            }
            modulated.put(axis, new DriveIntensity(axis, intensity, trigger));
        }

        double    composite = computeComposite(modulated, config);
        DriveAxis dominant  = findDominant(modulated);

        return new DriveProfile(agentId, tenantId, modulated, composite, dominant, now);
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
}
