package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.trace.PipelineTrace.SituationalPersonalitySnapshot;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SituationalSnapshotAccessorProducerTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<String> FACETS = Set.of("Anxiousness", "Hostility", "Risk Taking");

    private PersonalityStateService service;
    private SituationalSnapshotAccessor accessor;

    @BeforeEach
    void setUp() {
        var store = new InMemoryMindMapStore();
        service = new PersonalityStateService(store, FACETS);
        accessor = buildAccessor(service);
    }

    @Test
    void snapshotMapsIntensitiesFromState() {
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
    void snapshotMapsWinnersFromState() {
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
    void snapshotComputesFacetTotalShift() {
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
    void snapshotCountsPublishedFacets() {
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
    void returnsEmptyWhenNoStateSeeded() {
        assertThat(accessor.currentSnapshot("unknown", "t1")).isEmpty();
    }

    @Test
    void winnersAreNullWhenNoGroupWinner() {
        service.transition("t1", "a1", NOW, state -> state);

        var snapshot = accessor.currentSnapshot("a1", "t1").orElseThrow();
        assertThat(snapshot.affectiveWinner()).isNull();
        assertThat(snapshot.socialWinner()).isNull();
    }

    private static SituationalSnapshotAccessor buildAccessor(PersonalityStateService svc) {
        return (agentId, tenantId) -> {
            var state = svc.currentState(tenantId, agentId);
            if (state == null) return Optional.empty();

            var modeIntensities = new LinkedHashMap<String, Double>();
            for (var entry : state.intensities().entrySet()) {
                modeIntensities.put(entry.getKey().name(), entry.getValue());
            }

            var affWinner = state.winners().get(SituationalMode.Group.AFFECTIVE);
            var socWinner = state.winners().get(SituationalMode.Group.SOCIAL);

            var facetDisplacements = new LinkedHashMap<String, Double>();
            int publishedCount = 0;
            for (var entry : state.facets().entrySet()) {
                var fs = entry.getValue();
                double totalShift = fs.homeOffset() + fs.displacement() + fs.promoted();
                facetDisplacements.put(entry.getKey(), totalShift);
                if (Math.abs(totalShift) >= PersonalityStateService.EPSILON_PUBLISH) {
                    publishedCount++;
                }
            }

            return Optional.of(new SituationalPersonalitySnapshot(
                    Map.copyOf(modeIntensities),
                    affWinner != null ? affWinner.name() : null,
                    socWinner != null ? socWinner.name() : null,
                    Map.copyOf(facetDisplacements),
                    publishedCount));
        };
    }
}
