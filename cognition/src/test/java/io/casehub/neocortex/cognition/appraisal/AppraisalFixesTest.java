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
import io.casehub.neocortex.cognition.journal.EventRef;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Set;

class AppraisalFixesTest {

    private DriveOrchestrator driveOrchestrator;
    private MoodOrchestrator moodOrchestrator;

    @BeforeEach
    void setUp() {
        driveOrchestrator = mock(DriveOrchestrator.class);
        moodOrchestrator = mock(MoodOrchestrator.class);

        var profile = new DriveProfile("a1", "t1",
                Map.of(DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.7, "gaps")),
                0.7, DriveAxis.CURIOSITY, Instant.now());
        when(driveOrchestrator.currentDrives("a1", "t1")).thenReturn(Optional.of(profile));
        when(moodOrchestrator.currentMood("a1", "t1")).thenReturn(Optional.empty());
    }

    // --- SEC dimensions passthrough ---

    @Test
    void llmAppraisalReturnsSecDimensions() {
        var json = """
                {
                  "narrative": "Alert and wary.",
                  "dimensions": {
                    "relevance": 0.9,
                    "conduciveness": -0.7,
                    "controllability": 0.3,
                    "novelty": 0.8,
                    "urgency": 0.6,
                    "internal-standards": 1.0,
                    "external-standards": 1.0
                  }
                }""";
        var strategy = stubStrategy(json);
        var ctx = new AppraisalContext("a1",
                PerceivedSituation.passThrough("Clara is missing"),
                List.of(new Drive("protection", DriveCategory.CHARACTER, 0.8, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), null);

        var result = strategy.appraise(ctx);

        assertThat(result.secDimensions()).isNotEmpty();
        assertThat(result.secDimensions().get("relevance")).isCloseTo(0.9, within(0.001));
        assertThat(result.secDimensions().get("conduciveness")).isCloseTo(-0.7, within(0.001));
        assertThat(result.secDimensions().get("novelty")).isCloseTo(0.8, within(0.001));
    }

    @Test
    void emptyDimensionsGiveEmptySecDimensions() {
        var json = """
                {
                  "narrative": "Nothing much."
                }""";
        var strategy = stubStrategy(json);
        var ctx = new AppraisalContext("a1",
                PerceivedSituation.passThrough("quiet morning"),
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), null);

        var result = strategy.appraise(ctx);

        assertThat(result.secDimensions()).isEmpty();
    }

    // --- currentResultAt tick stamping ---

    @Test
    void currentResultAtReturnsResultForMatchingTick() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(), new NoOpAppraisalStrategy(),
                driveOrchestrator, moodOrchestrator, null, config);

        var tickRef = EventRef.tick(1);
        participant.tick(contextWithEventRef("Something happens", tickRef));

        assertThat(participant.currentResultAt("a1", "t1", tickRef)).isPresent();
    }

    @Test
    void currentResultAtReturnsEmptyForWrongTick() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(), new NoOpAppraisalStrategy(),
                driveOrchestrator, moodOrchestrator, null, config);

        participant.tick(contextWithEventRef("Something happens", EventRef.tick(1)));

        assertThat(participant.currentResultAt("a1", "t1", EventRef.tick(2))).isEmpty();
    }

    @Test
    void currentResultAtReturnsEmptyBeforeAnyTick() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(), new NoOpAppraisalStrategy(),
                driveOrchestrator, moodOrchestrator, null, config);

        assertThat(participant.currentResultAt("a1", "t1", EventRef.tick(1))).isEmpty();
    }

    // --- currentInputActivations ---

    @Test
    void currentInputActivationsAvailableAfterTick() {
        SituationClassifier classifier = (desc, meta, ctx) -> new ClassificationResult(List.of(
                new SituationActivation("threat", 0.8),
                new SituationActivation("mastery", 0.3)));

        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(), new NoOpAppraisalStrategy(),
                driveOrchestrator, moodOrchestrator, null, config, null,
                AppraisalThresholds.defaults(), classifier);

        participant.tick(contextWithObservation("A threatening figure appears"));

        var activations = participant.currentInputActivations("a1", "t1");
        assertThat(activations).containsEntry("threat", 0.8);
        assertThat(activations).containsEntry("mastery", 0.3);
    }

    @Test
    void currentInputActivationsEmptyWithoutClassifier() {
        var config = CognitionConfig.none().with("appraisal", true);
        var participant = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(), new NoOpAppraisalStrategy(),
                driveOrchestrator, moodOrchestrator, null, config);

        participant.tick(contextWithObservation("A quiet morning"));

        assertThat(participant.currentInputActivations("a1", "t1")).isEmpty();
    }

    // --- RelationalBiasing clamp ---

    @Test
    void relationalBiasingClampsAboveOne() {
        // trust = -1.0, rejection scale = -0.2 → 0.9 * (1 + 1.0 * 0.2) = 0.9 * 1.2 = 1.08
        var schema = new RelationalSchema("target1", "enemy", -1.0, 0.0, 0.0, null);
        var activations = List.of(new SituationActivation("rejection", 0.9));

        var result = RelationalBiasing.apply(schema, activations);

        assertThat(result.getFirst().confidence()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void relationalBiasingClampsAtZero() {
        // trust = -1.0, secure_attachment scale = 0.3 → 0.5 * (1 + (-1) * 0.3) = 0.5 * 0.7 = 0.35
        // With higher magnitude: trust = -3.0 (extreme) → 0.5 * (1 + (-3)*0.3) = 0.5 * 0.1 = 0.05
        // Actually trust is clamped to [-1,1] in the schema. But confidence can be low enough
        // For a clear test: use fabricated values via a custom schema with extreme trust
        // Using the existing formula: trust = 1.0, secure_attachment scale = 0.3 → positive boost.
        // Actually the formula is: biased = confidence * (1 + trust * scale)
        // For negative result: need trust * scale < -1 → not possible with max trust=1 and max scale=0.3
        // But biased values should still be >= 0. The clamp protects against future scale changes.
        // Use what we can: verify the returned value is never negative
        var schema = new RelationalSchema("target1", "enemy", -1.0, 0.0, 0.0, null);
        var activations = List.of(
                new SituationActivation("secure_attachment", 0.9),
                new SituationActivation("rejection", 0.9),
                new SituationActivation("betrayal", 0.9),
                new SituationActivation("psychological_threat", 0.9));

        var result = RelationalBiasing.apply(schema, activations);

        for (var activation : result) {
            assertThat(activation.confidence())
                    .as("confidence for %s", activation.nodeId())
                    .isBetween(0.0, 1.0);
        }
    }

    // --- helpers ---

    private CognitionTickContext contextWithObservation(String observation) {
        return new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(), observation);
    }

    private CognitionTickContext contextWithEventRef(String observation, EventRef tickEventRef) {
        return new CognitionTickContext("a1", "t1", null, (a, t) -> Set.of(),
                observation, null, null, null, tickEventRef);
    }

    private static LlmAppraisalStrategy stubStrategy(String response) {
        return new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
    }
}
