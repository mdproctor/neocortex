package io.casehub.neocortex.memory.cbr;

import java.time.Instant;
import java.util.Objects;

public record CbrOutcome(
    Outcome result,
    double successRate,
    String detail,
    Instant observedAt
) {
    public enum Outcome { SUCCESS, PARTIAL, FAILURE }

    public static final double DEFAULT_LEARNING_RATE = 0.2;

    public CbrOutcome {
        Objects.requireNonNull(result, "result must not be null");
        if (successRate < 0.0 || successRate > 1.0)
            throw new IllegalArgumentException("successRate must be in [0,1], got: " + successRate);
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public static CbrOutcome of(double successRate, String detail, Instant observedAt) {
        Outcome result = successRate == 1.0 ? Outcome.SUCCESS
                       : successRate == 0.0 ? Outcome.FAILURE
                       : Outcome.PARTIAL;
        return new CbrOutcome(result, successRate, detail, observedAt);
    }

    public static double adjustConfidence(Double oldConfidence, double successRate,
                                          double learningRate) {
        double old = oldConfidence != null ? oldConfidence : 1.0;
        return (1.0 - learningRate) * old + learningRate * successRate;
    }
}
