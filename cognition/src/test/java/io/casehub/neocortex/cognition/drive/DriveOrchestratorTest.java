package io.casehub.neocortex.cognition.drive;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriveOrchestratorTest {

    private static final String AGENT = "agent-1";
    private static final String TENANT = "tenant-1";

    @Test
    void evictIdleRemovesStaleEntries() {
        var moodOrch = new MoodOrchestrator(MoodConfig.defaults());

        DriveSource curiosity = (a, t) -> new DriveIntensity(DriveAxis.CURIOSITY, 0.6, "gap");
        DriveSource competence = (a, t) -> new DriveIntensity(DriveAxis.COMPETENCE, 0.3, "task");
        DriveSource affiliation = (a, t) -> new DriveIntensity(DriveAxis.AFFILIATION, 0.2, "bond");
        DriveSource autonomy = (a, t) -> new DriveIntensity(DriveAxis.AUTONOMY, 0.4, "choice");

        var orchestrator = new DriveOrchestrator(
                curiosity, competence, affiliation, autonomy,
                moodOrch, new DriveComposer(), DriveConfig.defaults());

        var descriptor = mock(AgentDescriptor.class);
        when(descriptor.agentId()).thenReturn(AGENT);
        when(descriptor.disposition()).thenReturn(null);

        orchestrator.tick(AGENT, TENANT, descriptor);
        assertThat(orchestrator.currentDrives(AGENT, TENANT)).isPresent();

        orchestrator.evictIdle(Duration.ZERO);

        assertThat(orchestrator.currentDrives(AGENT, TENANT)).isEmpty();
    }
}
