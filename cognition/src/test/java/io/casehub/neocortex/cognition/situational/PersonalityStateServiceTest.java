package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.util.LinkedHashMap;
import java.util.Set;

class PersonalityStateServiceTest {

    private              InMemoryMindMapStore    store;
    private              PersonalityStateService service;
    private static final Instant                 NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<String>   FACETS = Set.of(
            "Anxiousness", "Callousness", "Hostility", "Emotional Lability",
            "Grandiosity", "Impulsivity", "Risk Taking", "Suspiciousness",
            "Withdrawal", "Manipulativeness", "Deceitfulness",
            "Intimacy Avoidance", "Attention Seeking");

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        service = new PersonalityStateService(store, FACETS);
    }

    @Test
    void initialStateIsAtRest() {
        var state = service.loadOrCreate("t1", "agent1", NOW);
        assertThat(state.facets()).isNotEmpty();
        assertThat(state.facets()).allSatisfy((name, fs) -> {
            assertThat(fs.displacement()).isZero();
            assertThat(fs.homeOffset()).isZero();
            assertThat(fs.resilience()).isEqualTo(1.0);
        });
        for (var mode : SituationalMode.values()) {
            assertThat(state.intensity(mode)).isZero();
            assertThat(state.isEngaged(mode)).isFalse();
        }
    }

    @Test
    void persistenceRoundTrip() {
        var state = service.loadOrCreate("t1", "agent1", NOW);
        var updated = state.withFacets(Map.of(
                "Anxiousness", new FacetState(0.15, 0.02, 0.9, 1.5, 0.3),
                "Hostility", new FacetState(-0.1, 0.0, 1.0, 0.0, 0.0)));
        service.persist("t1", "agent1", updated);

        var service2 = new PersonalityStateService(store, FACETS);
        var loaded = service2.loadOrCreate("t1", "agent1", NOW);
        assertThat(loaded.facets().get("Anxiousness").displacement()).isCloseTo(0.15, within(1e-9));
        assertThat(loaded.facets().get("Anxiousness").homeOffset()).isCloseTo(0.02, within(1e-9));
        assertThat(loaded.facets().get("Anxiousness").resilience()).isCloseTo(0.9, within(1e-9));
        assertThat(loaded.facets().get("Hostility").displacement()).isCloseTo(-0.1, within(1e-9));
    }

    @Test
    void deterministicNodeIdentity() {
        var name1 = PersonalityStateService.nodeName("agent1");
        var name2 = PersonalityStateService.nodeName("agent1");
        assertThat(name1).isEqualTo(name2);
        assertThat(name1).startsWith("sit-state:");
    }

    @Test
    void differentAgentsDifferentNodes() {
        var name1 = PersonalityStateService.nodeName("agent1");
        var name2 = PersonalityStateService.nodeName("agent2");
        assertThat(name1).isNotEqualTo(name2);
    }

    @Test
    void tenantIsolation() {
        service.loadOrCreate("t1", "agent1", NOW);
        service.loadOrCreate("t2", "agent1", NOW);

        var subgraphs = store.listSubgraphs("t1").stream()
                .filter(sg -> SubgraphTypes.PERSONALITY_STATE.equals(sg.type()))
                .toList();
        assertThat(subgraphs).hasSize(1);

        var subgraphs2 = store.listSubgraphs("t2").stream()
                .filter(sg -> SubgraphTypes.PERSONALITY_STATE.equals(sg.type()))
                .toList();
        assertThat(subgraphs2).hasSize(1);
        assertThat(subgraphs.getFirst().id()).isNotEqualTo(subgraphs2.getFirst().id());
    }

    @Test
    void secondLoadReturnsCached() {
        var state1 = service.loadOrCreate("t1", "agent1", NOW);
        var state2 = service.loadOrCreate("t1", "agent1", NOW);
        assertThat(state1).isSameAs(state2);
    }

    @Test
    void currentStateNullBeforeLoad() {
        assertThat(service.currentState("t1", "agent1")).isNull();
    }

    @Test
    void currentStateAvailableAfterLoad() {
        service.loadOrCreate("t1", "agent1", NOW);
        assertThat(service.currentState("t1", "agent1")).isNotNull();
    }

    @Test
    void propertiesRoundTrip() {
        var facets = new LinkedHashMap<String, FacetState>();
        facets.put("Anxiousness", new FacetState(0.12, 0.03, 0.85, 2.1, 0.4));
        facets.put("Hostility", FacetState.initial());
        var state = new PersonalityState(facets,
                Map.of(SituationalMode.STRESS, 0.75, SituationalMode.FLOW, 0.0),
                Map.of(SituationalMode.STRESS, true, SituationalMode.FLOW, false),
                Map.of(SituationalMode.Group.AFFECTIVE, SituationalMode.STRESS),
                NOW, PersonalityState.truncateToHour(NOW), NOW.plusSeconds(3600));

        var props = PersonalityStateService.toProperties(state);
        var loaded = service.fromProperties(props, NOW);

        assertThat(loaded.facets().get("Anxiousness").displacement()).isCloseTo(0.12, within(1e-9));
        assertThat(loaded.facets().get("Anxiousness").resilience()).isCloseTo(0.85, within(1e-9));
        assertThat(loaded.intensity(SituationalMode.STRESS)).isCloseTo(0.75, within(1e-9));
        assertThat(loaded.isEngaged(SituationalMode.STRESS)).isTrue();
        assertThat(loaded.winners().get(SituationalMode.Group.AFFECTIVE)).isEqualTo(SituationalMode.STRESS);
        assertThat(loaded.cursorTime()).isEqualTo(NOW);
        assertThat(loaded.exposedUntil()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void transitionAppliesOperatorAtomicallyAndIncrementsVersion() {
        service.read("t1", "agent1", NOW);
        var result = service.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", st.facets().get("Anxiousness").withHomeOffset(0.05));
            return st.withFacets(facets);
        });
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.facets().get("Anxiousness").homeOffset()).isCloseTo(0.05, within(1e-9));
        assertThat(service.currentState("t1", "agent1").version()).isEqualTo(1);
    }

    @Test
    void compositionVersionBumpsOnlyWhenComposedViewChanges() {
        service.read("t1", "agent1", NOW);
        assertThat(service.compositionVersion("t1", "agent1")).isZero();

        service.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", st.facets().get("Anxiousness").withHomeOffset(0.05));
            return st.withFacets(facets);
        });
        var v1 = service.compositionVersion("t1", "agent1");
        assertThat(v1).isGreaterThan(0);

        service.transition("t1", "agent1", NOW, st -> st.withCursorTime(NOW.plusSeconds(60)));
        var v2 = service.compositionVersion("t1", "agent1");
        assertThat(v2).isEqualTo(v1);
    }

    @Test
    void promotionDoesNotBumpCompositionVersion() {
        service.read("t1", "agent1", NOW);
        service.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", st.facets().get("Anxiousness").withHomeOffset(0.05));
            return st.withFacets(facets);
        });
        var v1 = service.compositionVersion("t1", "agent1");

        service.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            var anx    = facets.get("Anxiousness");
            facets.put("Anxiousness", new FacetState(anx.displacement(), anx.homeOffset() - 0.03,
                                                     anx.resilience(), anx.exposure(), anx.load(), anx.promoted() + 0.03));
            return st.withFacets(facets);
        });
        var v2 = service.compositionVersion("t1", "agent1");
        assertThat(v2).isEqualTo(v1);
    }

    @Test
    void interleavedTransitionsPreserveBothWriterEffects() throws Exception {
        service.read("t1", "agent1", NOW);

        var t1 = new Thread(() -> {
            service.transition("t1", "agent1", NOW, st -> {
                var facets = new LinkedHashMap<>(st.facets());
                facets.put("Anxiousness", st.facets().get("Anxiousness").withHomeOffset(0.05));
                return st.withFacets(facets);
            });
        });
        var t2 = new Thread(() -> {
            service.transition("t1", "agent1", NOW, st -> {
                var facets = new LinkedHashMap<>(st.facets());
                facets.put("Hostility", st.facets().get("Hostility").withHomeOffset(0.03));
                return st.withFacets(facets);
            });
        });
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        var state = service.currentState("t1", "agent1");
        assertThat(state.version()).isEqualTo(2);
        assertThat(state.facets().get("Anxiousness").homeOffset()).isCloseTo(0.05, within(1e-9));
        assertThat(state.facets().get("Hostility").homeOffset()).isCloseTo(0.03, within(1e-9));
    }


    @Test
    void agentIdsIncludesCachedAgents() {
        service.read("t1", "agent1", NOW);
        service.read("t1", "agent2", NOW);
        var ids = service.agentIds("t1");
        assertThat(ids).containsExactlyInAnyOrder("agent1", "agent2");
    }

    @Test
    void readReturnsPersistedStateAfterServiceRecreation() {
        service.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", st.facets().get("Anxiousness").withHomeOffset(0.07));
            return st.withFacets(facets);
        });
        var service2 = new PersonalityStateService(store, FACETS);
        var loaded   = service2.read("t1", "agent1", NOW);
        assertThat(loaded.facets().get("Anxiousness").homeOffset()).isCloseTo(0.07, within(1e-9));
        assertThat(loaded.version()).isEqualTo(1);
    }
}
