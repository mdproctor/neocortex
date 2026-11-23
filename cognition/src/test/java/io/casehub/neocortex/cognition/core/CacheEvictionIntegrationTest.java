package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognition.drive.AffiliationDrive;
import io.casehub.neocortex.cognition.drive.AutonomyDrive;
import io.casehub.neocortex.cognition.drive.CompetenceDrive;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.memory.MemoryHygieneOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.memory.KnowledgeGapSummary;

class CacheEvictionIntegrationTest {

    private static class TestClock extends Clock {
        private Instant instant;
        TestClock(Instant start) { this.instant = start; }
        void advance(Duration d) { instant = instant.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static CognitionCore createMinimalCore() {
        var mood = new MoodOrchestrator(
                MoodConfig.defaults());
        var noOpHygiene = new MemoryHygieneOrchestrator() {
            @Override public void tick(String a, String t) {}
            @Override public KnowledgeGapSummary knowledgeGaps(String a, String t) {
                return KnowledgeGapSummary.empty();
            }
        };
        var drives = new DriveOrchestrator(
                new CuriosityDrive(null, null, 0.0),
                new CompetenceDrive(null),
                new AffiliationDrive(null, 0.3, Duration.ofHours(1)),
                new AutonomyDrive(null, 0.5),
                mood,
                new DriveComposer(),
                DriveConfig.defaults());
        return new CognitionCore(mood, drives, null, null, null, null, null, noOpHygiene);
    }

    @Test
    void schedulerInvokedDuringTick() {
        var core = createMinimalCore();
        var clock = new TestClock(Instant.now());
        var scheduler = new CacheEvictionScheduler(3, Duration.ofSeconds(1), Duration.ofMinutes(10), clock);
        var evicted = new AtomicInteger();
        scheduler.register(maxIdle -> evicted.incrementAndGet());
        core.configureCacheEviction(scheduler);

        clock.advance(Duration.ofSeconds(2));
        for (int i = 0; i < 3; i++) {
            core.tick("a1", "t1", Set.of());
        }
        assertThat(evicted.get()).isEqualTo(1);
    }

    @Test
    void effectiveDefaultsAutoRegisteredWithScheduler() {
        var core = createMinimalCore();
        var clock = new TestClock(Instant.now());
        var scheduler = new CacheEvictionScheduler(3, Duration.ofSeconds(1), Duration.ofMinutes(10), clock);
        core.configureCacheEviction(scheduler);

        var defaults = EffectiveCognitiveDefaults.forTesting();
        defaults.forAgent("t1", "a1");
        core.setEffectiveDefaults(defaults);

        clock.advance(Duration.ofSeconds(2));
        for (int i = 0; i < 3; i++) {
            core.tick("a1", "t1", Set.of());
        }
    }

    @Test
    void noEvictionWhenSchedulerNotConfigured() {
        var core = createMinimalCore();
        for (int i = 0; i < 100; i++) {
            core.tick("a1", "t1", Set.of());
        }
    }
}
