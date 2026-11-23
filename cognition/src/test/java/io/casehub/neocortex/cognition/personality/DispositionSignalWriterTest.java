package io.casehub.neocortex.cognition.personality;

import io.casehub.eidos.api.DispositionSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;

class DispositionSignalWriterTest {

    private InMemorySignalStore signalStore;
    private DispositionSignalWriter writer;

    @BeforeEach
    void setUp() {
        signalStore = new InMemorySignalStore();
        writer = new DispositionSignalWriter(signalStore, new DirectIsolatedTransactions());
    }

    @Test
    void eightThreadsHundredActivationsExactlyEightHundred() throws Exception {
        int threads = 8;
        int activationsPerThread = 100;
        var latch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int j = 0; j < activationsPerThread; j++) {
                    writer.enqueue("t1", "agent1", List.of("ti"));
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        writer.drain("t1", "agent1");

        var counts = signalStore.activationWeights("agent1", "t1");
        assertThat(counts.getOrDefault("ti", 0.0)).isEqualTo(800.0);
    }

    @Test
    void drainFlushesAllPendingActivations() {
        writer.enqueue("t1", "agent1", List.of("ti", "ne", "ti"));
        writer.drain("t1", "agent1");

        var counts = signalStore.activationWeights("agent1", "t1");
        assertThat(counts.getOrDefault("ti", 0.0)).isGreaterThanOrEqualTo(2);
        assertThat(counts.getOrDefault("ne", 0.0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void clearDiscardsPendingAndClearsStore() {
        writer.enqueue("t1", "agent1", List.of("ti", "ne"));
        writer.drain("t1", "agent1");
        assertThat(signalStore.activationWeights("agent1", "t1")).isNotEmpty();

        writer.enqueue("t1", "agent1", List.of("ti"));
        writer.clear("t1", "agent1");

        assertThat(signalStore.activationWeights("agent1", "t1")).isEmpty();
    }

    @Test
    void decayDrainsThenDecays() {
        writer.enqueue("t1", "agent1", List.of("ti", "ti", "ti", "ti", "ti"));
        writer.drain("t1", "agent1");
        assertThat(signalStore.activationWeights("agent1", "t1").get("ti")).isEqualTo(5.0);

        writer.decay("t1", "agent1", 0.5);
        assertThat(signalStore.activationWeights("agent1", "t1").get("ti")).isEqualTo(2.0);
    }

    @Test
    void storeOutageRetainsActivationsInBuffer() {
        signalStore.failOnNext = true;
        try {
            writer.enqueue("t1", "agent1", List.of("ti"));
            writer.drain("t1", "agent1");
        } catch (RuntimeException ignored) {}

        signalStore.failOnNext = false;
        writer.drain("t1", "agent1");
        assertThat(signalStore.activationWeights("agent1", "t1").getOrDefault("ti", 0.0))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void flushAllDrainsEveryAgent() {
        writer.enqueue("t1", "agent1", List.of("ti"));
        writer.enqueue("t1", "agent2", List.of("ne"));
        writer.flushAll();

        assertThat(signalStore.activationWeights("agent1", "t1").getOrDefault("ti", 0.0))
                .isGreaterThanOrEqualTo(1);
        assertThat(signalStore.activationWeights("agent2", "t1").getOrDefault("ne", 0.0))
                .isGreaterThanOrEqualTo(1);
    }

    static class InMemorySignalStore implements DispositionSignalStore {
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> counts = new ConcurrentHashMap<>();
        volatile boolean failOnNext = false;

        @Override
        public void recordActivation(String agentId, String tenancyId, String functionTerm) {
            if (failOnNext) throw new RuntimeException("store outage");
            counts.computeIfAbsent(key(agentId, tenancyId), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(functionTerm, k -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public Map<String, Double> activationWeights(String agentId, String tenancyId) {
            var agent = counts.get(key(agentId, tenancyId));
            if (agent == null) {return Map.of();}
            var result = new LinkedHashMap<String, Double>();
            agent.forEach((k, v) -> {if (v.get() > 0) {result.put(k, (double) v.get());}});
            return result;
        }

        @Override
        public void decay(String agentId, String tenancyId, double decayFactor) {
            var agent = counts.get(key(agentId, tenancyId));
            if (agent == null) return;
            agent.forEach((k, v) -> v.set((int)(v.get() * decayFactor)));
        }

        @Override
        public void clear(String agentId, String tenancyId) {
            counts.remove(key(agentId, tenancyId));
        }

        private String key(String agentId, String tenancyId) { return agentId + ":" + tenancyId; }
    }
}
