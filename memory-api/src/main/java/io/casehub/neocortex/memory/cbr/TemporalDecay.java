package io.casehub.neocortex.memory.cbr;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public sealed interface TemporalDecay {

    double factor(Instant storedAt, Instant now);

    record HalfLife(Duration halfLife) implements TemporalDecay {
        public HalfLife {
            Objects.requireNonNull(halfLife, "halfLife required");
            if (halfLife.isNegative() || halfLife.isZero()) {
                throw new IllegalArgumentException("halfLife must be positive, got " + halfLife);
            }
        }

        @Override
        public double factor(Instant storedAt, Instant now) {
            double ageSeconds = Duration.between(storedAt, now).toSeconds();
            if (ageSeconds <= 0) return 1.0;
            double halfLifeSeconds = halfLife.toSeconds();
            return Math.pow(0.5, ageSeconds / halfLifeSeconds);
        }
    }
}
