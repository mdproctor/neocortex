package io.casehub.neocortex.cognitive.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveCognitiveDefaultsTest {

    private CognitiveDefaultsRegistry registry;
    private StubDescriptorViewSource viewSource;
    private StubPersonalityStateSource stateSource;
    private EffectiveCognitiveDefaults effective;

    @BeforeEach
    void setUp() {
        registry = new CognitiveDefaultsRegistry();
        viewSource = new StubDescriptorViewSource();
        stateSource = new StubPersonalityStateSource();
        effective = new EffectiveCognitiveDefaults(registry, viewSource, stateSource);
    }

    @Test
    void forAgentReturnsEffectiveDefaults() {
        var view = DescriptorView.of("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of());
        viewSource.add("tenant-1", view);

        var defaults = effective.forAgent("tenant-1", "agent-1");
        assertThat(defaults).isNotNull();
        assertThat(defaults.agentId()).isEqualTo("agent-1");
        assertThat(defaults.moodBaseline()).isNotNull();
    }

    @Test
    void restingForReturnsLearningProfile() {
        var view = DescriptorView.of("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of());
        viewSource.add("tenant-1", view);

        var profile = effective.restingFor("tenant-1", "agent-1");
        assertThat(profile).isNotNull();
        assertThat(profile.agentId()).isEqualTo("agent-1");
    }

    @Test
    void compositionVersionChangeCausesReDerivation() {
        var facets = List.of(
                new PersonalityFacet("Anxiousness", 0.5, "eidos"),
                new PersonalityFacet("Emotional Lability", 0.5, "eidos"));
        var view = new DescriptorView("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of(), null, facets);
        viewSource.add("tenant-1", view);

        var before = effective.forAgent("tenant-1", "agent-1");
        var beforeAppraisal = before.appraisalWeights();

        var shifted = new PersonalityStateSnapshot(
                Map.of("Anxiousness", new PersonalityStateSnapshot.FacetDisplacement(0.0, 0.3)),
                1);
        stateSource.set("tenant-1", "agent-1", shifted);
        stateSource.setCompositionVersion("tenant-1", "agent-1", 1);

        var after = effective.forAgent("tenant-1", "agent-1");
        assertThat(after.appraisalWeights()).isNotEqualTo(beforeAppraisal);
    }

    @Test
    void explicitPinnedFieldSurvivesReDerivation() {
        var explicit = CognitiveDefaults.empty("agent-1")
                .withRegulationDefaults(new RegulationDefaults(0.99, 0.1, 0.1, 0.1));
        registry.register(explicit);
        var view = DescriptorView.of("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of());
        viewSource.add("tenant-1", view);

        var defaults = effective.forAgent("tenant-1", "agent-1");
        assertThat(defaults.regulationDefaults().reappraisalCapacity()).isEqualTo(0.99);
    }

    @Test
    void sameAgentIdInTwoTenantsHasIndependentDefaults() {
        var facetsA = List.of(new PersonalityFacet("Anxiousness", 0.3, "eidos"));
        var facetsB = List.of(new PersonalityFacet("Anxiousness", 0.9, "eidos"));
        var viewA = new DescriptorView("agent-1", null, List.of(), List.of(), null, facetsA);
        var viewB = new DescriptorView("agent-1", null, List.of(), List.of(), null, facetsB);
        viewSource.add("tenant-A", viewA);
        viewSource.add("tenant-B", viewB);

        var defaultsA = effective.forAgent("tenant-A", "agent-1");
        var defaultsB = effective.forAgent("tenant-B", "agent-1");
        assertThat(defaultsA.descriptor().personalityFacets())
                .isNotEqualTo(defaultsB.descriptor().personalityFacets());
    }

    @Test
    void pullContractReflectsStateWithoutPublish() {
        var view = new DescriptorView("agent-1", null, List.of(), List.of(), null,
                List.of(new PersonalityFacet("Anxiousness", 0.5, "eidos")));
        viewSource.add("t1", view);
        effective.forAgent("t1", "agent-1");

        stateSource.set("t1", "agent-1", new PersonalityStateSnapshot(
                Map.of("Anxiousness", new PersonalityStateSnapshot.FacetDisplacement(0.08, 0.0)),
                1));
        stateSource.setCompositionVersion("t1", "agent-1", 1);

        var defaults = effective.forAgent("t1", "agent-1");
        assertThat(defaults).isNotNull();
        var anxFacet = defaults.descriptor().personalityFacets().stream()
                .filter(f -> f.facet().equals("Anxiousness")).findFirst().orElseThrow();
        assertThat(anxFacet.score()).isCloseTo(0.58, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void agentIdsReturnsTenantScopedUnion() {
        viewSource.add("t1", DescriptorView.of("a1", null, List.of(), List.of()));
        viewSource.add("t1", DescriptorView.of("a2", null, List.of(), List.of()));
        registry.register(CognitiveDefaults.empty("a3"));

        var ids = effective.agentIds("t1");
        assertThat(ids).containsExactlyInAnyOrder("a1", "a2", "a3");
    }

    @Test
    void updateBaseRefreshesSlot() {
        var view = DescriptorView.of("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of());
        viewSource.add("t1", view);
        effective.forAgent("t1", "agent-1");

        var updatedView = DescriptorView.of("agent-1",
                new DispositionAxes("competitive", "strict", "bold", "high", "analytical"),
                List.of(new WeightedTerm("te", 0.9)), List.of());
        effective.updateBase("t1", updatedView, "tick-2");

        var defaults = effective.forAgent("t1", "agent-1");
        assertThat(defaults.descriptor()).isEqualTo(updatedView);
    }

    @Test
    void forAgentOrDefaultsNeverReturnsNull() {
        var defaults = effective.forAgentOrDefaults("t1", "unknown-agent");
        assertThat(defaults).isNotNull();
        assertThat(defaults.agentId()).isEqualTo("unknown-agent");
    }

    @Test
    void refreshStaleRecomputesSlotsOlderThanMaxAge() {
        var view = DescriptorView.of("agent-1",
                                     new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                                     List.of(new WeightedTerm("fe", 0.8)), List.of());
        viewSource.add("t1", view);
        effective.forAgent("t1", "agent-1");

        var updatedView = DescriptorView.of("agent-1",
                                            new DispositionAxes("competitive", "strict", "bold", "high", "analytical"),
                                            List.of(new WeightedTerm("te", 0.9)), List.of());
        viewSource.add("t1", updatedView);

        effective.refreshStale(java.time.Duration.ZERO);

        var defaults = effective.forAgent("t1", "agent-1");
        assertThat(defaults.descriptor()).isEqualTo(updatedView);
    }


    @Test
    void restingExcludesDisplacementEffectiveIncludes() {
        var facets = List.of(new PersonalityFacet("Anxiousness", 0.5, "eidos"));
        var view = new DescriptorView("agent-1",
                new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"),
                List.of(new WeightedTerm("fe", 0.8)), List.of(), null, facets);
        viewSource.add("t1", view);

        var state = new PersonalityStateSnapshot(
                Map.of("Anxiousness", new PersonalityStateSnapshot.FacetDisplacement(0.1, 0.2)),
                1);
        stateSource.set("t1", "agent-1", state);

        var resting = effective.restingFor("t1", "agent-1");
        var eff = effective.forAgent("t1", "agent-1");

        var restingAnx = resting.facets().stream()
                .filter(f -> f.facet().equals("Anxiousness")).findFirst().orElseThrow();
        var effAnx = eff.descriptor().personalityFacets().stream()
                .filter(f -> f.facet().equals("Anxiousness")).findFirst().orElseThrow();

        assertThat(restingAnx.score()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.001));
        assertThat(effAnx.score()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void situationalOriginSetWhenFacetShifted() {
        var facets = List.of(new PersonalityFacet("Anxiousness", 0.5, "eidos"));
        var view = new DescriptorView("agent-1", null, List.of(), List.of(), null, facets);
        viewSource.add("t1", view);

        var state = new PersonalityStateSnapshot(
                Map.of("Anxiousness", new PersonalityStateSnapshot.FacetDisplacement(0.0, 0.2)),
                1);
        stateSource.set("t1", "agent-1", state);

        var eff = effective.forAgent("t1", "agent-1");
        var anxFacet = eff.descriptor().personalityFacets().stream()
                .filter(f -> f.facet().equals("Anxiousness")).findFirst().orElseThrow();
        assertThat(anxFacet.origin()).isEqualTo("situational [usual 0.50]");
    }

    @Test
    void fieldClassificationRestingVsEffective() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.3, "eidos"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.3, "eidos"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.7, "eidos"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.3, "eidos"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.4, "eidos"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.2, "eidos"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.2, "eidos"));
        var view = new DescriptorView("agent-1",
                                      new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative"),
                                      List.of(new WeightedTerm("fe", 0.8), new WeightedTerm("si", 0.5)),
                                      List.of("goal-a"), null, facets);
        viewSource.add("t1", view);

        var displacementMap = Map.of(
                PersonalityFacet.ANXIOUSNESS, new PersonalityStateSnapshot.FacetDisplacement(0.0, 0.4),
                PersonalityFacet.EMOTIONAL_LABILITY, new PersonalityStateSnapshot.FacetDisplacement(0.0, 0.3),
                PersonalityFacet.RISK_TAKING, new PersonalityStateSnapshot.FacetDisplacement(0.0, -0.3));
        var state = new PersonalityStateSnapshot(displacementMap, 1);
        stateSource.set("t1", "agent-1", state);

        var resting = effective.restingFor("t1", "agent-1");
        var eff     = effective.forAgent("t1", "agent-1");

        assertThat(resting.personality()).as("personality: profile-derived, displacement-independent")
                                         .isEqualTo(eff.personality());
        assertThat(resting.personalityDominance()).as("personalityDominance: profile-derived, displacement-independent")
                                                  .isEqualTo(eff.personalityDominance());
        assertThat(resting.temporalFocus()).as("temporalFocus: goals-derived, displacement-independent")
                                           .isEqualTo(eff.temporalFocus());

        assertThat(resting.appraisalWeights()).as("appraisalWeights: facet-derived, displacement-sensitive")
                                              .isNotEqualTo(eff.appraisalWeights());
        assertThat(resting.habituationConfig()).as("habituationConfig: facet-derived, displacement-sensitive")
                                               .isNotEqualTo(eff.habituationConfig());
        assertThat(resting.recurringThoughtConfig()).as("recurringThoughtConfig: facet-derived, displacement-sensitive")
                                                    .isNotEqualTo(eff.recurringThoughtConfig());
        assertThat(resting.cbrStrategy()).as("cbrStrategy: facet-modulated via IMPULSIVITY/RISK_TAKING, displacement-sensitive")
                                         .isNotEqualTo(eff.cbrStrategy());

        var restingFacets = resting.facets();
        var effFacets = eff.descriptor().personalityFacets();
        var restingAnx = restingFacets.stream().filter(f -> f.facet().equals(PersonalityFacet.ANXIOUSNESS)).findFirst().orElseThrow();
        var effAnx = effFacets.stream().filter(f -> f.facet().equals(PersonalityFacet.ANXIOUSNESS)).findFirst().orElseThrow();
        assertThat(effAnx.score()).as("effective facet score includes displacement")
                                  .isGreaterThan(restingAnx.score());
    }


    // --- Stubs ---

    static class StubDescriptorViewSource implements DescriptorViewSource {
        private final Map<String, Map<String, DescriptorView>> views = new HashMap<>();

        void add(String tenantId, DescriptorView view) {
            views.computeIfAbsent(tenantId, k -> new HashMap<>()).put(view.agentId(), view);
        }

        @Override
        public Optional<DescriptorView> find(String tenantId, String agentId) {
            return Optional.ofNullable(views.getOrDefault(tenantId, Map.of()).get(agentId));
        }

        @Override
        public List<String> agentIds(String tenantId) {
            return List.copyOf(views.getOrDefault(tenantId, Map.of()).keySet());
        }
    }

    static class StubPersonalityStateSource implements PersonalityStateSource {
        private final Map<String, PersonalityStateSnapshot> states = new HashMap<>();
        private final Map<String, Long> compositionVersions = new HashMap<>();

        void set(String tenantId, String agentId, PersonalityStateSnapshot snap) {
            states.put(tenantId + ":" + agentId, snap);
        }

        void setCompositionVersion(String tenantId, String agentId, long cv) {
            compositionVersions.put(tenantId + ":" + agentId, cv);
        }

        @Override
        public Optional<PersonalityStateSnapshot> find(String tenantId, String agentId, Instant now) {
            return Optional.ofNullable(states.get(tenantId + ":" + agentId));
        }

        @Override
        public long compositionVersion(String tenantId, String agentId) {
            return compositionVersions.getOrDefault(tenantId + ":" + agentId, 0L);
        }
    }
}
