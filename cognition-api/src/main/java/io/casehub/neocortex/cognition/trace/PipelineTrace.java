package io.casehub.neocortex.cognition.trace;

import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.ChannelWeightProfile;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.regulation.EmotionRegulationResult;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record PipelineTrace(
        @Nullable AppraisalResult appraisalResult,
        Map<String, Double> concernSalience,
        Map<String, Double> capsInputActivations,
        @Nullable CapsSettlingSnapshot capsSettling,
        @Nullable EmotionRegulationResult regulationResult,
        @Nullable ChannelWeightProfile channelWeights,
        @Nullable HabituationState habituation,
        List<String> activatedDriveCategories,
        Map<String, Double> driveIntensities,
        Map<String, String> promptSections
) {
    public PipelineTrace {
        driveIntensities = driveIntensities != null ? Map.copyOf(driveIntensities) : Map.of();
    }
    public record CapsSettlingSnapshot(
            Map<String, Double> activations,
            int iterations,
            String convergenceType,
            double saturationRatio
    ) {}
}
