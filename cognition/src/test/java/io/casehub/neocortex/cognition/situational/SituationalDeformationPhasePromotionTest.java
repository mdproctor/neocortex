package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import java.util.LinkedHashMap;

class SituationalDeformationPhasePromotionTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<String> FACETS = Set.of("Anxiousness");

    private InMemoryMindMapStore store;
    private PersonalityStateService stateService;
    private PersonalityStateIntegrator integrator;
    private EffectiveCognitiveDefaults effectiveDefaults;
    private SituationalDeformationPhase phase;

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        stateService = new PersonalityStateService(store, FACETS);
        integrator = new PersonalityStateIntegrator(DynamicsConfig.defaults());

        effectiveDefaults = mock(EffectiveCognitiveDefaults.class);
        var defaults = mock(CognitiveDefaults.class);
        var descriptor = mock(DescriptorView.class);
        when(descriptor.personalityFacets()).thenReturn(
                List.of(new PersonalityFacet("Anxiousness", 0.5, "authored")));
        when(defaults.descriptor()).thenReturn(descriptor);
        when(effectiveDefaults.forAgent("t1", "agent1")).thenReturn(defaults);

        var journal = mock(CognitiveJournal.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        phase = new SituationalDeformationPhase(stateService, integrator, effectiveDefaults, journal, clock);
    }

    @Test
    void promotionViaTransitionMovesHomeOffsetToPromoted() {
        var saturatedSince = NOW.minus(Duration.ofDays(31));
        stateService.read("t1", "agent1", NOW);
        stateService.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", new FacetState(0.0, 0.09, 1.0, 0, 0, 0.0, saturatedSince, null));
            return st.withFacets(facets);
        });

        phase.run("t1", List.of());

        var state = stateService.currentState("t1", "agent1");
        assertThat(state).isNotNull();
        var anx = state.facets().get("Anxiousness");
        assertThat(anx.promoted()).isGreaterThan(0);
        double restingSum = anx.promoted() + anx.homeOffset();
        assertThat(restingSum).isCloseTo(0.09, within(0.02));
    }

    @Test
    void noPromotionWhenDwellNotReached() {
        var saturatedSince = NOW.minus(Duration.ofDays(10));
        stateService.read("t1", "agent1", NOW);
        stateService.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", new FacetState(0.0, 0.09, 1.0, 0, 0, 0.0, saturatedSince, null));
            return st.withFacets(facets);
        });

        phase.run("t1", List.of());

        var state = stateService.currentState("t1", "agent1");
        var anx = state.facets().get("Anxiousness");
        assertThat(anx.promoted()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void crashRecoveryNeverDoubleCounts() {
        var saturatedSince = NOW.minus(Duration.ofDays(31));
        stateService.read("t1", "agent1", NOW);
        stateService.transition("t1", "agent1", NOW, st -> {
            var facets = new LinkedHashMap<>(st.facets());
            facets.put("Anxiousness", new FacetState(0.0, 0.09, 1.0, 0, 0, 0.0, saturatedSince, null));
            return st.withFacets(facets);
        });

        phase.run("t1", List.of());
        var afterFirst = stateService.currentState("t1", "agent1").facets().get("Anxiousness");
        double promotedAfterFirst = afterFirst.promoted();

        phase.run("t1", List.of());
        var afterSecond = stateService.currentState("t1", "agent1").facets().get("Anxiousness");
        assertThat(afterSecond.promoted()).isCloseTo(promotedAfterFirst, within(0.01));
    }
}
