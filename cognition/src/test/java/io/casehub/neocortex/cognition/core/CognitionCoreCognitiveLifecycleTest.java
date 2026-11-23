package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.prompt.CognitiveLifecycle;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.cognition.drive.AffiliationDrive;
import io.casehub.neocortex.cognition.drive.AutonomyDrive;
import io.casehub.neocortex.cognition.drive.CompetenceDrive;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.memory.MemoryHygieneOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.memory.KnowledgeGapSummary;
import java.time.Duration;

class CognitionCoreCognitiveLifecycleTest {

    private static CognitionCore createMinimalCore() {
        var mood = new MoodOrchestrator(
                MoodConfig.defaults());
        var noOpHygiene = new MemoryHygieneOrchestrator() {
            @Override public void tick(String a, String t) {}
            @Override public KnowledgeGapSummary knowledgeGaps(String a, String t) {
                return KnowledgeGapSummary.empty();
            }
        };
        var drives = new DriveOrchestrator(
                new CuriosityDrive(null, null, 0.0),
                new CompetenceDrive(null),
                new AffiliationDrive(null, 0.3, Duration.ofHours(1)),
                new AutonomyDrive(null, 0.5),
                mood,
                new DriveComposer(),
                DriveConfig.defaults());
        return new CognitionCore(mood, drives, null, null, null, null, null, noOpHygiene);
    }

    @Test
    void implementsCognitiveLifecycle() {
        assertThat(CognitiveLifecycle.class).isAssignableFrom(CognitionCore.class);
    }

    @Test
    void lifecycleTickDelegatesToCoreTick() {
        var core = createMinimalCore();

        CognitiveLifecycle lifecycle = core;
        lifecycle.tick("agent-1", "tenant-1", Set.of());

        var sections = lifecycle.promptSections();
        assertThat(sections).isNotNull();
    }

    @Test
    void lifecycleRecordInteractionDelegatesToCore() {
        var core = createMinimalCore();
        core.tick("agent-1", "tenant-1", null, (aid, tid) -> Set.of());

        CognitiveLifecycle lifecycle = core;
        lifecycle.recordInteraction("agent-1", "tenant-1", null, "hello", "hi there");
    }
}
