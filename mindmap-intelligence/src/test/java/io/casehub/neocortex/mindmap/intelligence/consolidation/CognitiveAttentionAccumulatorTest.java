package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.CognitiveAttentionRequired;
import io.casehub.neocortex.mindmap.SignalCategory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveAttentionAccumulatorTest {

    @Test
    void signals_accumulate_per_principal() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("agent-a"),
            CognitiveDefaults.empty("agent-b"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.addSignals(List.of(
            new AttentionSignal("agent-a", "t1", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 3.0, "r1"),
            new AttentionSignal("agent-b", "t1", SignalCategory.DECAY_DETECTED,
                "n2", "G2", 2.0, "r2")));

        assertTrue(fired.isEmpty());

        acc.addSignals(List.of(
            new AttentionSignal("agent-a", "t1", SignalCategory.PRIORITY_SHIFT,
                "n3", "G3", 3.0, "r3")));

        assertEquals(1, fired.size());
        assertEquals("agent-a", fired.get(0).briefing().principalId());
    }

    @Test
    void signals_isolatedByTenant_samePrincipal() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
                CognitiveDefaults.empty("agent-1"));
        var acc = new CognitiveAttentionAccumulator(
                registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        // Same principal, tenant-a: significance 3.0 (below 5.0 threshold)
        acc.addSignals(List.of(
                new AttentionSignal("agent-1", "tenant-a", SignalCategory.URGENCY_SPIKE,
                                    "n1", "G1", 3.0, "r1")));
        assertTrue(fired.isEmpty(), "tenant-a alone should not fire");

        // Same principal, tenant-b: significance 3.0 (below 5.0 threshold)
        acc.addSignals(List.of(
                new AttentionSignal("agent-1", "tenant-b", SignalCategory.URGENCY_SPIKE,
                                    "n2", "G2", 3.0, "r2")));
        // Before fix: pooled = 6.0 >= 5.0, fires. After fix: each tenant at 3.0, neither fires.
        assertTrue(fired.isEmpty(), "tenant-b alone should not fire — signals must not pool across tenants");

        // Push tenant-a over threshold
        acc.addSignals(List.of(
                new AttentionSignal("agent-1", "tenant-a", SignalCategory.DECAY_DETECTED,
                                    "n3", "G3", 3.0, "r3")));
        assertEquals(1, fired.size(), "tenant-a should fire at 6.0");
        assertEquals("tenant-a", fired.get(0).briefing().tenantId());
    }


    @Test
    void adaptive_threshold_lowers_with_high_urgency_p75() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.updateUrgencyP75("a", "t", 0.8);
        // adjusted = 5.0 * (1.0 - 0.2) = 4.0
        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 4.5, "r")));
        assertEquals(1, fired.size());
    }

    @Test
    void minimum_interval_suppresses_rapid_pushes() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var fixedClock = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, fixedClock, 5.0, 300);

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 6.0, "r")));
        assertEquals(1, fired.size());

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.DECAY_DETECTED,
                "n2", "G2", 6.0, "r")));
        assertEquals(1, fired.size(), "second push suppressed by interval guard");
    }

    @Test
    void deduplication_keeps_higher_significance() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 2.0, "low")));
        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 4.0, "high"),
            new AttentionSignal("a", "t", SignalCategory.DECAY_DETECTED,
                "n2", "G2", 2.0, "other")));

        assertEquals(1, fired.size());
        var briefing = fired.get(0).briefing();
        assertEquals(2, briefing.signals().size());
        assertEquals(4.0, briefing.signals().get(0).significance());
    }

    @Test
    void null_principal_broadcasts_to_all_registered_agents() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"), CognitiveDefaults.empty("b"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.addSignals(List.of(
            new AttentionSignal(null, "t", SignalCategory.MERGE_CANDIDATE,
                "n1", "Dup entity", 6.0, "r")));
        assertEquals(2, fired.size());
    }

    @Test
    void broadcast_with_mixed_tenants_routes_to_correct_agents() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
                CognitiveDefaults.empty("agent-1"));
        var acc = new CognitiveAttentionAccumulator(
                registry, null, fired::add, Clock.systemUTC(), 1.0, 0);

        acc.addSignals(List.of(
                new AttentionSignal(null, "t1", SignalCategory.MERGE_CANDIDATE,
                                    "n1", "Dup A", 2.0, "r1"),
                new AttentionSignal(null, "t2", SignalCategory.MERGE_CANDIDATE,
                                    "n2", "Dup B", 2.0, "r2")));

        assertEquals(2, fired.size(), "one fire per tenant");
        var tenantIds = fired.stream()
                             .map(e -> e.briefing().tenantId())
                             .sorted().toList();
        assertEquals(List.of("t1", "t2"), tenantIds);
    }


    @Test
    void threshold_floor_prevents_zero_threshold() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.updateUrgencyP75("a", "t", 1.0);
        // adjusted = 5.0 * (1.0 - 0.4) = 3.0 (floor = 5.0 * 0.6 = 3.0)
        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 2.5, "below floor")));
        assertTrue(fired.isEmpty(), "2.5 < 3.0 floor — should not fire");

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.DECAY_DETECTED,
                "n2", "G2", 1.0, "crosses floor")));
        assertEquals(1, fired.size(), "3.5 >= 3.0 floor — should fire");
    }

    @Test
    void signals_sorted_by_significance_descending_in_briefing() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.DECAY_DETECTED,
                "n1", "Low", 1.0, "r"),
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n2", "High", 3.0, "r"),
            new AttentionSignal("a", "t", SignalCategory.PRIORITY_SHIFT,
                "n3", "Mid", 2.0, "r")));
        assertEquals(1, fired.size());
        var signals = fired.get(0).briefing().signals();
        assertEquals(3.0, signals.get(0).significance());
        assertEquals(2.0, signals.get(1).significance());
        assertEquals(1.0, signals.get(2).significance());
    }

    @Test
    void pending_signals_cleared_after_fire() {
        var fired = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(
            CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
            registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.URGENCY_SPIKE,
                "n1", "G1", 6.0, "r")));
        assertEquals(1, fired.size());

        // Below threshold now — pending was cleared
        acc.addSignals(List.of(
            new AttentionSignal("a", "t", SignalCategory.DECAY_DETECTED,
                "n2", "G2", 2.0, "r")));
        assertEquals(1, fired.size(), "below threshold after drain — no second fire");
    }


    @Test
    void affect_recorded_with_large_pad_delta_creates_signal() {
        var fired    = new ArrayList<CognitiveAttentionRequired>();
        var store    = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var registry = EffectiveCognitiveDefaults.forTesting(CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
                registry, store, fired::add, Clock.systemUTC(), 1.0, 0);

        var sg = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("People", "person", null), "t1");
        var nodeId = store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("Test entity", sg)
                                                                         .withProperty("agent-id", "a")
                                                                         .withPleasure(0.8).withArousal(0.2).withDominance(0.5), "t1");

        // First observation primes cache
        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "mem-1"));
        assertTrue(fired.isEmpty(), "first observation primes cache, no signal");

        // Update PAD to significantly different values
        store.updateNode(nodeId, io.casehub.neocortex.mindmap.NodeUpdate.empty()
                                                                        .withPad(-0.5, 0.9, 0.1), "t1");

        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "mem-2"));
        assertEquals(1, fired.size());
        assertEquals(SignalCategory.AFFECT_CHANGE, fired.get(0).briefing().signals().get(0).category());
    }

    @Test
    void affect_recorded_with_small_pad_delta_no_signal() {
        var fired    = new ArrayList<CognitiveAttentionRequired>();
        var store    = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var registry = EffectiveCognitiveDefaults.forTesting(CognitiveDefaults.empty("a"));
        var acc = new CognitiveAttentionAccumulator(
                registry, store, fired::add, Clock.systemUTC(), 5.0, 0);

        var sg = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("People", "person", null), "t1");
        var nodeId = store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("Stable entity", sg)
                                                                         .withProperty("agent-id", "a")
                                                                         .withPleasure(0.5).withArousal(0.3).withDominance(0.4), "t1");

        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "mem-1"));

        // Small PAD change
        store.updateNode(nodeId, io.casehub.neocortex.mindmap.NodeUpdate.empty()
                                                                        .withPad(0.55, 0.35, 0.45), "t1");

        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "mem-2"));
        assertTrue(fired.isEmpty(), "small delta should not create signal");
    }

    @Test
    void experience_recorded_with_goal_metadata_creates_signal() {
        var fired    = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(CognitiveDefaults.empty("agent-x"));
        var acc = new CognitiveAttentionAccumulator(
                registry, null, fired::add, Clock.systemUTC(), 1.0, 0);

        var event = new io.casehub.neocortex.memory.experience.Observation(
                "agent-x", "t1", null, "turn-1",
                Instant.now(), "Made progress on goal", null,
                java.util.Map.of("goal-node-id", "goal-42"), "entity-1");
        acc.onExperienceRecorded(new io.casehub.neocortex.memory.experience.ExperienceRecorded(event, "m1"));

        assertEquals(1, fired.size());
        assertEquals("agent-x", fired.get(0).briefing().principalId());
        assertEquals("goal-42", fired.get(0).briefing().signals().get(0).sourceNodeId());
    }

    @Test
    void experience_recorded_without_goal_metadata_no_signal() {
        var fired    = new ArrayList<CognitiveAttentionRequired>();
        var registry = EffectiveCognitiveDefaults.forTesting(CognitiveDefaults.empty("agent-x"));
        var acc = new CognitiveAttentionAccumulator(
                registry, null, fired::add, Clock.systemUTC(), 5.0, 0);

        var event = new io.casehub.neocortex.memory.experience.Observation(
                "agent-x", "t1", null, "turn-1",
                Instant.now(), "Routine observation", null,
                java.util.Map.of(), "entity-1");
        acc.onExperienceRecorded(new io.casehub.neocortex.memory.experience.ExperienceRecorded(event, "m1"));

        assertTrue(fired.isEmpty(), "no goal metadata — no signal");
    }


    @Test
    void pad_cache_entries_expire_after_interval() {
        var fired    = new ArrayList<CognitiveAttentionRequired>();
        var store    = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var registry = EffectiveCognitiveDefaults.forTesting(CognitiveDefaults.empty("a"));

        // Use a mutable clock to control time
        var baseInstant = Instant.parse("2026-01-01T00:00:00Z");
        var mutableClock = new java.time.Clock() {
            volatile Instant now = baseInstant;

            @Override
            public java.time.ZoneId getZone()                      {return java.time.ZoneOffset.UTC;}

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {return this;}

            @Override
            public Instant instant()                               {return now;}
        };

        var acc = new CognitiveAttentionAccumulator(
                registry, store, fired::add, mutableClock, 1.0, 0, 600);

        var sg = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("People", "person", null), "t1");
        var nodeId = store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("Entity", sg)
                                                                         .withProperty("agent-id", "a")
                                                                         .withPleasure(0.5).withArousal(0.3).withDominance(0.4), "t1");

        // Prime the cache at t=0
        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "m1"));

        // Advance clock past expiry (>600s)
        mutableClock.now = baseInstant.plusSeconds(700);

        // Now change PAD significantly
        store.updateNode(nodeId, io.casehub.neocortex.mindmap.NodeUpdate.empty()
                                                                        .withPad(-0.5, 0.9, 0.1), "t1");

        // This should prime the cache again (expired entry removed), not produce a signal
        acc.onAffectRecorded(new io.casehub.neocortex.memory.mood.AffectRecorded(nodeId, "t1", "m2"));
        assertTrue(fired.isEmpty(), "expired cache entry should be treated as first observation");
    }

}
