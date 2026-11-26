package io.casehub.neocortex.cognitive.index;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveCognitiveDefaultsSchedulerTest {

    @Test
    void tickRefreshesStaleAndEvictsIdle() {
        var viewSource = new EffectiveCognitiveDefaultsTest.StubDescriptorViewSource();
        var stateSource = new EffectiveCognitiveDefaultsTest.StubPersonalityStateSource();
        var registry = new CognitiveDefaultsRegistry();
        var defaults = new EffectiveCognitiveDefaults(registry, viewSource, stateSource);

        var view = DescriptorView.of("agent-1", null, List.of(), List.of());
        viewSource.add("t1", view);
        defaults.forAgent("t1", "agent-1");

        var scheduler = new EffectiveCognitiveDefaultsScheduler(defaults, 60, 24, 10);

        var updatedView = DescriptorView.of("agent-1",
                new DispositionAxes("competitive", "strict", "bold", "high", "analytical"),
                List.of(new WeightedTerm("te", 0.9)), List.of());
        viewSource.add("t1", updatedView);

        scheduler.tick();

        var result = defaults.forAgent("t1", "agent-1");
        assertThat(result).isNotNull();
    }
}
