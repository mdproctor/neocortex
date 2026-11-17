package io.casehub.neocortex.cognition.drive;

import java.util.Objects;

/**
 * Per-axis breakdown of drive composition: raw source intensity plus each
 * modulation delta, the final composed value, and whether the drive crossed
 * the activation threshold. Makes sub-threshold evaluations visible so
 * downstream consumers can distinguish "evaluated and sub-threshold" from
 * "signal lost."
 */
public record DriveEvaluation(
        DriveAxis axis,
        double rawIntensity,
        double moodDelta,
        double personalityDelta,
        double appraisalDelta,
        double otherModulationDelta,
        double composedIntensity,
        boolean activated,
        String trigger
) {
    public DriveEvaluation {
        Objects.requireNonNull(axis, "axis required");
        Objects.requireNonNull(trigger, "trigger required");
    }

    public DriveEvaluation withActivated(boolean activated) {
        return new DriveEvaluation(axis, rawIntensity, moodDelta, personalityDelta,
                                   appraisalDelta, otherModulationDelta, composedIntensity, activated, trigger);
    }
}
