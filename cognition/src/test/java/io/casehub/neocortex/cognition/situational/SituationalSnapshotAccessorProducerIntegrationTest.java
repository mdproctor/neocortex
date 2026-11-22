package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.trace.PipelineTrace.SituationalPersonalitySnapshot;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the real {@link SituationalSnapshotAccessorProducer} rather than
 * duplicating its mapping logic. Refs #1193.
 */
class SituationalSnapshotAccessorProducerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<String> FACETS = Set.of("Anxiousness", "Hostility", "Risk Taking");

    private PersonalityStateService service;
    private SituationalSnapshotAccessor accessor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var store = new InMemoryMindMapStore();
        service = new PersonalityStateService(store, FACETS);

        var producer = new SituationalSnapshotAccessorProducer();
        Instance<PersonalityStateService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        producer.stateService = instance;

        accessor = producer.snapshotAccessor();
    }

    @Test
    void mapsIntensitiesFromState() {
        service.transition("t1", "a1", NOW, state -> {
            var intensities = new EnumMap<SituationalMode, Double>(SituationalMode.class);
            for (var mode : SituationalMode.values()) {
                intensities.put(mode, 0.0);
            }
            intensities.put(SituationalMode.STRESS, 0.7);
            intensities.put(SituationalMode.SOCIAL_COLLABORATIVE, 0.4);
            return new PersonalityState(state.facets(), intensities, state.engaged(),
                    state.winners(), state.cursorTime(), state.hourCursor(), state.exposedUntil(),
                    state.version(), state.reflection(), state.livedProfile());
        });

        var snapshot = accessor.currentSnapshot("a1", "t1");
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().modeIntensities()).containsEntry("STRESS", 0.7);
        assertThat(snapshot.get().modeIntensities()).containsEntry("SOCIAL_COLLABORATIVE", 0.4);
    }

    @Test
    void mapsWinnersFromState() {
        service.transition("t1", "a1", NOW, state -> {
            var winners = new EnumMap<SituationalMode.Group, SituationalMode>(SituationalMode.Group.class);
            winners.put(SituationalMode.Group.AFFECTIVE, SituationalMode.STRESS);
            winners.put(SituationalMode.Group.SOCIAL, SituationalMode.SOCIAL_COMPETITIVE);
            return new PersonalityState(state.facets(), state.intensities(), state.engaged(),
                    winners, state.cursorTime(), state.hourCursor(), state.exposedUntil(),
                    state.version(), state.reflection(), state.livedProfile());
        });

        var snapshot = accessor.currentSnapshot("a1", "t1").orElseThrow();
        assertThat(snapshot.affectiveWinner()).isEqualTo("STRESS");
        assertThat(snapshot.socialWinner()).isEqualTo("SOCIAL_COMPETITIVE");
    }

    @Test
    void computesFacetTotalShift() {
        service.transition("t1", "a1", NOW, state -> {
            var facets = new LinkedHashMap<>(state.facets());
            facets.put("Anxiousness", new FacetState(0.1, 0.05, 1.0, 0, 0, 0.02));
            return state.withFacets(facets);
        });

        var snapshot = accessor.currentSnapshot("a1", "t1").orElseThrow();
        assertThat(snapshot.facetDisplacements().get("Anxiousness"))
                .isCloseTo(0.17, within(0.001));
    }

    @Test
    void countsPublishedFacets() {
        service.transition("t1", "a1", NOW, state -> {
            var facets = new LinkedHashMap<>(state.facets());
            facets.put("Anxiousness", new FacetState(0.1, 0.0, 1.0, 0, 0, 0.0));
            facets.put("Hostility", new FacetState(0.001, 0.0, 1.0, 0, 0, 0.0));
            facets.put("Risk Taking", new FacetState(0.0, 0.0, 1.0, 0, 0, 0.0));
            return state.withFacets(facets);
        });

        var snapshot = accessor.currentSnapshot("a1", "t1").orElseThrow();
        assertThat(snapshot.publishedCount())
                .as("only Anxiousness exceeds EPSILON_PUBLISH (0.005)")
                .isEqualTo(1);
    }

    @Test
    void returnsEmptyWhenNoState() {
        assertThat(accessor.currentSnapshot("unknown", "t1")).isEmpty();
    }

    @Test
    void winnersNullWhenNoGroupWinner() {
        service.transition("t1", "a1", NOW, state -> state);

        var snapshot = accessor.currentSnapshot("a1", "t1").orElseThrow();
        assertThat(snapshot.affectiveWinner()).isNull();
        assertThat(snapshot.socialWinner()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyWhenServiceNotResolvable() {
        var producer = new SituationalSnapshotAccessorProducer();
        Instance<PersonalityStateService> absent = mock(Instance.class);
        when(absent.isResolvable()).thenReturn(false);
        producer.stateService = absent;

        var fallbackAccessor = producer.snapshotAccessor();
        assertThat(fallbackAccessor.currentSnapshot("a1", "t1")).isEmpty();
    }
}
