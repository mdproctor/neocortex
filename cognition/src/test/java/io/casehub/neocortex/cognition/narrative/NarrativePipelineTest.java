package io.casehub.neocortex.cognition.narrative;

import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.neocortex.memory.ReflectionEntry;
import io.casehub.neocortex.memory.ReflectionQueryStore;
import io.casehub.neocortex.summarisation.DefaultSummarisationPipelineFactory;
import io.casehub.neocortex.summarisation.LevelEventBus;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Duration;
import org.mockito.Mockito;

class NarrativePipelineTest {

    private static final String VALID_RESPONSE = """
            {
              "newEpisodes": [
                {
                  "description": "Discovered new approach to problem solving",
                  "emotionalValence": 0.6,
                  "thematicTags": ["learning"],
                  "fromReflections": [0]
                }
              ],
              "themes": [
                {
                  "label": "learner",
                  "salience": 0.7,
                  "thematicTags": ["learning"],
                  "axisWeights": { "CURIOSITY": 0.4 }
                }
              ]
            }
            """;

    private static NarrativeContentSummariser stubSummariser(String response) {
        var s = new NarrativeContentSummariser(NarrativeConfig.defaults(), Clock.systemUTC(), StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        return s;
    }

    @Test
    void tick_endToEnd_adapterToSummariserToStore() {
        var reflectionStore = mock(ReflectionQueryStore.class);
        var reflections = List.of(
                new ReflectionEntry("agent-1", "t1", "learned about X",
                        Instant.ofEpochMilli(100), List.of()),
                new ReflectionEntry("agent-1", "t1", "discovered Y",
                        Instant.ofEpochMilli(200), List.of()),
                new ReflectionEntry("agent-1", "t1", "mastered Z",
                        Instant.ofEpochMilli(300), List.of()),
                new ReflectionEntry("agent-1", "t1", "understood W",
                        Instant.ofEpochMilli(400), List.of()),
                new ReflectionEntry("agent-1", "t1", "explored V",
                        Instant.ofEpochMilli(500), List.of()));
        when(reflectionStore.findSince(eq("agent-1"), eq("t1"), any()))
                .thenReturn(reflections);

        var narrativeMemory = mock(NarrativeMemory.class);
        when(narrativeMemory.load(any(), any())).thenReturn(null);

        var summariser = stubSummariser(VALID_RESPONSE);

        var pipelineFactory = new DefaultSummarisationPipelineFactory();

        var pipeline = new NarrativePipeline(
                summariser, NarrativeConfig.defaults(),
                reflectionStore, narrativeMemory,
                pipelineFactory, new LevelEventBus<>());

        pipeline.tick("agent-1", "t1");

        verify(narrativeMemory).store(any(NarrativeState.class));
    }

    @Test
    void tick_insufficientReflections_noStore() {
        var reflectionStore = mock(ReflectionQueryStore.class);
        when(reflectionStore.findSince(eq("agent-1"), eq("t1"), any()))
                .thenReturn(List.of(
                        new ReflectionEntry("agent-1", "t1", "one",
                                Instant.ofEpochMilli(100), List.of())));

        var existingState = new NarrativeState("agent-1", "t1",
                NarrativeScope.INDIVIDUAL, List.of(
                new IndividualEpisode("ep1", Instant.EPOCH, null,
                        List.of(), "existing", 0.5, List.of())),
                Instant.now(), 5);

        var narrativeMemory = mock(NarrativeMemory.class);
        when(narrativeMemory.load(any(), any())).thenReturn(existingState);

        var summariser = stubSummariser(VALID_RESPONSE);

        var pipelineFactory = new DefaultSummarisationPipelineFactory();

        var pipeline = new NarrativePipeline(
                summariser, NarrativeConfig.defaults(),
                reflectionStore, narrativeMemory,
                pipelineFactory, new LevelEventBus<>());

        pipeline.tick("agent-1", "t1");

        verify(narrativeMemory, Mockito.never()).store(any());
    }
}
