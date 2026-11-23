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
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import org.mockito.Mockito;

class SituationalPersonalityParticipantTest {

    private static final String      TENANT = "test-tenant";
    private static final String      AGENT  = "frida";
    private static final Instant     NOW    = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<String> FACETS = Set.of("Anxiousness", "Emotional Lability", "Hostility");

    private SituationalPersonalityParticipant participant;
    private StubAppraisalAccessor             appraisalAccessor;
    private StubMoodAccessor                  moodAccessor;
    private InMemoryMindMapStore       mindMapStore;
    private PersonalityStateService    stateService;
    private EffectiveCognitiveDefaults effectiveDefaults;
    private List<CognitiveEntry>              journalEntries;
    private Clock                             clock;

    @BeforeEach
    void setUp() {
        clock             = Clock.fixed(NOW, ZoneId.of("UTC"));
        mindMapStore      = new InMemoryMindMapStore();
        stateService      = new PersonalityStateService(mindMapStore, FACETS);
        effectiveDefaults = EffectiveCognitiveDefaults.forTesting();
        appraisalAccessor = new StubAppraisalAccessor();
        moodAccessor      = new StubMoodAccessor();
        journalEntries    = new ArrayList<>();
        CognitiveJournal journal = journalEntries::add;

        var config = SituationalPersonalityConfig.withDefaults(buildModeConfigs());

        participant = new SituationalPersonalityParticipant(
                stateService,
                new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config,
                effectiveDefaults,
                appraisalAccessor,
                moodAccessor,
                journal,
                clock
        );
    }

    @Test
    void stressDetectedAndPersonalityModulated() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        assertThat(state.intensity(SituationalMode.STRESS)).isGreaterThan(0);
        assertThat(state.isEngaged(SituationalMode.STRESS)).isTrue();
    }

    @Test
    void noSituationInputsNoModulation() {
        appraisalAccessor.situationInputs = Map.of();
        appraisalAccessor.secDimensions   = Map.of();
        moodAccessor.pleasure             = 0;
        moodAccessor.arousal              = 0;
        moodAccessor.dominance            = 0;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        for (var facet : FACETS) {
            assertThat(state.facet(facet).displacement()).isCloseTo(0.0, within(1e-9));
        }
    }

    @Test
    void trustAccessor_numericEffectOnDetection() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.7, "threat-social", 0.6);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.8, "conduciveness", 0.2);
        moodAccessor.pleasure             = -0.3;
        moodAccessor.arousal              = 0.5;
        moodAccessor.dominance            = -0.2;

        var modeConfigs = buildModeConfigsWithRelational();
        var config      = SituationalPersonalityConfig.withDefaults(modeConfigs);

        var withoutTrust = new SituationalPersonalityParticipant(
                stateService, new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config, effectiveDefaults,
                appraisalAccessor, moodAccessor,
                journalEntries::add, clock);

        var contextNoTarget = contextWith(descriptorWithStressProfile());
        withoutTrust.tick(contextNoTarget);
        double intensityWithout = stateService.currentState(TENANT, AGENT).intensity(SituationalMode.STRESS);

        var                                             stateService2 = new PersonalityStateService(new InMemoryMindMapStore(), FACETS);
        SituationalPersonalityParticipant.TrustAccessor trust         = (self, other, tenant) -> 0.9;
        var withTrust = new SituationalPersonalityParticipant(
                stateService2, new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config, effectiveDefaults,
                appraisalAccessor, moodAccessor,
                trust,
                journalEntries::add, clock);

        var contextWithTarget = new CognitionTickContext(AGENT, TENANT,
                                                         descriptorWithStressProfile(), (a, t) -> Set.of(),
                                                         "test observation", null, "other-agent", null, EventRef.tick(1));
        withTrust.tick(contextWithTarget);
        double intensityWith = stateService2.currentState(TENANT, AGENT).intensity(SituationalMode.STRESS);

        assertThat(intensityWithout).isGreaterThan(0);
        assertThat(intensityWith).isGreaterThan(0);
        assertThat(intensityWith).as("trust should amplify stress via relational component")
                                 .isGreaterThan(intensityWithout);
    }


    @Test
    void noSituationalProfilesSkipsPull() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithNoProfile());
        participant.tick(context);

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        for (var facet : FACETS) {
            assertThat(state.facet(facet).displacement()).isCloseTo(0.0, within(1e-9));
        }
    }

    @Test
    void journalEntryRecordedOnModulation() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        var modulations = journalEntries.stream()
                                        .filter(e -> e instanceof CognitiveEntry.SituationalModulation)
                                        .map(e -> (CognitiveEntry.SituationalModulation) e)
                                        .toList();
        assertThat(modulations).hasSize(1);
        assertThat(modulations.getFirst().tenantId()).isEqualTo(TENANT);
        assertThat(modulations.getFirst().agentId()).isEqualTo(AGENT);
    }

    @Test
    void statePersistedAcrossTicks() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        double firstDisplacement = stateService.currentState(TENANT, AGENT).facet("Anxiousness").displacement();
        assertThat(firstDisplacement).isGreaterThan(0);

        participant.tick(context);

        double secondDisplacement = stateService.currentState(TENANT, AGENT).facet("Anxiousness").displacement();
        assertThat(secondDisplacement).isGreaterThan(0);
    }

    @Test
    void nullDescriptorSkipsTick() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9);
        var context = new CognitionTickContext(AGENT, TENANT, null, (a, t) -> Set.of());
        participant.tick(context);

        assertThat(stateService.currentState(TENANT, AGENT)).isNull();
    }

    @Test
    void currentSnapshotReflectsDetectionState() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        var snapshot = participant.currentSnapshot(AGENT, TENANT);
        assertThat(snapshot).isPresent();

        var s = snapshot.get();
        assertThat(s.modeIntensities()).containsKey("STRESS");
        assertThat(s.modeIntensities().get("STRESS")).isGreaterThan(0);
        assertThat(s.affectiveWinner()).isEqualTo("STRESS");
        assertThat(s.facetDisplacements()).isNotEmpty();
    }

    @Test
    void currentSnapshotEmptyBeforeAnyTick() {
        var snapshot = participant.currentSnapshot(AGENT, TENANT);
        assertThat(snapshot).isEmpty();
    }

    @Test
    void evictIdleRemovesStaleEntries() {
        var mockClock = Mockito.mock(Clock.class);
        Mockito.when(mockClock.instant()).thenReturn(NOW);
        Mockito.when(mockClock.getZone()).thenReturn(ZoneId.of("UTC"));

        var config = SituationalPersonalityConfig.withDefaults(buildModeConfigs());
        var evictParticipant = new SituationalPersonalityParticipant(
                stateService,
                new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config, effectiveDefaults, appraisalAccessor, moodAccessor,
                new ArrayList<CognitiveEntry>()::add, mockClock);

        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9);
        appraisalAccessor.secDimensions = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure = -0.5;
        moodAccessor.arousal = 0.6;
        moodAccessor.dominance = -0.4;

        evictParticipant.tick(new CognitionTickContext(AGENT, TENANT,
                descriptorWithStressProfile(), (a, t) -> Set.of(),
                "test observation", null, null, null, EventRef.tick(1)));
        assertThat(evictParticipant.currentSnapshot(AGENT, TENANT)).isPresent();

        Mockito.when(mockClock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)));
        int evicted = evictParticipant.evictIdle(Duration.ofHours(1));
        assertThat(evicted).isEqualTo(1);
        assertThat(evictParticipant.currentSnapshot(AGENT, TENANT)).isEmpty();
    }

    @Test
    void evictIdleKeepsRecentEntries() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9);
        appraisalAccessor.secDimensions = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure = -0.5;
        moodAccessor.arousal = 0.6;
        moodAccessor.dominance = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);

        int evicted = participant.evictIdle(Duration.ofHours(1));
        assertThat(evicted).isZero();
        assertThat(participant.currentSnapshot(AGENT, TENANT)).isPresent();
    }

    @Test
    void trustAccessorSignalIncludedWhenTargetAgentPresent() {
        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        SituationalPersonalityParticipant.TrustAccessor trustAccessor =
                (observer, target, tenant) -> 0.8;

        var config = SituationalPersonalityConfig.withDefaults(buildModeConfigs());
        var participantWithTrust = new SituationalPersonalityParticipant(
                stateService,
                new SituationalDetector(config),
                new PersonalityStateIntegrator(config.dynamics()),
                config, effectiveDefaults,
                appraisalAccessor, moodAccessor, trustAccessor,
                journalEntries::add, clock);

        var descriptor = descriptorWithStressProfile();
        var context = new CognitionTickContext(AGENT, TENANT, descriptor, (a, t) -> Set.of(),
                                               "test observation", null, "other-agent", null, EventRef.tick(1));
        participantWithTrust.tick(context);

        var state = stateService.currentState(TENANT, AGENT);
        assertThat(state).isNotNull();
        assertThat(state.intensity(SituationalMode.STRESS)).isGreaterThan(0);
    }


    @Test
    void compositeDeltaScaledByPhaseSalience() {
        var personSgId = mindMapStore.createSubgraph(
                new SubgraphInput(
                        "Person", SubgraphTypes.PERSON, null), TENANT);
        mindMapStore.addNode(
                NodeInput.of(AGENT, personSgId)
                                                      .withProperties(Map.of(
                                                              "lifecycle.born", "2020-01-01",
                                                              "archetype-1-family", "creator",
                                                              "archetype-1-salience-childhood", "0.5"
                                                                            )).withProvenance("test"),
                TENANT);

        participant.setMindMapStore(mindMapStore);

        appraisalAccessor.situationInputs = Map.of("threat-physical", 0.9, "threat-social", 0.8);
        appraisalAccessor.secDimensions   = Map.of("relevance", 0.9, "conduciveness", 0.1);
        moodAccessor.pleasure             = -0.5;
        moodAccessor.arousal              = 0.6;
        moodAccessor.dominance            = -0.4;

        var context = contextWith(descriptorWithStressProfile());
        participant.tick(context);
        double scaledDisplacement = stateService.currentState(TENANT, AGENT).facet("Anxiousness").displacement();

        var noSalienceParticipant = new SituationalPersonalityParticipant(
                stateService, new SituationalDetector(SituationalPersonalityConfig.withDefaults(buildModeConfigs())),
                new PersonalityStateIntegrator(SituationalPersonalityConfig.withDefaults(buildModeConfigs()).dynamics()),
                SituationalPersonalityConfig.withDefaults(buildModeConfigs()),
                effectiveDefaults, appraisalAccessor, moodAccessor,
                new ArrayList<CognitiveEntry>()::add, clock);

        var context2 = new CognitionTickContext("agent-no-salience", TENANT,
                                                descriptorWithStressProfileForAgent("agent-no-salience"), (a, t) -> Set.of(),
                                                "test observation", null, null, null, EventRef.tick(1));
        noSalienceParticipant.tick(context2);
        double unscaledDisplacement = stateService.currentState(TENANT, "agent-no-salience")
                                                  .facet("Anxiousness").displacement();

        assertThat(scaledDisplacement).isGreaterThan(0);
        assertThat(unscaledDisplacement).isGreaterThan(0);
        assertThat(scaledDisplacement).isLessThan(unscaledDisplacement);
    }


    // --- Helpers ---

    private CognitionTickContext contextWith(AgentDescriptor descriptor) {
        return new CognitionTickContext(AGENT, TENANT, descriptor, (a, t) -> Set.of(),
                                        "test observation", null, null, null, EventRef.tick(1));
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

    private AgentDescriptor descriptorWithNoProfile() {
        return new AgentDescriptor(AGENT, "Frida", "Artist", "1.0", null, null, null, null,
                                   null, null, null, null, null, "test-slot", null, "creator-artist", null, null,
                                   List.of(),
                                   AgentDisposition.builder()
                                                   .ampdFacets(List.of(
                                                           new AmpdFacet("Anxiousness", 62),
                                                           new AmpdFacet("Emotional Lability", 55),
                                                           new AmpdFacet("Hostility", 30)))
                                                   .build(),
                                   null, null, TENANT, null, null, null, List.of(), List.of(), List.of(), Map.of());
    }

    private AgentDescriptor descriptorWithStressProfileForAgent(String agentId) {
        var stressProfile = new SituationalProfile("stress", List.of(
                new AmpdFacetShift("Anxiousness", 15),
                new AmpdFacetShift("Emotional Lability", 10),
                new AmpdFacetShift("Hostility", 5)
                                                                    ), "estimated");

        return new AgentDescriptor(agentId, "Test", "Artist", "1.0", null, null, null, null,
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
        var stressMode = new ModeDetectionConfig(SituationalMode.Group.AFFECTIVE,
                                                 stressExo, stressEndo, null, 0.3, 0.1, Duration.ofMinutes(5));

        return Map.of(SituationalMode.STRESS, stressMode);
    }

    private Map<SituationalMode, ModeDetectionConfig> buildModeConfigsWithRelational() {
        var stressExo = List.<SignalConfig>of(
                new SignalConfig.SituationInputSignal(
                        List.of("threat-physical", "threat-social"),
                        SignalConfig.Aggregation.MAX, 0.5),
                new SignalConfig.MoodSignal("pleasure", SignalTransform.NEGATIVE_PART, 0.3),
                new SignalConfig.MoodSignal("dominance", SignalTransform.NEGATIVE_PART, 0.2));
        var stressEndo = List.<SignalConfig>of(
                new SignalConfig.SecSignal(List.of(
                        new SignalConfig.SecFactor("relevance", SignalTransform.IDENTITY),
                        new SignalConfig.SecFactor("conduciveness", SignalTransform.COMPLEMENT)), 1.0));
        var relational = new SignalConfig.RelationalConfig("trust", SignalTransform.IDENTITY);
        var stressMode = new ModeDetectionConfig(SituationalMode.Group.AFFECTIVE,
                                                 stressExo, stressEndo, relational, 0.3, 0.1, Duration.ofMinutes(5));
        return Map.of(SituationalMode.STRESS, stressMode);
    }


    // --- Stubs ---

    static class StubAppraisalAccessor implements SituationalPersonalityParticipant.AppraisalAccessor {
        Map<String, Double> situationInputs = Map.of();
        Map<String, Double> secDimensions   = Map.of();

        @Override
        public Map<String, Double> currentInputActivations(String agentId, String tenantId) {
            return situationInputs;
        }

        @Override
        public Map<String, Double> currentSecDimensions(String agentId, String tenantId) {
            return secDimensions;
        }
    }

    static class StubMoodAccessor implements SituationalPersonalityParticipant.MoodAccessor {
        double pleasure  = 0;
        double arousal   = 0;
        double dominance = 0;

        @Override
        public double pleasure(String agentId, String tenantId) {
            return pleasure;
        }

        @Override
        public double arousal(String agentId, String tenantId) {
            return arousal;
        }

        @Override
        public double dominance(String agentId, String tenantId) {
            return dominance;
        }
    }
}
