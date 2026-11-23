package io.casehub.neocortex.cognition.personality;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.memory.InMemoryDispositionSignalStore;
import io.casehub.eidos.runtime.health.DefaultDispositionEvolution;
import io.casehub.eidos.runtime.health.DefaultDispositionHealth;
import io.casehub.neocortex.cognition.journal.CognitiveEntry;
import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognition.situational.PersonalityStateService;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.LearningProfile;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.index.WeightedTerm;
import java.util.Comparator;

/**
 * 240-day trajectory tests (spec test group 14) exercising real Eidos SPIs
 * ({@link DefaultDispositionHealth}, {@link DefaultDispositionEvolution}) against
 * the {@link DispositionEvolutionPhase} consolidation loop.
 *
 * <p>Reference profile: Ti 0.35, Ne 0.20, six others at 0.075 (sum 1.0).
 * Default JPAF: δ=0.06, retention=0.8/period, swapMargin=0.03,
 * minTransitionInterval=30d, reflectionPeriod=24h.
 */
class DispositionTrajectoryTest {

    private static final String TENANT = "t1";
    private static final String AGENT = "agent-traj";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);
    private static final int TRAJECTORY_DAYS = 240;
    private static final Set<String> FACET_NAMES = Set.of("Anxiousness", "Emotional Lability");

    private static final List<DispositionValue> REFERENCE_PROFILE = List.of(
            new DispositionValue("Ti", 0.35),
            new DispositionValue("Ne", 0.20),
            new DispositionValue("Fi", 0.075),
            new DispositionValue("Se", 0.075),
            new DispositionValue("Fe", 0.075),
            new DispositionValue("Ni", 0.075),
            new DispositionValue("Te", 0.075),
            new DispositionValue("Si", 0.075));

    private DispositionSignalStore signalStore;
    private InMemoryMindMapStore mindMapStore;
    private PersonalityStateService stateService;
    private DispositionSignalWriter signalWriter;
    private AgentDescriptor descriptor;

    @BeforeEach
    void setUp() {
        signalStore = new InMemoryDispositionSignalStore();
        mindMapStore = new InMemoryMindMapStore();
        stateService = new PersonalityStateService(mindMapStore, FACET_NAMES);

        descriptor = AgentDescriptor.builder()
                .agentId(AGENT).name("Trajectory").slot("test").tenancyId(TENANT)
                .disposition(AgentDisposition.builder()
                        .socialOrient("cooperative")
                        .dispositionProfile(REFERENCE_PROFILE)
                        .build())
                .build();
    }

    // ── Scenario 1: Pure NEGATIVE 5/period ──────────────────────────

    @ParameterizedTest(name = "pureNegative_5perPeriod_rc={0}")
    @ValueSource(doubles = {1.0, 0.8, 0.5})
    void pureNegative_5perPeriod_oneTransitionNoReturn(double rc) {
        var result = simulate(rc, day -> {
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ne");
            }
        });

        assertThat(result.transitions).as("exactly one transition")
                .isEqualTo(1);
        assertThat(result.lastTypeLabel).as("swapped to Ne-dominant")
                .isEqualTo("Ne");
    }

    // ── Scenario 2: Pure POSITIVE 3/period ──────────────────────────

    @ParameterizedTest(name = "purePositive_3perPeriod_rc={0}")
    @ValueSource(doubles = {1.0, 0.8, 0.5})
    void purePositive_3perPeriod_zeroTransitions(double rc) {
        var result = simulate(rc, day -> {
            for (int i = 0; i < 3; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ti");
            }
        });

        assertThat(result.transitions).as("no transitions when reinforcing dominant")
                .isEqualTo(0);
    }

    // ── Scenario 3: Mixed 3+5 ───────────────────────────────────────

    @Test
    void mixed_3neg5pos_swapWithin2Periods_thenStable() {
        var result = simulate(1.0, day -> {
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ne");
            }
            for (int i = 0; i < 3; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ti");
            }
        });

        assertThat(result.transitions).as("swap within trajectory, then stable")
                .isEqualTo(1);
    }

    // ── Scenario 4: Positive after adaptation ───────────────────────

    @ParameterizedTest(name = "positiveAfterAdaptation_rc={0}")
    @ValueSource(doubles = {1.0, 0.8, 0.5})
    void positiveAfterAdaptation_returnOnce(double rc) {
        int switchDay = -1;
        var journal = new TrajectoryJournal();
        var phase = buildPhase(rc, journal);

        var now = START;
        for (int day = 0; day < TRAJECTORY_DAYS; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);

            if (switchDay < 0) {
                for (int i = 0; i < 5; i++) {
                    signalStore.recordActivation(AGENT, TENANT, "Ne");
                }
            } else {
                for (int i = 0; i < 5; i++) {
                    signalStore.recordActivation(AGENT, TENANT, "Ti");
                }
            }

            phase.run(TENANT, List.of());

            if (switchDay < 0 && journal.transitions > 0) {
                switchDay = day;
                signalStore.clear(AGENT, TENANT);
            }
        }

        assertThat(journal.transitions).as("one forward swap + one return")
                .isEqualTo(2);
        var finalState = stateService.read(TENANT, AGENT, now);
        assertThat(finalState.reflection().reversalStreak()).as("reversal streak=1 (A→B→A)")
                .isEqualTo(1);
    }

    // ── Scenario 5: Dead zone 5+5 ──────────────────────────────────

    @ParameterizedTest(name = "deadZone_5plus5_rc={0}")
    @ValueSource(doubles = {1.0, 0.8, 0.5})
    void deadZone_5plus5_transitionsSpaced(double rc) {
        var journal = new TrajectoryJournal();
        var phase = buildPhase(rc, journal);

        var interval = Duration.ofMillis((long) (DispositionEvolutionConfig.defaults()
                .minTransitionInterval().toMillis() / rc));

        var now = START;
        for (int day = 0; day < TRAJECTORY_DAYS; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);

            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ne");
            }
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ti");
            }

            phase.run(TENANT, List.of());
        }

        for (int i = 0; i < journal.transitionTimes.size() - 1; i++) {
            var gap = Duration.between(journal.transitionTimes.get(i),
                    journal.transitionTimes.get(i + 1));
            assertThat(gap).as("transitions spaced >= minTransitionInterval/rc")
                    .isGreaterThanOrEqualTo(interval);
        }
    }

    // ── Scenario 6: Threshold sweep ─────────────────────────────────

    @Test
    void thresholdSweep_alternation() {
        var journal = new TrajectoryJournal();
        var phase = buildPhase(1.0, journal);

        var now = START;
        for (int day = 0; day < TRAJECTORY_DAYS; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);

            int period = day / 40;
            if (period % 2 == 0) {
                for (int i = 0; i < 7; i++) {
                    signalStore.recordActivation(AGENT, TENANT, "Ne");
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    signalStore.recordActivation(AGENT, TENANT, "Ti");
                }
            }

            phase.run(TENANT, List.of());
        }

        assertThat(journal.transitions).as("alternation produces multiple transitions")
                .isGreaterThanOrEqualTo(2);
    }
// ── Group 8: Store-of-record regression ──────────────────────────

    @Test
    void storeOfRecord_freshServiceSeesLivedProfile() {
        var journal = new TrajectoryJournal();
        var phase   = buildPhase(1.0, journal);

        var now = START;
        for (int day = 0; day < 60; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ne");
            }
            phase.run(TENANT, List.of());
        }
        assertThat(journal.transitions).as("at least one transition").isGreaterThanOrEqualTo(1);

        var stateBeforeRestart = stateService.read(TENANT, AGENT, now);
        assertThat(stateBeforeRestart.livedProfile()).as("lived profile exists").isNotNull();

        // Simulate restart: fresh PersonalityStateService over the SAME MindMapStore
        var freshService = new PersonalityStateService(
                mindMapStore, FACET_NAMES);
        var stateAfterRestart = freshService.read(TENANT, AGENT, now);

        assertThat(stateAfterRestart.livedProfile()).as("fresh service sees lived profile")
                                                    .isNotNull();
        assertThat(stateAfterRestart.livedProfile().profile())
                .as("profile contents survive restart")
                .isEqualTo(stateBeforeRestart.livedProfile().profile());
    }

    @Test
    void minTransitionInterval_defersSecondTransition() {
        var journal = new TrajectoryJournal();
        var phase   = buildPhase(1.0, journal);

        // Drive first transition
        var now = START;
        for (int day = 0; day < 60; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ne");
            }
            phase.run(TENANT, List.of());
            if (journal.transitions > 0) {break;}
        }
        assertThat(journal.transitions).isEqualTo(1);
        var firstTransitionDay = now;

        // Immediately switch signals — should be deferred by minTransitionInterval
        signalStore.clear(AGENT, TENANT);
        int deferredCount = 0;
        for (int day = 0; day < 60; day++) {
            now = firstTransitionDay.plus(DAY.multipliedBy(day + 1));
            updateClock(phase, now);
            for (int i = 0; i < 5; i++) {
                signalStore.recordActivation(AGENT, TENANT, "Ti");
            }
            phase.run(TENANT, List.of());
            if (journal.transitions == 1) {deferredCount++;}
        }

        assertThat(journal.transitions).as("second transition eventually happens")
                                       .isEqualTo(2);
        assertThat(deferredCount).as("some periods deferred due to minTransitionInterval")
                                 .isGreaterThan(0);
    }


    // ── Infrastructure ──────────────────────────────────────────────

    private TrajectoryResult simulate(double rc, SignalPattern pattern) {
        var journal = new TrajectoryJournal();
        var phase = buildPhase(rc, journal);

        var now = START;
        for (int day = 0; day < TRAJECTORY_DAYS; day++) {
            now = START.plus(DAY.multipliedBy(day));
            updateClock(phase, now);
            pattern.apply(day);
            phase.run(TENANT, List.of());
        }

        var state = stateService.read(TENANT, AGENT, now);
        String dominant = null;
        if (state.livedProfile() != null && !state.livedProfile().profile().isEmpty()) {
            dominant = state.livedProfile().profile().stream()
                    .max(Comparator.comparingDouble(WeightedTerm::weight))
                    .map(WeightedTerm::term).orElse(null);
        }

        return new TrajectoryResult(journal.transitions, journal.reversals,
                dominant, journal.transitionTimes);
    }

    @SuppressWarnings("unchecked")
    private DispositionEvolutionPhase buildPhase(double rc, TrajectoryJournal journal) {
        var unsatisfied = mock(Instance.class);
        when(unsatisfied.isUnsatisfied()).thenReturn(true);

        DispositionHealth health = new DefaultDispositionHealth(
                signalStore, mock(VocabularyRegistry.class), unsatisfied);
        DispositionEvolution evolution = new DefaultDispositionEvolution(unsatisfied);

        var agentRegistry = mock(AgentRegistry.class);
        when(agentRegistry.findById(AGENT, TENANT)).thenReturn(Optional.of(descriptor));

        var cbrStore = mock(CbrRecordStore.class);

        var effectiveDefaults = mock(EffectiveCognitiveDefaults.class);
        when(effectiveDefaults.agentIds(TENANT)).thenReturn(Set.of(AGENT));
        configureLearningProfileRc(effectiveDefaults, rc);

        signalWriter = new DispositionSignalWriter(
                signalStore, Runnable::run);

        var config = DispositionEvolutionConfig.defaults();

        var phase = new DispositionEvolutionPhase(
                health, evolution, agentRegistry, cbrStore,
                effectiveDefaults, stateService, signalWriter, config,
                Clock.fixed(START, ZoneOffset.UTC));
        phase.setCognitiveJournal(journal);
        return phase;
    }

    private void configureLearningProfileRc(EffectiveCognitiveDefaults effectiveDefaults, double rc) {
        var defaults = CognitiveDefaults.empty(AGENT)
                .withCapacityConfig(new CognitiveCapacityConfig(rc, null, null));
        when(effectiveDefaults.restingFor(eq(TENANT), eq(AGENT))).thenReturn(LearningProfile.from(defaults));
    }

    private void updateClock(DispositionEvolutionPhase phase, Instant now) {
        try {
            var clockField = DispositionEvolutionPhase.class.getDeclaredField("clock");
            clockField.setAccessible(true);
            clockField.set(phase, Clock.fixed(now, ZoneOffset.UTC));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface SignalPattern {
        void apply(int day);
    }

    record TrajectoryResult(int transitions, int reversals,
                            String lastTypeLabel, List<Instant> transitionTimes) {}

    static class TrajectoryJournal implements CognitiveJournal {
        int transitions;
        int reversals;
        final List<Instant> transitionTimes = new ArrayList<>();
        final List<CognitiveEntry> entries = new ArrayList<>();

        @Override
        public void record(CognitiveEntry entry) {
            entries.add(entry);
            if (entry instanceof CognitiveEntry.DispositionReflected r) {
                if ("Evolved".equals(r.outcomeType())) {
                    transitions++;
                    transitionTimes.add(r.timestamp());
                }
            }
            if (entry instanceof CognitiveEntry.DispositionReversal) {
                reversals++;
            }
        }
    }
}
