package io.casehub.neocortex.rag;

import java.time.Duration;
import java.util.Objects;

public record QualityThresholds(
        int minRetrievalsForQualityCheck,
        int minFeedbackForQualityCheck,
        double lowQualityRatio,
        Duration staleWindow) {

    public QualityThresholds {
        if (minRetrievalsForQualityCheck < 1) {
            throw new IllegalArgumentException(
                    "minRetrievalsForQualityCheck must be >= 1, got " + minRetrievalsForQualityCheck);
        }
        if (minFeedbackForQualityCheck < 1) {
            throw new IllegalArgumentException(
                    "minFeedbackForQualityCheck must be >= 1, got " + minFeedbackForQualityCheck);
        }
        if (lowQualityRatio < 0.0 || lowQualityRatio > 1.0) {
            throw new IllegalArgumentException(
                    "lowQualityRatio must be in [0, 1], got " + lowQualityRatio);
        }
        Objects.requireNonNull(staleWindow, "staleWindow must not be null");
    }

    public static QualityThresholds defaults() {
        return new QualityThresholds(3, 3, 0.7, Duration.ofDays(90));
    }
}
