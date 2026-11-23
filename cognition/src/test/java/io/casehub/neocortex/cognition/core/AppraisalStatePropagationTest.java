package io.casehub.neocortex.cognition.core;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveIntensity;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveSource;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.cognition.prompt.MoodPromptSection;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import io.casehub.neocortex.cognition.appraisal.EmotionMapper;
import io.casehub.neocortex.cognition.appraisal.SecDimensions;
import io.casehub.neocortex.cognition.appraisal.SecResult;
import java.util.Map;

class AppraisalStatePropagationTest {

    @Test
    void moodSignalNotVisibleUntilReTick() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        mood.record(new MoodSignal.InteractionAppraisal(0, 0, 0, "init"), "a1", "t1");
        mood.tick("a1", "t1");

        assertThat(mood.currentMood("a1", "t1").orElseThrow().pleasure())
                .isCloseTo(0, offset(0.01));

        mood.record(new MoodSignal.DirectShift(-0.5, 0.6, -0.3, "appraisal-emotions"), "a1", "t1");

        assertThat(mood.currentMood("a1", "t1").orElseThrow().pleasure())
                .isCloseTo(0, offset(0.01));

        mood.tick("a1", "t1");
        var updated = mood.currentMood("a1", "t1").orElseThrow();
        assertThat(updated.pleasure()).isLessThan(-0.2);
        assertThat(updated.arousal()).isGreaterThan(0.2);
    }

    @Test
    void moodPromptSectionRendersAppraisalAffectedState() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        mood.record(new MoodSignal.InteractionAppraisal(0, 0, 0, "init"), "a1", "t1");
        mood.tick("a1", "t1");

        var section = new MoodPromptSection(mood);
        var ctx = new CognitionRenderContext("a1", "t1", null);

        assertThat(section.render(ctx)).contains("emotionally even");

        mood.record(new MoodSignal.DirectShift(-0.5, 0.6, -0.3, "appraisal-emotions"), "a1", "t1");
        mood.tick("a1", "t1");

        var rendered = section.render(ctx);
        assertThat(rendered).doesNotContain("emotionally even");
        assertThat(rendered).contains("tense");
    }

    @Test
    void appraisalDriveModulationAppliedOnReTick() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        mood.record(new MoodSignal.InteractionAppraisal(0, 0, 0, "init"), "a1", "t1");
        mood.tick("a1", "t1");

        DriveSource compStub = (a, t) -> new DriveIntensity(DriveAxis.COMPETENCE, 0.1, "stub");
        DriveSource affStub  = (a, t) -> new DriveIntensity(DriveAxis.AFFILIATION, 0.1, "stub");
        DriveSource autStub  = (a, t) -> new DriveIntensity(DriveAxis.AUTONOMY, 0.1, "stub");
        DriveSource curStub  = (a, t) -> new DriveIntensity(DriveAxis.CURIOSITY, 0.1, "stub");
        var drives = new DriveOrchestrator(curStub, compStub, affStub, autStub, mood,
                new DriveComposer(), DriveConfig.defaults());

        var descriptor = mock(AgentDescriptor.class);
        drives.tick("a1", "t1", descriptor);
        var baseline = drives.currentDrives("a1", "t1").orElseThrow()
                .drives().get(DriveAxis.COMPETENCE).intensity();

        var modulation = Map.of(
                DriveAxis.COMPETENCE, 0.15,
                DriveAxis.CURIOSITY, 0.0,
                DriveAxis.AFFILIATION, 0.0,
                DriveAxis.AUTONOMY, 0.0);
        drives.recordAppraisalModulation("a1", "t1", modulation);
        drives.tick("a1", "t1", descriptor);

        var updated = drives.currentDrives("a1", "t1").orElseThrow()
                .drives().get(DriveAxis.COMPETENCE).intensity();

        assertThat(updated).isGreaterThan(baseline);
        assertThat(updated).isCloseTo(baseline + 0.15, offset(0.05));
    }

    @Test
    void drivesShiftWhenMoodUpdatedFromAppraisal() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        mood.record(new MoodSignal.InteractionAppraisal(0, 0, 0, "init"), "a1", "t1");
        mood.tick("a1", "t1");

        DriveSource stub = (agentId, tenantId) -> new DriveIntensity(DriveAxis.CURIOSITY, 0.5, "stub");
        var drives = new DriveOrchestrator(stub, stub, stub, stub, mood,
                new DriveComposer(), DriveConfig.defaults());

        var descriptor = mock(AgentDescriptor.class);
        drives.tick("a1", "t1", descriptor);
        var baseline = drives.currentDrives("a1", "t1").orElseThrow()
                .drives().get(DriveAxis.CURIOSITY).intensity();

        mood.record(new MoodSignal.DirectShift(-0.5, 0.6, -0.3, "appraisal-emotions"), "a1", "t1");
        mood.tick("a1", "t1");
        drives.tick("a1", "t1", descriptor);

        var updated = drives.currentDrives("a1", "t1").orElseThrow()
                .drives().get(DriveAxis.CURIOSITY).intensity();

        assertThat(updated).isNotEqualTo(baseline);
    }

    @Test
    void harnessTickPropagatesAppraisalToDrives() {
        var emotion = new CognitiveEmotion(EmotionType.ANGER, 0.5, "obstacle",
                Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.5, 0.3));
        var appraisal = new AppraisalResult(
                List.of(emotion), List.of(), HabituationState.empty(), "moderate frustration");

        var config = CognitionConfig.all().with("regulation", true).with("appraisal", true);
        var harness = CognitionTestHarness.builder()
                .withCognitionConfig(config)
                .agentFromAxes("a1", "cooperative", "flexible", "moderate", "collaborative", "analytical")
                .withAppraisalResult("a1", appraisal)
                .build();

        harness.observe("a1", "test-tenant", "An obstacle is blocking progress");
        harness.tick("a1", "test-tenant");

        var trace = harness.currentTrace("a1", "test-tenant");
        assertThat(trace).isPresent();
        var sections = trace.get().promptSections();
        var driveSection = sections.get("DrivePromptSection");
        assertThat(driveSection).as("DrivePromptSection should be present").isNotNull();
        assertThat(driveSection).as("ANGER should boost competence above stub baseline")
                .doesNotContain("Competence: 0.1");
    }

    @Test
    void highNoveltyAppraisalDrivesCuriosityViaHope() {
        var secResults = List.of(
                SecResult.of("relevance",
                                                                      SecDimensions.RELEVANCE, 0.7,
                                                                      SecDimensions.NOVELTY, 0.9),
                SecResult.of("implication",
                                                                      SecDimensions.CONDUCIVENESS, 0.1));

        var emotions = EmotionMapper.mapEmotions(secResults, "novel-discovery");
        assertThat(emotions).extracting("type").contains(EmotionType.HOPE);

        var hopeEmotion = emotions.stream()
                                  .filter(e -> e.type() == EmotionType.HOPE).findFirst().orElseThrow();
        assertThat(hopeEmotion.intensity()).as("HOPE intensity from relevance*novelty").isGreaterThan(0.5);

        var appraisal = new AppraisalResult(emotions, List.of(), HabituationState.empty(), "high novelty curiosity");

        var config = CognitionConfig.all().with("regulation", true).with("appraisal", true);
        var harness = CognitionTestHarness.builder()
                                          .withCognitionConfig(config)
                                          .agentFromAxes("a1", "cooperative", "flexible", "moderate", "collaborative", "analytical")
                                          .withAppraisalResult("a1", appraisal)
                                          .build();

        harness.observe("a1", "test-tenant", "A completely novel phenomenon appears");
        harness.tick("a1", "test-tenant");

        var trace = harness.currentTrace("a1", "test-tenant");
        assertThat(trace).isPresent();
        var driveSection = trace.get().promptSections().get("DrivePromptSection");
        assertThat(driveSection).as("DrivePromptSection should be present").isNotNull();
        assertThat(driveSection)
                .as("High-novelty HOPE should override curiosity trigger text")
                .contains("elevated by HOPE");
        assertThat(driveSection)
                .as("Original 'no knowledge gaps' trigger should be replaced")
                .doesNotContain("no knowledge gaps");
    }

    @Test
    void appraisalMoodBridgeProducesProportionalShift() {
        var emotion = new CognitiveEmotion(EmotionType.FEAR, 0.6, "threat",
                Instant.now(), EmotionSource.INTRINSIC, new PadProjection(-0.5, 0.5, -0.3));
        var appraisal = new AppraisalResult(
                List.of(emotion), List.of(), HabituationState.empty(), "danger detected");

        var config = CognitionConfig.all().with("regulation", true).with("appraisal", true);
        var harness = CognitionTestHarness.builder()
                .withCognitionConfig(config)
                .agentFromAxes("a1", "cooperative", "flexible", "moderate", "collaborative", "analytical")
                .withAppraisalResult("a1", appraisal)
                .build();

        harness.observe("a1", "test-tenant", "A threatening situation");
        harness.tick("a1", "test-tenant");

        var mood = harness.currentMood("a1", "test-tenant").orElseThrow();
        assertThat(mood.pleasure())
                .as("Single bridge: mood pleasure should reflect PAD projection, not 2x")
                .isBetween(-0.8, -0.1);
    }


}
