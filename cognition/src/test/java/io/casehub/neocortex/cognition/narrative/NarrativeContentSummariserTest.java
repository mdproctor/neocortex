package io.casehub.neocortex.cognition.narrative;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.ReflectionEntry;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.Set;

class NarrativeContentSummariserTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private NarrativeContentSummariser summariser;
    private String llmResponse;
    private int invocationCount;
    private String lastUserPrompt;
    private boolean shouldThrow;

    private static final String VALID_RESPONSE = """
            {
              "newEpisodes": [
                {
                  "description": "Helped team through production outage",
                  "emotionalValence": 0.7,
                  "thematicTags": ["crisis", "teamwork"],
                  "fromReflections": [0]
                }
              ],
              "themes": [
                {
                  "label": "crisis-helper",
                  "salience": 0.8,
                  "thematicTags": ["crisis"],
                  "axisWeights": {
                    "AFFILIATION": 0.5,
                    "COMPETENCE": 0.3
                  }
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        llmResponse = VALID_RESPONSE;
        invocationCount = 0;
        lastUserPrompt = null;
        shouldThrow = false;
        summariser = buildSummariser(NarrativeConfig.defaults());
    }

    private NarrativeContentSummariser buildSummariser(NarrativeConfig config) {
        var s = new NarrativeContentSummariser(config, CLOCK, StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                invocationCount++;
                lastUserPrompt = prompt;
                if (shouldThrow) return Multi.createFrom().failure(new RuntimeException("LLM unavailable"));
                return Multi.createFrom().item(new AgentEvent.TextDelta(llmResponse));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        return s;
    }

    private ReflectionEntry reflection(String insight) {
        return new ReflectionEntry("agent-1", "tenant-1", insight,
                EARLIER, List.of("case-1"));
    }

    private NarrativeState existingState(List<NarrativeFragment> fragments) {
        return new NarrativeState("agent-1", "tenant-1",
                NarrativeScope.INDIVIDUAL, fragments,
                Instant.parse("2026-09-12T09:00:00Z"), 5);
    }

    @Test
    void summarise_firstSynthesis_noExistingState() {
        var items = List.of(reflection("resolved outage"));
        var result = summariser.summarise(items, null).toCompletableFuture().join();

        assertThat(result).isNotNull();
        assertThat(result.episodes()).hasSize(1);
        assertThat(result.episodes().get(0).description())
                .isEqualTo("Helped team through production outage");
        assertThat(result.themes()).hasSize(1);
        assertThat(result.themes().get(0).label()).isEqualTo("crisis-helper");
        assertThat(result.scopeId()).isEqualTo("agent-1");
        assertThat(result.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    void summarise_incrementalMerge_preservesExistingEpisodes() {
        var existing = new IndividualEpisode("ep-old", Instant.EPOCH, null,
                List.of(), "old episode", 0.5, List.of());
        var state = existingState(List.of(existing));

        var items = List.of(reflection("new insight"));
        var result = summariser.summarise(items, state).toCompletableFuture().join();

        assertThat(result.episodes()).hasSize(2);
        assertThat(result.episodes().get(0).id()).isEqualTo("ep-old");
        assertThat(result.episodes().get(1).description())
                .isEqualTo("Helped team through production outage");
    }

    @Test
    void summarise_themesFullyReDerived() {
        var oldTheme = new DerivedTheme("old-id", Instant.EPOCH, null,
                List.of(), "old-theme", 0.5,
                Map.of(), List.of());
        var state = existingState(List.of(oldTheme));

        var items = List.of(reflection("new insight"));
        var result = summariser.summarise(items, state).toCompletableFuture().join();

        assertThat(result.themes()).hasSize(1);
        assertThat(result.themes().get(0).label()).isEqualTo("crisis-helper");
    }

    @Test
    void summarise_llmFailure_returnsPrevious() {
        shouldThrow = true;
        var state = existingState(List.of());

        var items = List.of(reflection("some insight"));
        var result = summariser.summarise(items, state).toCompletableFuture().join();

        assertThat(result).isSameAs(state);
    }

    @Test
    void summarise_parseFailure_returnsPrevious() {
        llmResponse = ("not valid json at all");
        var state = existingState(List.of());

        var items = List.of(reflection("some insight"));
        var result = summariser.summarise(items, state).toCompletableFuture().join();

        assertThat(result).isSameAs(state);
    }

    @Test
    void summarise_emptyItems_returnsPrevious() {
        var state = existingState(List.of());
        var result = summariser.summarise(List.of(), state).toCompletableFuture().join();

        assertThat(result).isSameAs(state);
        assertThat(invocationCount).isZero();
    }

    @Test
    void summarise_reflectionIndexMapping() {
        var r0 = new ReflectionEntry("agent-1", "tenant-1", "insight-0",
                EARLIER, List.of("src-a", "src-b"));
        var r1 = new ReflectionEntry("agent-1", "tenant-1", "insight-1",
                EARLIER, List.of("src-c"));
        var items = List.of(r0, r1);

        var result = summariser.summarise(items, null).toCompletableFuture().join();

        assertThat(result.episodes().get(0).sourceReflectionIds())
                .containsExactly("src-a", "src-b");
    }

    @Test
    void summarise_maxReflectionsCapped() {
        var config = new NarrativeConfig(NarrativeSynthesisGate.defaults(),
                20, 10, 0.1, 2, "domain", "case-type");
        var capped = buildSummariser(config);

        var items = List.of(
                reflection("a"), reflection("b"), reflection("c"));
        capped.summarise(items, null).toCompletableFuture().join();

        assertThat(lastUserPrompt)
                .contains("0. a")
                .contains("1. b")
                .doesNotContain("2. c");
    }

    @Test
    void summarise_emptyResult_returnsPrevious() {
        llmResponse = ("""
                { "newEpisodes": [], "themes": [] }
                """);
        var state = existingState(List.of());
        var result = summariser.summarise(List.of(reflection("x")), state)
                .toCompletableFuture().join();

        assertThat(result).isSameAs(state);
    }

    @Test
    void summarise_noPruning_allEpisodesKept() {
        var config = new NarrativeConfig(NarrativeSynthesisGate.defaults(),
                1, 10, 0.1, 100, "domain", "case-type");
        var small = buildSummariser(config);

        var existing = new IndividualEpisode("ep-old", Instant.EPOCH, null,
                List.of(), "old", 0.5, List.of());
        var state = existingState(List.of(existing));

        var result = small.summarise(List.of(reflection("x")), state)
                .toCompletableFuture().join();

        assertThat(result.episodes()).hasSize(2);
    }

    @Test
    void summarise_groupEpisodesIncludedInTagMatching() {
        var groupEpisode = new GroupEpisode("gep1", Instant.EPOCH, Instant.EPOCH,
                List.of("crisis"), "group desc", 0.3,
                Set.of("member1"), Map.of(), 0.5);
        var state = existingState(List.of(groupEpisode));

        var result = summariser.summarise(List.of(reflection("x")), state)
                .toCompletableFuture().join();

        assertThat(result.groupEpisodes()).hasSize(1);
        assertThat(result.groupEpisodes().get(0).id()).isEqualTo("gep1");
        var crisisTheme = result.themes().stream()
                .filter(t -> t.label().equals("crisis-helper"))
                .findFirst().orElseThrow();
        assertThat(crisisTheme.supportingFragmentIds()).contains("gep1");
    }

    @Test
    void summarise_withMemoryRetriever_includesMemoriesInPrompt() {
        var mockStore = mock(CaseMemoryStore.class);
        when(mockStore.query(any())).thenReturn(List.of(
                new Memory("m1", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                           "tenant-1", null, "Successfully navigated team conflict through mediation",
                           Map.of(), Instant.now(), Confidence.unknown(0.8), null, null, null, null, null),
                new Memory("m2", Subject.of("unknown", "agent-1"), new MemoryDomain("experience"),
                           "tenant-1", null, "Learned to adapt communication style for different audiences",
                           Map.of(), Instant.now(), Confidence.unknown(0.9), null, null, null, null, null)
                                                       ));
        var retriever = new CognitiveMemoryRetriever(mockStore, List.of());

        var s = new NarrativeContentSummariser(NarrativeConfig.defaults(), CLOCK,
                                               StubPipelines.create(init -> new AgentSession() {
                                                   @Override
                                                   public Multi<AgentEvent> query(String prompt) {
                                                       lastUserPrompt = prompt;
                                                       return Multi.createFrom().item(new AgentEvent.TextDelta(llmResponse));
                                                   }

                                                   @Override
                                                   public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

                                                   @Override
                                                   public void close(Duration timeout)   {}
                                               }), retriever);

        s.summarise(List.of(reflection("resolved outage")), null).toCompletableFuture().join();

        assertThat(lastUserPrompt).contains("Relevant Experience Memories");
        assertThat(lastUserPrompt).contains("Successfully navigated team conflict through mediation");
        assertThat(lastUserPrompt).contains("Learned to adapt communication style for different audiences");
    }

    @Test
    void summarise_withoutMemoryRetriever_promptOmitsMemorySection() {
        summariser.summarise(List.of(reflection("resolved outage")), null).toCompletableFuture().join();

        assertThat(lastUserPrompt).doesNotContain("Relevant Experience Memories");
    }
}
