package io.casehub.neocortex.cognition.narrative;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class NarrativeOrchestrator {

    private final NarrativeMemory store;
    private final ConcurrentHashMap<String, NarrativeState> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.time.Instant> lastTickTimes = new ConcurrentHashMap<>();

    public NarrativeOrchestrator(NarrativeMemory store) {
        this.store = store;
    }

    public NarrativeTick tick(String agentId, String tenantId) {
        var key = agentId + ":" + tenantId;
        lastTickTimes.put(key, java.time.Instant.now());
        return withLock(key, () -> {
            var loaded = store.load(agentId, tenantId);
            var previous = cache.get(key);

            if (loaded == null) {
                return new NarrativeTick.NoChange("no narrative in store");
            }

            cache.put(key, loaded);

            if (previous == null) {
                return new NarrativeTick.Updated(null, loaded,
                        loaded.episodes().stream().map(IndividualEpisode::id).toList(),
                        loaded.themes().stream().map(DerivedTheme::label).toList());
            }

            if (loaded.synthesisedAt().equals(previous.synthesisedAt())) {
                return new NarrativeTick.NoChange("no new synthesis");
            }

            var newEpisodeIds = loaded.episodes().stream()
                    .map(IndividualEpisode::id)
                    .filter(id -> previous.episodes().stream()
                            .noneMatch(e -> e.id().equals(id)))
                    .toList();
            var newThemeLabels = loaded.themes().stream()
                    .map(DerivedTheme::label)
                    .filter(label -> previous.themes().stream()
                            .noneMatch(t -> t.label().equalsIgnoreCase(label)))
                    .toList();

            return new NarrativeTick.Updated(previous, loaded,
                    newEpisodeIds, newThemeLabels);
        });
    }

    public Optional<NarrativeState> currentNarrative(String agentId, String tenantId) {
        return Optional.ofNullable(cache.get(agentId + ":" + tenantId));
    }

    public void evictIdle(java.time.Duration maxIdle) {
        var cutoff = java.time.Instant.now().minus(maxIdle);
        lastTickTimes.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                var key = e.getKey();
                cache.remove(key);
                tickLocks.remove(key);
                return true;
            }
            return false;
        });
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
}
