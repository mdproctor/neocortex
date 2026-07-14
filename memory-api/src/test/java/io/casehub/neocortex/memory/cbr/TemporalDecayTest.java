package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TemporalDecayTest {

    @Test void halfLife_rejectsNull() {
        assertThatThrownBy(() -> new TemporalDecay.HalfLife(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void halfLife_rejectsZero() {
        assertThatThrownBy(() -> new TemporalDecay.HalfLife(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void halfLife_rejectsNegative() {
        assertThatThrownBy(() -> new TemporalDecay.HalfLife(Duration.ofDays(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void halfLife_noAge_factorIsOne() {
        var decay = new TemporalDecay.HalfLife(Duration.ofDays(30));
        Instant now = Instant.now();
        assertThat(decay.factor(now, now)).isEqualTo(1.0);
    }

    @Test void halfLife_exactlyOneHalfLife_factorIsHalf() {
        var decay = new TemporalDecay.HalfLife(Duration.ofDays(30));
        Instant now = Instant.now();
        Instant thirtyDaysAgo = now.minus(Duration.ofDays(30));
        assertThat(decay.factor(thirtyDaysAgo, now)).isCloseTo(0.5, within(0.001));
    }

    @Test void halfLife_twoHalfLives_factorIsQuarter() {
        var decay = new TemporalDecay.HalfLife(Duration.ofDays(30));
        Instant now = Instant.now();
        Instant sixtyDaysAgo = now.minus(Duration.ofDays(60));
        assertThat(decay.factor(sixtyDaysAgo, now)).isCloseTo(0.25, within(0.001));
    }

    @Test void halfLife_futureStoredAt_factorIsOne() {
        var decay = new TemporalDecay.HalfLife(Duration.ofDays(30));
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofDays(1));
        assertThat(decay.factor(future, now)).isEqualTo(1.0);
    }

    @Test void halfLife_shortHalfLife_decaysFaster() {
        var fast = new TemporalDecay.HalfLife(Duration.ofHours(1));
        var slow = new TemporalDecay.HalfLife(Duration.ofDays(30));
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        assertThat(fast.factor(oneHourAgo, now)).isLessThan(slow.factor(oneHourAgo, now));
    }
}
