package io.casehub.neocortex.cognition.gut;

import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.memory.mood.MoodState;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class GutFeelingParticipantTest {

    private static InMemoryMemoryStore createStore() {
        return new InMemoryMemoryStore(new CurrentPrincipal() {
            @Override public String actorId() { return "a1"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return "t1"; }
            @Override public boolean isCrossTenantAdmin() { return true; }
        });
    }

    @Test
    void returnsEmptyWhenNoMemoryStore() {
        var participant = new GutFeelingParticipant(null, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "some observation");
        participant.tick(ctx);
        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void experienceMatchProducesAvoidSignal() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Authority figure criticized my work harshly").withPad(-0.7, 0.6, -0.3));
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Boss dismissed my authority idea without listening").withPad(-0.5, 0.4, -0.4));
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Manager overruled my authority decision publicly").withPad(-0.6, 0.5, -0.5));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "authority");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().valence()).isEqualTo(GutValence.AVOID);
        assertThat(result.get().intensity()).isGreaterThan(0.0);
        assertThat(result.get().resonanceDescription()).isNotNull();
    }

    @Test
    void experienceMatchProducesApproachSignal() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Collaborative brainstorming session with supportive peers").withPad(0.7, 0.5, 0.3));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "brainstorming");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().valence()).isEqualTo(GutValence.APPROACH);
    }

    @Test
    void nullObservationSkipsExperienceLayer() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Some experience").withPad(-0.8, 0.7, -0.5));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void filtersMemoriesWithNullPad() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Experience without PAD data about relevant situation"));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "relevant situation");
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void isolatesResultsByAgent() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Agent 1 negative experience about situation").withPad(-0.8, 0.5, -0.3));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "situation");
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isPresent();
        assertThat(participant.currentResult("a2", "t1")).isEmpty();
    }

    @Test
    void cautiousWhenPleasureNearZero() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Ambiguous encounter with uncertain outcome").withPad(0.05, 0.3, 0.0));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "ambiguous");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().valence()).isEqualTo(GutValence.CAUTIOUS);
    }

    @Test
    void truncatesResonanceDescription() {
        var store = createStore();
        var longText = "a]".repeat(60) + " situation";
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                longText).withPad(-0.5, 0.3, -0.2));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "situation");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().resonanceDescription()).hasSizeLessThanOrEqualTo(100);
    }

    private static MoodOrchestrator stubMoodReturning(
            String agentId, String tenantId, double p, double a, double d) {
        var orchestrator = new MoodOrchestrator(MoodConfig.defaults()) {
            @Override
            public Optional<MoodState> currentMood(String aId, String tId) {
                if (agentId.equals(aId) && tenantId.equals(tId)) {return Optional.of(new MoodState(aId, tId, Instant.now(), p, a, d, "test", null, null, Map.of()));}
                return Optional.empty();
            }
        };
        return orchestrator;
    }

    @Test
    void moodDistanceFallbackWhenNoExperienceMatch() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("node", "n1"), new MemoryDomain("affect"), "t1",
                                   "PAD update").withPad(-0.6, 0.7, -0.3));
        store.store(MemoryInput.of(Subject.of("node", "n2"), new MemoryDomain("affect"), "t1",
                                   "PAD update").withPad(-0.5, 0.6, -0.4));

        var moodOrchestrator = stubMoodReturning("a1", "t1", 0.8, 0.1, 0.5);
        var participant      = new GutFeelingParticipant(store, moodOrchestrator);
        var ctx              = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().resonanceDescription()).isNotNull();
    }

    @Test
    void noResonanceWhenMoodNearCentroid() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("node", "n1"), new MemoryDomain("affect"), "t1",
                                   "PAD update").withPad(0.3, 0.2, 0.1));

        var moodOrchestrator = stubMoodReturning("a1", "t1", 0.3, 0.2, 0.1);
        var participant      = new GutFeelingParticipant(store, moodOrchestrator);
        var ctx              = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void fallbackReturnsEmptyWhenNoMood() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("node", "n1"), new MemoryDomain("affect"), "t1",
                                   "PAD update").withPad(-0.5, 0.5, -0.3));

        var participant = new GutFeelingParticipant(store, null);
        var ctx         = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of());
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }


    @Test
    void layer1UsesRetrieverWhenAvailable() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                                   "Authority figure criticized harshly").withPad(-0.7, 0.6, -0.3));

        var retriever = new io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever(
                store, java.util.List.of(), java.util.List.of());

        var participant = new GutFeelingParticipant(retriever, store, null, null);
        var ctx         = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "authority");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().valence()).isEqualTo(GutValence.AVOID);
    }

    @Test
    void layer1FallsBackToStoreWhenNoRetriever() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                                   "Collaborative brainstorming with peers").withPad(0.7, 0.5, 0.3));

        var participant = new GutFeelingParticipant(null, store, null, null);
        var ctx         = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "brainstorming");
        participant.tick(ctx);

        var result = participant.currentResult("a1", "t1");
        assertThat(result).isPresent();
        assertThat(result.get().valence()).isEqualTo(GutValence.APPROACH);
    }

    @Test
    void evictIdleRemovesStaleEntries() {
        var store = createStore();
        store.store(MemoryInput.of(Subject.of("agent", "a1"), ExperienceEvents.DOMAIN, "t1",
                "Authority figure criticized harshly").withPad(-0.7, 0.6, -0.3));

        var participant = new GutFeelingParticipant(store, null);
        var ctx = new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), "authority");
        participant.tick(ctx);

        assertThat(participant.currentResult("a1", "t1")).isPresent();

        participant.evictIdle(java.time.Duration.ZERO);

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }
}
