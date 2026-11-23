package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.regulation.RegulationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

class CognitionCoreRegulationContextTest {

    @Test
    void tickWithRegulationContextPassesItToParticipants() {
        var config = CognitionConfig.none();
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
        var core = new CognitionCore(mood, drives, null, null, null, null, null, noOpHygiene);

        var capturedContext = new AtomicReference<CognitionTickContext>();
        core.addParticipant(CognitionPhase.FOUNDATION, ctx -> capturedContext.set(ctx));

        var regCtx = new RegulationContext("library", List.of("james"), "investigation");
        core.tick("agent1", "tenant1", null, (a, t) -> Set.of(),
                  "observation", null, regCtx);

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().regulationContext()).isNotNull();
        assertThat(capturedContext.get().regulationContext().location()).isEqualTo("library");
        assertThat(capturedContext.get().regulationContext().interactingAgents())
                .containsExactly("james");
    }

    @Test
    void tickWithoutRegulationContextPassesNull() {
        var config = CognitionConfig.none();
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
        var core = new CognitionCore(mood, drives, null, null, null, null, null, noOpHygiene);

        var capturedContext = new AtomicReference<CognitionTickContext>();
        core.addParticipant(CognitionPhase.FOUNDATION, ctx -> capturedContext.set(ctx));

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of(),
                  "observation", null);

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().regulationContext()).isNull();
    }
}
