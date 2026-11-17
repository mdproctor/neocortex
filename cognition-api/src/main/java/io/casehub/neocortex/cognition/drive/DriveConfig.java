package io.casehub.neocortex.cognition.drive;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for drive composition and modulation.
 * <p>
 * Controls how raw drive intensities are modulated by mood and
 * personality, and how the composite drive score is computed.
 * The modulation model follows Self-Determination Theory's distinction
 * between intrinsic drives (curiosity, competence, autonomy) and
 * social drives (affiliation).
 * <p>
 * Parameter interactions: moodPleasureModulation is applied at full
 * strength to AFFILIATION and COMPETENCE drives, and at
 * secondaryPleasureModulationFactor strength to CURIOSITY and AUTONOMY.
 * moodArousalModulation applies uniformly to all drives.
 * personalityModulationStrength scales disposition-based adjustments.
 * The axisWeights determine composite drive score relative contributions.
 *
 * @see Deci, E.L. & Ryan, R.M. (2000). The "what" and "why" of goal
 * pursuits: Human needs and the self-determination of behavior.
 * Psychological Inquiry, 11(4), 227-268.
 */
public record DriveConfig(
        Map<DriveAxis, Double> axisWeights,
        double changeThreshold,
        double moodPleasureModulation,
        double moodArousalModulation,
        double personalityModulationStrength,
        double maxIntensity,
        double minIntensity,
        double affiliationDecayThreshold,
        Duration affiliationStaleDuration,
        double autonomyConfidenceFloor,
        double narrativeModulationStrength,
        double subThoughtModulationStrength,

        /**
         * Scaling factor for mood pleasure modulation on drives that are
         * not directly pleasure-linked (CURIOSITY, AUTONOMY).
         *
         * AFFILIATION and COMPETENCE receive the full moodPleasureModulation
         * because positive affect directly facilitates social engagement and
         * perceived competence. Other drives receive a dampened version.
         *
         * Higher = non-social drives are equally affected by mood pleasure
         * (approaching 1.0 means same as social drives); lower = mood
         * pleasure barely affects non-social drives.
         * Related: moodPleasureModulation.
         *
         * Range: [0.0, 1.0]. Default 0.5 — half the pleasure modulation
         * strength, preventing mood from dominating non-social drives.
         */
        double secondaryPleasureModulationFactor,

        /**
         * Exponential smoothing factor for drive deactivation across ticks.
         * Only applies when new intensity is LOWER than previous — upward
         * spikes bypass smoothing (threat activation is instant).
         *
         * smoothed = α * new + (1-α) * previous
         *
         * Higher = faster decay (1.0 = no smoothing, same as before).
         * Lower = slower decay, more momentum.
         *
         * Range: [0.0, 1.0]. Default 0.3 — drives ramp down over ~3 ticks.
         */
        double smoothingAlpha
) {
    public DriveConfig {
        Objects.requireNonNull(axisWeights, "axisWeights required");
        Objects.requireNonNull(affiliationStaleDuration, "affiliationStaleDuration required");
        axisWeights = Map.copyOf(axisWeights);
        if (changeThreshold < 0.0 || changeThreshold > 1.0) {
            throw new IllegalArgumentException("changeThreshold must be in [0.0, 1.0]");
        }
        if (maxIntensity < minIntensity) {throw new IllegalArgumentException("maxIntensity must be >= minIntensity");}
        if (minIntensity < 0.0) {throw new IllegalArgumentException("minIntensity must be >= 0.0");}
        if (maxIntensity > 1.0) {throw new IllegalArgumentException("maxIntensity must be <= 1.0");}
        if (smoothingAlpha < 0.0 || smoothingAlpha > 1.0) {throw new IllegalArgumentException("smoothingAlpha must be in [0.0, 1.0]");}
    }

    public static DriveConfig defaults() {
        return new DriveConfig(
                Map.of(DriveAxis.CURIOSITY, 1.0, DriveAxis.COMPETENCE, 1.0,
                       DriveAxis.AFFILIATION, 1.0, DriveAxis.AUTONOMY, 1.0),
                0.05, 0.3, 0.2, 0.25, 1.0, 0.0,
                0.5, Duration.ofHours(24), 0.6, 0.25, 0.6,
                0.5, 0.3);
    }
}
