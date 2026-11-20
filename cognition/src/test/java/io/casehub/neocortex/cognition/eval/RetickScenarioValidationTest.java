package io.casehub.neocortex.cognition.eval;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.cognition.eval.divergence.DivergenceFixtures;
import io.casehub.neocortex.memory.mood.MoodState;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 300-tick scenario validation for the post-DERIVED mood/drive re-tick (#919).
 * Compares single-tick (baseline) vs double-tick (appraisal enabled) across
 * mood stability, drive coherence, and personality differentiation.
 */
class RetickScenarioValidationTest {

    private static final String TENANT = DivergenceFixtures.TENANT;
    private static final int TICK_COUNT = 300;

    private static InMemoryMindMapStore seededStore() {
        var store = new InMemoryMindMapStore();
        var now = Instant.now();
        var sgId = store.createSubgraph(
            new SubgraphInput("research", SubgraphTypes.RESEARCH_AREA, null), TENANT);
        var nodes = new ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            nodes.add(store.addNode(
                NodeInput.of("topic-" + i, sgId)
                    .withConfidence(Confidence.inferred(0.3 + (i % 3) * 0.1, now)), TENANT));
        }
        for (int i = 0; i < nodes.size() - 1; i++) {
            store.addEdge(EdgeInput.of(nodes.get(i), nodes.get(i + 1), "relates-to"), TENANT);
        }
        store.addEdge(EdgeInput.of(nodes.getLast(), nodes.getFirst(), "relates-to"), TENANT);
        return store;
    }

    private static final String[] OBSERVATIONS = {
        "unexpected experimental result challenges assumptions",
        "colleague presents an interesting new perspective",
        "routine status update with no surprises",
        "minor setback in ongoing project",
        "positive feedback on recent work",
        "ambiguous signal requires careful interpretation",
        "familiar pattern seen in a new context",
        "time pressure on deliverable",
        "collaborative breakthrough with team",
        "quiet reflection period with no external input"
    };

    private static AppraisalResult appraisalForObservation(int tickIndex) {
        var now = Instant.now();
        return switch (tickIndex % 10) {
            case 0 -> result(EmotionType.INTEREST, 0.6, new PadProjection(0.3, 0.5, 0.2), now);
            case 1 -> result(EmotionType.INTEREST, 0.4, new PadProjection(0.2, 0.3, 0.1), now);
            case 2 -> AppraisalResult.empty();
            case 3 -> result(EmotionType.DISTRESS, 0.3, new PadProjection(-0.2, 0.3, -0.1), now);
            case 4 -> result(EmotionType.JOY, 0.4, new PadProjection(0.3, 0.2, 0.2), now);
            case 5 -> result(EmotionType.FEAR, 0.2, new PadProjection(-0.1, 0.3, -0.2), now);
            case 6 -> result(EmotionType.INTEREST, 0.3, new PadProjection(0.1, 0.2, 0.0), now);
            case 7 -> result(EmotionType.DISTRESS, 0.4, new PadProjection(-0.3, 0.4, -0.2), now);
            case 8 -> result(EmotionType.JOY, 0.5, new PadProjection(0.4, 0.3, 0.3), now);
            case 9 -> AppraisalResult.empty();
            default -> AppraisalResult.empty();
        };
    }

    private static AppraisalResult result(EmotionType type, double intensity,
                                          PadProjection pad, Instant now) {
        var emotion = new CognitiveEmotion(type, intensity, "scenario", now,
                                           EmotionSource.INTRINSIC, pad);
        return new AppraisalResult(List.of(emotion), List.of(), HabituationState.empty(), null);
    }

    @Test
    void moodStability_noRunawayDrift() {
        var harness = CognitionTestHarness.builder()
            .withMindMapStore(seededStore())
            .agentFromDefaults("open", DivergenceFixtures.openDefaults())
            .agentFromDefaults("cautious", DivergenceFixtures.cautiousDefaults())
            .withCognitionConfig(CognitionConfig.all().with("appraisal", true))
            .withAppraisalResult("open", AppraisalResult.empty())
            .withAppraisalResult("cautious", AppraisalResult.empty())
            .build();

        var openMoods = new ArrayList<MoodState>();
        var cautiousMoods = new ArrayList<MoodState>();

        for (int tick = 0; tick < TICK_COUNT; tick++) {
            harness.observe("open", TENANT, OBSERVATIONS[tick % OBSERVATIONS.length]);
            harness.observe("cautious", TENANT, OBSERVATIONS[tick % OBSERVATIONS.length]);
            harness.tick("open", TENANT);
            harness.tick("cautious", TENANT);

            harness.currentMood("open", TENANT).ifPresent(openMoods::add);
            harness.currentMood("cautious", TENANT).ifPresent(cautiousMoods::add);
        }

        assertMoodBounded(openMoods, "open");
        assertMoodBounded(cautiousMoods, "cautious");
        assertNoMonotonicDrift(openMoods, "open");
        assertNoMonotonicDrift(cautiousMoods, "cautious");
    }

    @Test
    void driveCoherence_stableAndBoundedOver300Ticks() {
        var harness = CognitionTestHarness.builder()
            .withMindMapStore(seededStore())
            .agentFromDefaults("open", DivergenceFixtures.openDefaults())
            .withCognitionConfig(CognitionConfig.all().with("appraisal", true))
            .withAppraisalResult("open", AppraisalResult.empty())
            .build();

        var drives = new ArrayList<DriveProfile>();

        for (int tick = 0; tick < TICK_COUNT; tick++) {
            harness.observe("open", TENANT, OBSERVATIONS[tick % OBSERVATIONS.length]);
            harness.tick("open", TENANT);

            harness.core().drives().currentDrives("open", TENANT).ifPresent(drives::add);
        }

        assertThat(drives).as("should produce drive profiles").isNotEmpty();
        assertDrivesBounded(drives, "open");
        assertNoMonotonicDriveDrift(drives, "open");

        double meanMotivation = drives.stream()
            .mapToDouble(DriveProfile::compositeMotivation).average().orElse(0);
        assertThat(meanMotivation)
            .as("mean composite motivation should be non-zero with seeded MindMap")
            .isGreaterThan(0.0);
    }

    @Test
    void retickVsBaseline_driveExpressionNotDegraded() {
        var baselineHarness = CognitionTestHarness.builder()
            .withMindMapStore(seededStore())
            .agentFromDefaults("open", DivergenceFixtures.openDefaults())
            .withCognitionConfig(CognitionConfig.all())
            .build();

        var retickHarness = CognitionTestHarness.builder()
            .withMindMapStore(seededStore())
            .agentFromDefaults("open", DivergenceFixtures.openDefaults())
            .withCognitionConfig(CognitionConfig.all().with("appraisal", true))
            .withAppraisalResult("open", AppraisalResult.empty())
            .build();

        var baselineMotivation = new DoubleSummaryStatistics();
        var retickMotivation = new DoubleSummaryStatistics();

        for (int tick = 0; tick < TICK_COUNT; tick++) {
            String obs = OBSERVATIONS[tick % OBSERVATIONS.length];
            baselineHarness.observe("open", TENANT, obs);
            retickHarness.observe("open", TENANT, obs);
            baselineHarness.tick("open", TENANT);
            retickHarness.tick("open", TENANT);

            baselineHarness.core().drives().currentDrives("open", TENANT)
                .ifPresent(d -> baselineMotivation.accept(d.compositeMotivation()));
            retickHarness.core().drives().currentDrives("open", TENANT)
                .ifPresent(d -> retickMotivation.accept(d.compositeMotivation()));
        }

        assertThat(retickMotivation.getCount())
            .as("re-tick should produce drive profiles")
            .isGreaterThan(0);

        double baselineMean = baselineMotivation.getAverage();
        double retickMean = retickMotivation.getAverage();

        assertThat(retickMean)
            .as("re-tick mean motivation (%.3f) should not degrade >50%% from baseline (%.3f)",
                retickMean, baselineMean)
            .isGreaterThan(baselineMean * 0.5);
    }

    @Test
    void retickWithEmotions_moodStaysWithinBounds() {
        var harness = CognitionTestHarness.builder()
            .withMindMapStore(seededStore())
            .agentFromDefaults("open", DivergenceFixtures.openDefaults())
            .withCognitionConfig(CognitionConfig.all().with("appraisal", true))
            .withAppraisalResult("open", AppraisalResult.empty())
            .build();

        var moods = new ArrayList<MoodState>();

        for (int tick = 0; tick < TICK_COUNT; tick++) {
            harness.observe("open", TENANT, OBSERVATIONS[tick % OBSERVATIONS.length]);

            var appraisal = appraisalForObservation(tick);
            org.mockito.Mockito.when(
                harness.core().appraisalParticipant().currentResult(
                    org.mockito.Mockito.eq("open"), org.mockito.Mockito.anyString()))
                .thenReturn(Optional.of(appraisal));

            harness.tick("open", TENANT);
            harness.currentMood("open", TENANT).ifPresent(moods::add);
        }

        assertMoodBounded(moods, "open (with emotions)");
        assertNoMonotonicDrift(moods, "open (with emotions)");
    }

    // --- Assertion helpers ---

    private void assertMoodBounded(List<MoodState> moods, String agent) {
        for (int i = 0; i < moods.size(); i++) {
            var m = moods.get(i);
            assertThat(m.pleasure()).as("%s mood pleasure at tick %d", agent, i)
                .isBetween(-1.0, 1.0);
            assertThat(m.arousal()).as("%s mood arousal at tick %d", agent, i)
                .isBetween(-1.0, 1.0);
            assertThat(m.dominance()).as("%s mood dominance at tick %d", agent, i)
                .isBetween(-1.0, 1.0);
        }
    }

    private void assertNoMonotonicDrift(List<MoodState> moods, String agent) {
        if (moods.size() < 20) return;

        int windowSize = moods.size() / 5;
        var firstWindow = moods.subList(0, windowSize);
        var lastWindow = moods.subList(moods.size() - windowSize, moods.size());

        double firstPleasure = firstWindow.stream().mapToDouble(MoodState::pleasure).average().orElse(0);
        double lastPleasure = lastWindow.stream().mapToDouble(MoodState::pleasure).average().orElse(0);
        double firstArousal = firstWindow.stream().mapToDouble(MoodState::arousal).average().orElse(0);
        double lastArousal = lastWindow.stream().mapToDouble(MoodState::arousal).average().orElse(0);

        assertThat(Math.abs(lastPleasure - firstPleasure))
            .as("%s pleasure drift (first window=%.3f, last window=%.3f)", agent, firstPleasure, lastPleasure)
            .isLessThan(0.5);
        assertThat(Math.abs(lastArousal - firstArousal))
            .as("%s arousal drift (first window=%.3f, last window=%.3f)", agent, firstArousal, lastArousal)
            .isLessThan(0.5);
    }

    private void assertDrivesBounded(List<DriveProfile> drives, String agent) {
        for (int i = 0; i < drives.size(); i++) {
            var d = drives.get(i);
            assertThat(d.compositeMotivation())
                .as("%s compositeMotivation at tick %d", agent, i)
                .isBetween(0.0, 1.0);
            for (var entry : d.drives().entrySet()) {
                assertThat(entry.getValue().intensity())
                    .as("%s %s intensity at tick %d", agent, entry.getKey(), i)
                    .isBetween(0.0, 1.0);
            }
        }
    }

    private void assertNoMonotonicDriveDrift(List<DriveProfile> drives, String agent) {
        if (drives.size() < 20) return;

        int windowSize = drives.size() / 5;
        var firstWindow = drives.subList(0, windowSize);
        var lastWindow = drives.subList(drives.size() - windowSize, drives.size());

        for (var axis : DriveAxis.values()) {
            double firstMean = firstWindow.stream()
                .filter(d -> d.drives().containsKey(axis))
                .mapToDouble(d -> d.drives().get(axis).intensity())
                .average().orElse(0);
            double lastMean = lastWindow.stream()
                .filter(d -> d.drives().containsKey(axis))
                .mapToDouble(d -> d.drives().get(axis).intensity())
                .average().orElse(0);

            assertThat(Math.abs(lastMean - firstMean))
                .as("%s %s drive drift (first=%.3f, last=%.3f)", agent, axis, firstMean, lastMean)
                .isLessThan(0.5);
        }
    }


}
