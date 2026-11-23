package io.casehub.neocortex.cognition.narrative;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;

class NarrativeOutputProcessorTest {

    private final NarrativeConfig config = new NarrativeConfig(
            NarrativeSynthesisGate.defaults(), 3, 2, 0.2, 10,
            "domain", "case-type");

    @Test
    void pruneEpisodes_dropsOldestBeyondMax() {
        var processor = new NarrativeOutputProcessor(config);
        var episodes = IntStream.range(0, 5)
                .mapToObj(i -> (NarrativeFragment) new IndividualEpisode("ep" + i,
                        Instant.ofEpochSecond(i), null, List.of(),
                        "desc" + i, 0.5, List.of()))
                .toList();
        var state = new NarrativeState("a1", "t1",
                NarrativeScope.INDIVIDUAL, episodes, Instant.now(), 5);

        var result = processor.process(List.of(state), null);
        assertThat(result.get(0).episodes()).hasSize(3);
        assertThat(result.get(0).episodes().get(0).id()).isEqualTo("ep2");
        assertThat(result.get(0).episodes().get(2).id()).isEqualTo("ep4");
    }

    @Test
    void pruneThemes_dropsLowestSalienceBeyondMax() {
        var processor = new NarrativeOutputProcessor(config);
        var themes = List.<NarrativeFragment>of(
                theme("t1", 0.9), theme("t2", 0.5), theme("t3", 0.7));
        var state = new NarrativeState("a1", "t1",
                NarrativeScope.INDIVIDUAL, themes, Instant.now(), 0);

        var result = processor.process(List.of(state), null);
        assertThat(result.get(0).themes()).hasSize(2);
        assertThat(result.get(0).themes().stream()
                .map(DerivedTheme::label).toList())
                .containsExactlyInAnyOrder("t1", "t3");
    }

    @Test
    void pruneThemes_removesBelowSalienceFloor() {
        var processor = new NarrativeOutputProcessor(config);
        var themes = List.<NarrativeFragment>of(
                theme("above", 0.5), theme("below", 0.1));
        var state = new NarrativeState("a1", "t1",
                NarrativeScope.INDIVIDUAL, themes, Instant.now(), 0);

        var result = processor.process(List.of(state), null);
        assertThat(result.get(0).themes()).hasSize(1);
        assertThat(result.get(0).themes().get(0).label()).isEqualTo("above");
    }

    @Test
    void preservesGroupEpisodes() {
        var processor = new NarrativeOutputProcessor(config);
        var episode = new IndividualEpisode("ep1", Instant.EPOCH, null,
                List.of(), "desc", 0.5, List.of());
        var groupEpisode = new GroupEpisode("gep1", Instant.EPOCH, Instant.EPOCH,
                List.of(), "group-desc", 0.3, Set.of(), Map.of(), 0.5);
        var fragments = List.<NarrativeFragment>of(episode, groupEpisode);
        var state = new NarrativeState("a1", "t1",
                NarrativeScope.INDIVIDUAL, fragments, Instant.now(), 0);

        var result = processor.process(List.of(state), null);
        assertThat(result.get(0).groupEpisodes()).hasSize(1);
        assertThat(result.get(0).groupEpisodes().get(0).id()).isEqualTo("gep1");
    }

    @Test
    void noPruningNeeded_returnsUnchanged() {
        var processor = new NarrativeOutputProcessor(config);
        var episode = new IndividualEpisode("ep1", Instant.EPOCH, null,
                List.of(), "desc", 0.5, List.of());
        var thm = theme("t1", 0.8);
        var state = new NarrativeState("a1", "t1",
                NarrativeScope.INDIVIDUAL, List.of(episode, thm), Instant.now(), 0);

        var result = processor.process(List.of(state), null);
        assertThat(result.get(0).episodes()).hasSize(1);
        assertThat(result.get(0).themes()).hasSize(1);
    }

    private DerivedTheme theme(String label, double salience) {
        return new DerivedTheme(label, Instant.EPOCH, null, List.of(),
                label, salience, Map.of(), List.of());
    }
}
