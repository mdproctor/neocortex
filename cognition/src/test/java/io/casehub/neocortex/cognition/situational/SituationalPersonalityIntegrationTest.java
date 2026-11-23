package io.casehub.neocortex.cognition.situational;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AmpdFacet;
import io.casehub.eidos.api.AmpdFacetShift;
import io.casehub.eidos.api.SituationalProfile;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.journal.CognitiveEntry;
import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognition.journal.EventRef;
import io.casehub.neocortex.cognitive.eidos.EidosCognitiveProfileBridge;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DescriptorViewSource;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.cognitive.index.PersonalityStateSnapshot;
import io.casehub.neocortex.cognitive.index.PersonalityStateSource;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.util.LinkedHashMap;

class SituationalPersonalityIntegrationTest {

    private static final String TENANT = "test-tenant";
    private static final String AGENT = "frida";
    private static final Set<String> FACETS = Set.of("Anxiousness", "Emotional Lability", "Hostility");

    private SituationalPersonalityParticipant participant;
    private PersonalityStateService           stateService;
    private EffectiveCognitiveDefaults        effectiveDefaults;
    private List<CognitiveEntry> journalEntries;
    private StubAppraisal appraisal;
    private StubMood mood;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-17T10:00:00Z");
        var store = new InMemoryMindMapStore();
        stateService = new PersonalityStateService(store, FACETS);
        journalEntries = new ArrayList<>();
        CognitiveJournal journal = journalEntries::add;
        appraisal = new StubAppraisal();
        mood = new StubMood();

        var config = SituationalPersonalityConfig.withDefaults(buildModeConfigs());

        var descriptor = descriptorWithStressProfile();
        var view = EidosCognitiveProfileBridge.toDescriptorView(descriptor);

        var viewSource = new DescriptorViewSource() {
            @Override public Optional<DescriptorView> find(String t, String a) {
                return Optional.of(view);
            }
            @Override public List<String> agentIds(String t) {
                return List.of(AGENT);
            }
        };
        var stateSource = new PersonalityStateSource() {
            @Override public Optional<PersonalityStateSnapshot> find(String t, String a, Instant n) {
                var state = stateService.currentState(t, a);
                if (state == null) return Optional.empty();
                var facets = new LinkedHashMap<String, PersonalityStateSnapshot.FacetDisplacement>();
                for (var entry : state.facets().entrySet()) {
                    var fs = entry.getValue();
                    facets.put(entry.getKey(),
                            new PersonalityStateSnapshot.FacetDisplacement(fs.homeOffset(), fs.displacement()));
                }
                return Optional.of(new PersonalityStateSnapshot(facets, System.nanoTime()));
            }
        };

        effectiveDefaults = new EffectiveCognitiveDefaults(
                CognitiveDefaultsRegistry.forTesting(), viewSource, stateSource);
        effectiveDefaults.updateBase(TENANT, view, "init");

        participant = new SituationalPersonalityParticipant(
                stateService,
                new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config,
                effectiveDefaults,
                appraisal,
                mood,
                journal,
                Clock.fixed(now, ZoneId.of("UTC"))
        );
    }

    @Test
    void fullStressCycleWithRecovery() {
        appraisal.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisal.secDimensions = Map.of("relevance", 0.9, "conduciveness", 0.1);
        mood.pleasure = -0.5;
        mood.arousal = 0.6;
        mood.dominance = -0.4;

        tickWith(descriptorWithStressProfile());
        assertStressEngaged();

        var effectiveBefore = effectiveDefaults.forAgent(TENANT, AGENT);
        assertThat(effectiveBefore).isNotNull();

        appraisal.situationInputs = Map.of();
        appraisal.secDimensions = Map.of();
        mood.pleasure = 0.1;
        mood.arousal = -0.1;
        mood.dominance = 0.1;

        for (int i = 0; i < 10; i++) {
            tickWith(descriptorWithStressProfile());
        }

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();

        assertThat(journalEntries.stream()
                .filter(e -> e instanceof CognitiveEntry.SituationalModulation)
                .count()).isEqualTo(11);
    }

    @Test
    void noiseFloorDoesNotHoldMode() {
        appraisal.situationInputs = Map.of("threat-physical", 0.05, "threat-social", 0.03);
        appraisal.secDimensions = Map.of("relevance", 0.2, "conduciveness", 0.5);
        mood.pleasure = 0.0;
        mood.arousal = 0.0;
        mood.dominance = 0.0;

        tickWith(descriptorWithStressProfile());

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        assertThat(state.isEngaged(SituationalMode.STRESS)).isFalse();
        for (var facet : FACETS) {
            assertThat(state.facet(facet).displacement()).isCloseTo(0.0, within(1e-9));
        }
    }

    @Test
    void restartPreservesState() {
        appraisal.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisal.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        mood.pleasure             = -0.5;
        mood.arousal              = 0.6;
        mood.dominance            = -0.4;

        tickWith(descriptorWithStressProfile());

        double displacement = stateService.currentState(TENANT, AGENT).facet("Anxiousness").displacement();
        assertThat(displacement).isGreaterThan(0);

        var loaded = stateService.loadOrCreate(TENANT, AGENT, now);
        assertThat(loaded.facet("Anxiousness").displacement()).isCloseTo(displacement, within(1e-9));
    }

    @Test
    void tenantIsolation() {
        appraisal.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisal.secDimensions = Map.of("relevance", 0.9, "conduciveness", 0.1);
        mood.pleasure = -0.5;
        mood.arousal = 0.6;
        mood.dominance = -0.4;

        tickWith(descriptorWithStressProfile());

        var state1 = stateService.currentState(TENANT, AGENT);
        assertThat(state1).isNotNull();
        assertThat(state1.intensity(SituationalMode.STRESS)).isGreaterThan(0);

        var state2 = stateService.currentState("other-tenant", AGENT);
        assertThat(state2).isNull();
    }

    // --- helpers ---

    private void tickWith(AgentDescriptor descriptor) {
        var context = new CognitionTickContext(AGENT, TENANT, descriptor,
                (a, t) -> Set.of(), "observation", null, null, null, EventRef.tick(1));
        participant.tick(context);
    }

    private void assertStressEngaged() {
        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        assertThat(state.isEngaged(SituationalMode.STRESS)).isTrue();
        assertThat(state.intensity(SituationalMode.STRESS)).isGreaterThan(0);
    }

    private AgentDescriptor descriptorWithStressProfile() {
        var stressProfile = new SituationalProfile("stress", List.of(
                new AmpdFacetShift("Anxiousness", 15),
                new AmpdFacetShift("Emotional Lability", 10),
                new AmpdFacetShift("Hostility", 5)
        ), "estimated");

        return new AgentDescriptor(AGENT, "Frida", "Artist", "1.0", null, null, null, null,
                null, null, null, null, null, "test-slot", null, "creator-artist", null, null,
                List.of(),
                AgentDisposition.builder()
                        .ampdFacets(List.of(
                                new AmpdFacet("Anxiousness", 62),
                                new AmpdFacet("Emotional Lability", 55),
                                new AmpdFacet("Hostility", 30)))
                        .situationalProfiles(stressProfile)
                        .build(),
                null, null, TENANT, null, null, null, List.of(), List.of(), List.of(), Map.of());
    }

    private Map<SituationalMode, ModeDetectionConfig> buildModeConfigs() {
        var stressExo = List.<SignalConfig>of(
                new SignalConfig.SituationInputSignal(
                        List.of("threat-physical", "threat-social"),
                        SignalConfig.Aggregation.MAX, 0.5),
                new SignalConfig.MoodSignal("pleasure", SignalTransform.NEGATIVE_PART, 0.3),
                new SignalConfig.MoodSignal("dominance", SignalTransform.NEGATIVE_PART, 0.2)
        );
        var stressEndo = List.<SignalConfig>of(
                new SignalConfig.SecSignal(List.of(
                        new SignalConfig.SecFactor("relevance", SignalTransform.IDENTITY),
                        new SignalConfig.SecFactor("conduciveness", SignalTransform.COMPLEMENT)
                ), 1.0)
        );
        return Map.of(SituationalMode.STRESS, new ModeDetectionConfig(
                SituationalMode.Group.AFFECTIVE,
                stressExo, stressEndo, null, 0.3, 0.1, Duration.ofMinutes(5)));
    }

    // --- stubs ---

    static class StubAppraisal implements SituationalPersonalityParticipant.AppraisalAccessor {
        Map<String, Double> situationInputs = Map.of();
        Map<String, Double> secDimensions = Map.of();

        @Override
        public Map<String, Double> currentInputActivations(String agentId, String tenantId) {
            return situationInputs;
        }

        @Override
        public Map<String, Double> currentSecDimensions(String agentId, String tenantId) {
            return secDimensions;
        }
    }

    static class StubMood implements SituationalPersonalityParticipant.MoodAccessor {
        double pleasure, arousal, dominance;

        @Override public double pleasure(String agentId, String tenantId) { return pleasure; }
        @Override public double arousal(String agentId, String tenantId) { return arousal; }
        @Override public double dominance(String agentId, String tenantId) { return dominance; }
    }
}
