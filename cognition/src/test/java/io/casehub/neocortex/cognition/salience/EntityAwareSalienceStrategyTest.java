package io.casehub.neocortex.cognition.salience;

import io.casehub.neocortex.cognition.appraisal.PerceivedSituation;
import io.casehub.neocortex.cognition.appraisal.SalienceContext;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;

class EntityAwareSalienceStrategyTest {

    private ConcernBasedSalienceStrategy delegate;

    @BeforeEach
    void setUp() {
        delegate = new ConcernBasedSalienceStrategy();
    }

    private Memory concern(String memoryId, String text) {
        return new Memory(memoryId, Subject.of("agent", "a1"),
                          new MemoryDomain("experience"), "t1", null, text,
                          Map.of(), Instant.now(), null, null, null, null, null, null);
    }

    @Test
    void boostsSalienceWhenEntityNameFoundInObservation() {
        Memory c1 = concern("mem-1", "{{node-alice:full:Alice}} discussed photography");

        var strategy = new EntityAwareSalienceStrategy(delegate);
        var context  = new SalienceContext("Alice walked into the room.", List.of(), null, List.of(c1), List.of(), "t1");

        PerceivedSituation result = strategy.perceive(context);

        assertThat(result.salience()).containsKey("mem-1");
        assertThat(result.salience().get("mem-1")).isGreaterThan(0.0);
    }

    @Test
    void noBoostWhenNoEntityMatch() {
        Memory c1 = concern("mem-1", "{{node-alice:full:Alice}} discussed photography");

        var strategy = new EntityAwareSalienceStrategy(delegate);
        var context  = new SalienceContext("The weather was pleasant.", List.of(), null, List.of(c1), List.of(), "t1");

        PerceivedSituation result = strategy.perceive(context);

        assertThat(result.salience().getOrDefault("mem-1", 0.0)).isEqualTo(0.0);
    }

    @Test
    void addsToDelegateSalience() {
        Memory c1 = concern("mem-1", "{{node-frida:full:Frida}} exhibited photography art.");

        var strategy = new EntityAwareSalienceStrategy(delegate);
        var context  = new SalienceContext("Frida exhibited photography art.", List.of(), null, List.of(c1), List.of(), "t1");

        PerceivedSituation result = strategy.perceive(context);

        assertThat(result.salience().get("mem-1")).isGreaterThan(0.2);
    }

    @Test
    void caseInsensitiveMatch() {
        Memory c1 = concern("mem-1", "{{node-james:full:James}} was in the garden");

        var strategy = new EntityAwareSalienceStrategy(delegate);
        var context  = new SalienceContext("james was in the garden.", List.of(), null, List.of(c1), List.of(), "t1");

        PerceivedSituation result = strategy.perceive(context);

        assertThat(result.salience()).containsKey("mem-1");
    }

    @Test
    void noTokensNoBoost() {
        Memory c1 = concern("mem-1", "plain text about photography");

        var strategy = new EntityAwareSalienceStrategy(delegate);
        var context  = new SalienceContext("Alice walked in.", List.of(), null, List.of(c1), List.of(), "t1");

        PerceivedSituation result = strategy.perceive(context);

        assertThat(result.salience().getOrDefault("mem-1", 0.0)).isEqualTo(0.0);
    }
}
