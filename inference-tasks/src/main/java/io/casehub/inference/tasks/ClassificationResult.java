package io.casehub.inference.tasks;

import java.util.Map;
import java.util.Objects;

public record ClassificationResult(String label, float confidence, Map<String, Float> scores) {

    public ClassificationResult {
        Objects.requireNonNull(label, "label must not be null");
        if (confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("confidence must be in [0,1]");
        Objects.requireNonNull(scores, "scores must not be null");
        scores = Map.copyOf(scores);
    }
}
