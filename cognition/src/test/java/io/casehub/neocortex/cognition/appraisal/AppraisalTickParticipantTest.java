package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveIntensity;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.OverlayRef;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppraisalTickParticipantTest {

    private DriveOrchestrator driveOrchestrator;
    private MoodOrchestrator moodOrchestrator;
    private SalienceStrategy salienceStrategy;
    private AppraisalStrategy appraisalStrategy;

    @BeforeEach
    void setUp() {
        driveOrchestrator = mock(DriveOrchestrator.class);
        moodOrchestrator = mock(MoodOrchestrator.class);
        salienceStrategy = new NoOpSalienceStrategy();
        appraisalStrategy = new NoOpAppraisalStrategy();

        var profile = new DriveProfile("a1", "t1",
                Map.of(DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.7, "gaps")),
                0.7, DriveAxis.CURIOSITY, Instant.now());
        when(driveOrchestrator.currentDrives("a1", "t1")).thenReturn(Optional.of(profile));
        when(moodOrchestrator.currentMood("a1", "t1")).thenReturn(Optional.empty());
    }

    @Test
    void skipsWhenAppraisalDisabled() {
        var config = CognitionConfig.none();
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("The room is dark"));

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void skipsWhenNoObservation() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(new CognitionTickContext("a1", "t1", null, (a, t) -> java.util.Set.of()));

        assertThat(participant.currentResult("a1", "t1")).isEmpty();
    }

    @Test
    void producesResultWithObservation() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("The room is dark"));

        assertThat(participant.currentResult("a1", "t1")).isPresent();
    }

    @Test
    void passesThroughObservationWithSalienceDisabled() {
        var capturingSalience = Mockito.spy(salienceStrategy);
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                capturingSalience, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("dark corridor"));

        verify(capturingSalience, never()).perceive(any());
    }

    @Test
    void runsSalienceWhenEnabled() {
        var capturingSalience = Mockito.spy(salienceStrategy);
        var config = CognitionConfig.none().with("appraisal", true).with("salience", true);
        var participant = new AppraisalTickParticipant(
                capturingSalience, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("dark corridor"));

        verify(capturingSalience).perceive(any());
    }

    @Test
    void habituationStatePersistsAcrossTicks() {
        AppraisalStrategy trackingStrategy = ctx -> {
            var hash = Integer.toHexString(ctx.situation().narrative().hashCode());
            var count = ctx.habituation().observationCounts().getOrDefault(hash, 0);
            return new AppraisalResult(List.of(), List.of(),
                    ctx.habituation().withObservation(hash, 1.0 - count * 0.2));
        };

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, trackingStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("bookshelf"));
        participant.tick(contextWithObservation("bookshelf"));

        var result = participant.currentResult("a1", "t1").orElseThrow();
        assertThat(result.updatedHabituation().observationCounts().values())
                .allSatisfy(count -> assertThat(count).isEqualTo(2));
    }

    @Test
    void includesCharacterDrivesFromMindMap() {
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var sgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("Cognitive",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), "t1");
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("scheming", sgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity",
                                                                    "drive-type", "scheming",
                                                                    "intensity", "0.75",
                                                                    "agent-id", "a1")),
                      "t1");

        var capturedDrives = new java.util.concurrent.atomic.AtomicReference<List<Drive>>();
        AppraisalStrategy capturing = ctx -> {
            capturedDrives.set(ctx.drives());
            return AppraisalResult.empty();
        };

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, capturing, driveOrchestrator,
                moodOrchestrator, null, config, store);

        participant.tick(contextWithObservation("The villain plots"));

        var drives = capturedDrives.get();
        assertThat(drives).isNotNull();
        assertThat(drives).anyMatch(d ->
                                            d.name().equals("scheming") &&
                                            d.category() == DriveCategory.CHARACTER &&
                                            d.intensity() == 0.75);
    }

    @Test
    void worksWithoutMindMapStore() {
        var capturedDrives = new java.util.concurrent.atomic.AtomicReference<List<Drive>>();
        AppraisalStrategy capturing = ctx -> {
            capturedDrives.set(ctx.drives());
            return AppraisalResult.empty();
        };

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, capturing, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("nothing special"));

        var drives = capturedDrives.get();
        assertThat(drives).isNotNull();
        assertThat(drives).allMatch(d -> d.category() == DriveCategory.BASELINE);
    }

    @Test
    void concernSalienceEmptyBeforeTick() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        assertThat(participant.currentConcernSalience("a1", "t1")).isEmpty();
    }

    @Test
    void computesConcernSalienceFromDriveDescriptions() {
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var cogSgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("Cognitive",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), "t1");
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("scheming", cogSgId)
                                                            .withProperties(Map.of(
                                                                    "cognitiveKind", "drive-intensity",
                                                                    "drive-type", "scheming",
                                                                    "intensity", "0.8",
                                                                    "description", "Compelled to hatch elaborate plans and schemes",
                                                                    "agent-id", "a1")),
                      "t1");

        var config = CognitionConfig.none().with("appraisal", true).with("salience", true);
        var concernStrategy = new io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy();
        var participant = new AppraisalTickParticipant(
                concernStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config, store);

        participant.tick(contextWithObservation("They hatched an elaborate plan to escape"));

        var salience = participant.currentConcernSalience("a1", "t1");
        assertThat(salience).containsKey("scheming");
        assertThat(salience.get("scheming")).isGreaterThan(0.0);
    }

    @Test
    void concernSalienceIncludesActiveGoals() {
        var store = new io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore();
        var goalSgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("Goals",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.GOAL, null), "t1");
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("protect Clara from danger", goalSgId)
                                                            .withProperties(Map.of(
                                                                    "status", "active",
                                                                    "agent-id", "a1")),
                      "t1");

        var config = CognitionConfig.none().with("appraisal", true).with("salience", true);
        var concernStrategy = new io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy();
        var participant = new AppraisalTickParticipant(
                concernStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config, store);

        participant.tick(contextWithObservation("Clara is in terrible danger"));

        var salience = participant.currentConcernSalience("a1", "t1");
        assertThat(salience).containsKey("protect Clara from danger");
        assertThat(salience.get("protect Clara from danger")).isGreaterThan(0.0);
    }


    @Test
    void appliesRelationalBiasingToSituationClassification() {
        var store = new InMemoryMindMapStore();

        var personSgId = store.createSubgraph(
                new SubgraphInput("People", SubgraphTypes.PERSON, null), "t1");
        var targetNodeId = store.addNode(
                NodeInput.of("Target Agent", personSgId)
                        .withProperties(Map.of("agentId", "target1")), "t1");
        var overlayProps = new java.util.HashMap<>(
                new RelationalSchema("target1", "ally", 0.8, 0.5, 0.3, null).toProperties());
        overlayProps.put(OverlayRef.AGENT_ID, "a1");
        var overlayRef = OverlayRef.of(targetNodeId);
        store.addNode(NodeInput.of("overlay-a1-target", personSgId)
                        .withTraits(java.util.Set.of("overlay"))
                        .withRefs(java.util.Set.of(overlayRef))
                        .withProperties(Map.copyOf(overlayProps)), "t1");

        SituationClassifier classifier = (desc, meta, ctx) -> new ClassificationResult(List.of(
                new SituationActivation("secure_attachment", 0.6),
                new SituationActivation("rejection", 0.6),
                new SituationActivation("mastery", 0.5)));

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config, store,
                AppraisalThresholds.defaults(), classifier);

        var contextWithTarget = new CognitionTickContext(
                "a1", "t1", null, (a, t) -> java.util.Set.of(),
                "Someone was kind to me", null, "target1");
        participant.tick(contextWithTarget);
        var biasedWeights = participant.currentChannelWeights("a1", "t1");
        assertThat(biasedWeights).isPresent();

        participant.tick(contextWithObservation("Someone was kind to me"));
        var unbiasedWeights = participant.currentChannelWeights("a1", "t1");
        assertThat(unbiasedWeights).isPresent();

        assertThat(biasedWeights.get()).isNotEqualTo(unbiasedWeights.get());
    }

    @Test
    void noRelationalBiasingWithoutTarget() {
        SituationClassifier classifier = (desc, meta, ctx) -> new ClassificationResult(List.of(
                new SituationActivation("secure_attachment", 0.6),
                new SituationActivation("rejection", 0.6)));

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config, null,
                AppraisalThresholds.defaults(), classifier);

        participant.tick(contextWithObservation("Someone was kind to me"));
        var weights1 = participant.currentChannelWeights("a1", "t1");

        participant.tick(contextWithObservation("Someone was kind to me"));
        var weights2 = participant.currentChannelWeights("a1", "t1");

        assertThat(weights1).isPresent();
        assertThat(weights2).isPresent();
        assertThat(weights1.get()).isEqualTo(weights2.get());
    }

    @Test
    void hoodedClawKindnessFromPreyVsToolProducesDifferentOutputs() {
        var store = new InMemoryMindMapStore();
        var personSgId = store.createSubgraph(
                new SubgraphInput("People", SubgraphTypes.PERSON, null), "t1");

        var preyNodeId = store.addNode(
                NodeInput.of("Prey Agent", personSgId)
                        .withProperties(Map.of("agentId", "prey1")), "t1");
        var preyOverlayProps = new java.util.HashMap<>(
                new RelationalSchema("prey1", "prey", -0.5, 0.0, 0.9, null).toProperties());
        preyOverlayProps.put(OverlayRef.AGENT_ID, "villain");
        store.addNode(NodeInput.of("overlay-villain-prey", personSgId)
                        .withTraits(java.util.Set.of("overlay"))
                        .withRefs(java.util.Set.of(OverlayRef.of(preyNodeId)))
                        .withProperties(Map.copyOf(preyOverlayProps)), "t1");

        var toolNodeId = store.addNode(
                NodeInput.of("Tool Agent", personSgId)
                        .withProperties(Map.of("agentId", "tool1")), "t1");
        var toolOverlayProps = new java.util.HashMap<>(
                new RelationalSchema("tool1", "tool", 0.3, 0.1, 0.8, null).toProperties());
        toolOverlayProps.put(OverlayRef.AGENT_ID, "villain");
        store.addNode(NodeInput.of("overlay-villain-tool", personSgId)
                        .withTraits(java.util.Set.of("overlay"))
                        .withRefs(java.util.Set.of(OverlayRef.of(toolNodeId)))
                        .withProperties(Map.copyOf(toolOverlayProps)), "t1");

        SituationClassifier classifier = (desc, meta, ctx) -> new ClassificationResult(List.of(
                new SituationActivation("secure_attachment", 0.7),
                new SituationActivation("rejection", 0.3),
                new SituationActivation("betrayal", 0.2)));

        var villainProfile = new DriveProfile("villain", "t1",
                Map.of(DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.7, "gaps")),
                0.7, DriveAxis.CURIOSITY, java.time.Instant.now());
        when(driveOrchestrator.currentDrives("villain", "t1")).thenReturn(Optional.of(villainProfile));
        when(moodOrchestrator.currentMood("villain", "t1")).thenReturn(Optional.empty());

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config, store,
                AppraisalThresholds.defaults(), classifier);

        var preyContext = new CognitionTickContext(
                "villain", "t1", null, (a, t) -> java.util.Set.of(),
                "They showed unexpected kindness", null, "prey1");
        participant.tick(preyContext);
        var preyWeights = participant.currentChannelWeights("villain", "t1").orElseThrow();

        var toolContext = new CognitionTickContext(
                "villain", "t1", null, (a, t) -> java.util.Set.of(),
                "They showed unexpected kindness", null, "tool1");
        participant.tick(toolContext);
        var toolWeights = participant.currentChannelWeights("villain", "t1").orElseThrow();

        assertThat(preyWeights).isNotEqualTo(toolWeights);
    }


    @Test
    void capacityFrustration_lateralityGap_producesAngerMoodShift() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        var budget = new io.casehub.neocortex.cognition.search.SearchBudget(
                0.5, 0.5, 0.5, 0.5,
                io.casehub.neocortex.cognition.search.SearchMotivation.CURIOSITY,
                false, true, 0.0, 0.0, 0.3, -1.0, -1.0, -1.0, Instant.now());
        participant.setSearchBudgetAccessor((a, t) -> Optional.of(budget));

        participant.tick(contextWithObservation("Something interesting"));

        verify(moodOrchestrator, Mockito.atLeast(1)).record(any(), eq("a1"), eq("t1"));
    }

    @Test
    void capacityFrustration_noGap_noExtraMoodShift() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        var budget = new io.casehub.neocortex.cognition.search.SearchBudget(
                0.5, 0.5, 0.5, 0.5,
                io.casehub.neocortex.cognition.search.SearchMotivation.CURIOSITY,
                false, false, 0.0, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        participant.setSearchBudgetAccessor((a, t) -> Optional.of(budget));

        participant.tick(contextWithObservation("Something boring"));

        verify(moodOrchestrator, never()).record(any(), any(), any());
    }

    @Test
    void capacityFrustration_depthGap_producesDisappointment() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        var budget = new io.casehub.neocortex.cognition.search.SearchBudget(
                0.5, 0.5, 0.5, 0.5,
                io.casehub.neocortex.cognition.search.SearchMotivation.CURIOSITY,
                false, true, 0.2, 0.0, 0.0, -1.0, -1.0, -1.0, Instant.now());
        participant.setSearchBudgetAccessor((a, t) -> Optional.of(budget));

        participant.tick(contextWithObservation("Complex reasoning required"));

        verify(moodOrchestrator, Mockito.atLeast(1)).record(any(), eq("a1"), eq("t1"));
    }

    @Test
    void capacityFrustration_multipleGaps_bridgesAllToMood() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        var budget = new io.casehub.neocortex.cognition.search.SearchBudget(
                0.5, 0.5, 0.5, 0.5,
                io.casehub.neocortex.cognition.search.SearchMotivation.CURIOSITY,
                false, true, 0.15, 0.1, 0.25, -1.0, -1.0, -1.0, Instant.now());
        participant.setSearchBudgetAccessor((a, t) -> Optional.of(budget));

        participant.tick(contextWithObservation("Ambitious exploration"));

        verify(moodOrchestrator, Mockito.atLeast(1)).record(any(), eq("a1"), eq("t1"));
    }

    @Test
    void capacityFrustration_noAccessor_noFrustration() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, driveOrchestrator,
                moodOrchestrator, null, config);

        participant.tick(contextWithObservation("Something interesting"));

        verify(moodOrchestrator, never()).record(any(), any(), any());
    }

    private CognitionTickContext contextWithObservation(String observation) {
        return new CognitionTickContext("a1", "t1", null, (a, t) -> java.util.Set.of(), observation);
    }
}
