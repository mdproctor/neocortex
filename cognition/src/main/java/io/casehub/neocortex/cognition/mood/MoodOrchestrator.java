package io.casehub.neocortex.cognition.mood;

import io.casehub.neocortex.memory.mood.MoodBaseline;
import io.casehub.neocortex.memory.mood.MoodDecay;
import io.casehub.neocortex.memory.mood.MoodState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@ApplicationScoped
public class MoodOrchestrator {

    private final MoodConfig config;
    private final Clock clock;

    private final ConcurrentHashMap<String, AgentMoodState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastTickTimes = new ConcurrentHashMap<>();

    @Inject
    public MoodOrchestrator(MoodConfig config) {
        this(config, Clock.systemUTC());
    }

    MoodOrchestrator(MoodConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public void record(MoodSignal signal, String agentId, String tenantId) {
        var state = states.computeIfAbsent(stateKey(agentId, tenantId),
                k -> new AgentMoodState(agentId, tenantId, config.baseline()));
        state.pendingSignals.addLast(signal);
        state.lastActivityTimestamp = clock.instant();
    }

    public MoodTick tick(String agentId, String tenantId) {
        var key = stateKey(agentId, tenantId);
        lastTickTimes.put(key, Instant.now());
        var state = states.get(key);
        if (state == null) {
            return new MoodTick.NoChange("no mood state");
        }

        return withLock(key, () -> doTick(state));
    }

    public Optional<MoodState> currentMood(String agentId, String tenantId) {
        var state = states.get(stateKey(agentId, tenantId));
        if (state == null) return Optional.empty();
        return Optional.of(state.currentMood);
    }

    private MoodTick doTick(AgentMoodState state) {
        var now = clock.instant();
        var drained = new java.util.ArrayList<MoodSignal>();
        while (!state.pendingSignals.isEmpty()) {
            drained.add(state.pendingSignals.removeFirst());
        }

        if (drained.isEmpty() && state.lastTickTimestamp != null) {
            Duration sinceLast = Duration.between(state.lastTickTimestamp, now);
            if (sinceLast.toMillis() < config.decayTimeConstant().toMillis() / 10) {
                return new MoodTick.NoChange("no signals, decay not significant");
            }
        }

        double p = state.currentMood.pleasure();
        double a = state.currentMood.arousal();
        double d = state.currentMood.dominance();
        String cause = state.currentMood.cause();

        for (var signal : drained) {
            p += signal.pleasureDelta();
            a += signal.arousalDelta();
            d += signal.dominanceDelta();
            if (signal instanceof MoodSignal.DirectShift ds) {
                cause = ds.cause();
            } else if (signal instanceof MoodSignal.InteractionAppraisal ia && ia.cause() != null) {
                cause = ia.cause();
            }
        }

        p = clampAxis(p, config.baseline().pleasure(), config.maxDisplacement());
        a = clampAxis(a, config.baseline().arousal(), config.maxDisplacement());
        d = clampAxis(d, config.baseline().dominance(), config.maxDisplacement());

        var shifted = new MoodState(state.agentId, state.tenantId,
                                    null, p, a, d, cause != null ? cause : "tick",
                                    null, Set.of(), Map.of());

        boolean decayed = false;
        if (state.lastTickTimestamp != null) {
            Duration elapsed = Duration.between(state.lastTickTimestamp, now);
            var decayedMood = MoodDecay.decay(shifted, config.baseline(),
                    elapsed, config.decayTimeConstant());
            if (decayedMood.pleasure() != shifted.pleasure()
                    || decayedMood.arousal() != shifted.arousal()
                    || decayedMood.dominance() != shifted.dominance()) {
                shifted = decayedMood;
                decayed = true;
            }
        }

        state.currentMood = shifted;
        state.lastTickTimestamp = now;

        if (drained.isEmpty() && !decayed) {
            return new MoodTick.NoChange("no signals, no decay");
        }
        return new MoodTick.Updated(shifted, drained.size(), decayed);
    }

    private <T> T withLock(String key, Supplier<T> action) {
        var lock = tickLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static double clampAxis(double value, double baseline, double maxDisplacement) {
        double lower = Math.max(-1.0, baseline - maxDisplacement);
        double upper = Math.min(1.0, baseline + maxDisplacement);
        return Math.max(lower, Math.min(upper, value));
    }

    public void evictIdle(Duration maxIdle) {
        var cutoff = Instant.now().minus(maxIdle);
        lastTickTimes.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                var key = e.getKey();
                states.remove(key);
                tickLocks.remove(key);
                return true;
            }
            return false;
        });
    }

    private static String stateKey(String agentId, String tenantId) {
        return agentId + ":" + tenantId;
    }

    static final class AgentMoodState {
        final String agentId;
        final String tenantId;
        final ArrayDeque<MoodSignal> pendingSignals = new ArrayDeque<>();
        MoodState currentMood;
        Instant lastTickTimestamp;
        Instant lastActivityTimestamp;

        AgentMoodState(String agentId, String tenantId, MoodBaseline baseline) {
            this.agentId = agentId;
            this.tenantId = tenantId;
            this.currentMood = new MoodState(agentId, tenantId,
                                             null, baseline.pleasure(), baseline.arousal(), baseline.dominance(),
                                             "initial", null, Set.of(), Map.of());
        }
    }
}
