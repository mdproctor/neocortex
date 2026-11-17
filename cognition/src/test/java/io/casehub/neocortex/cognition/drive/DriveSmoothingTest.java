package io.casehub.neocortex.cognition.drive;

import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriveSmoothingTest {

    private static DriveSource constant(double value) {
        return (agentId, tenantId) -> new DriveIntensity(DriveAxis.CURIOSITY, value, "test");
    }

    private static DriveSource forAxis(DriveAxis axis, double value) {
        return (agentId, tenantId) -> new DriveIntensity(axis, value, "test");
    }

    @Test
    void deactivation_isSmoothed_notInstant() {
        var moodOrch = mock(MoodOrchestrator.class);
        when(moodOrch.currentMood(any(), any())).thenReturn(Optional.empty());
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var descriptor = mock(io.casehub.eidos.api.AgentDescriptor.class);

        var highSource = new DriveSource[]{forAxis(DriveAxis.CURIOSITY, 0.8)};
        DriveSource curiosity = (a, t) -> highSource[0].evaluate(a, t);
        var orchestrator = new DriveOrchestrator(
                curiosity,
                forAxis(DriveAxis.COMPETENCE, 0.3),
                forAxis(DriveAxis.AFFILIATION, 0.3),
                forAxis(DriveAxis.AUTONOMY, 0.3),
                moodOrch, new DriveComposer(), DriveConfig.defaults(), clock);

        // Tick 1: establish high curiosity
        orchestrator.tick("a", "t", descriptor);
        double afterHigh = orchestrator.currentDrives("a", "t")
                .map(p -> p.drives().get(DriveAxis.CURIOSITY).intensity()).orElse(0.0);
        assertThat(afterHigh).isGreaterThan(0.7);

        // Tick 2: curiosity source drops to 0.1
        highSource[0] = forAxis(DriveAxis.CURIOSITY, 0.1);
        orchestrator.tick("a", "t", descriptor);
        double afterDrop = orchestrator.currentDrives("a", "t")
                .map(p -> p.drives().get(DriveAxis.CURIOSITY).intensity()).orElse(0.0);

        // Should be smoothed: not 0.1 (instant), but somewhere between 0.1 and previous
        assertThat(afterDrop).as("Deactivation should be smoothed, not instant")
                .isGreaterThan(0.3);
        assertThat(afterDrop).as("Should still have decreased")
                .isLessThan(afterHigh);
    }

    @Test
    void activation_isInstant_notSmoothed() {
        var moodOrch = mock(MoodOrchestrator.class);
        when(moodOrch.currentMood(any(), any())).thenReturn(Optional.empty());
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var descriptor = mock(io.casehub.eidos.api.AgentDescriptor.class);

        var source = new DriveSource[]{forAxis(DriveAxis.CURIOSITY, 0.2)};
        DriveSource curiosity = (a, t) -> source[0].evaluate(a, t);
        var orchestrator = new DriveOrchestrator(
                curiosity,
                forAxis(DriveAxis.COMPETENCE, 0.3),
                forAxis(DriveAxis.AFFILIATION, 0.3),
                forAxis(DriveAxis.AUTONOMY, 0.3),
                moodOrch, new DriveComposer(), DriveConfig.defaults(), clock);

        // Tick 1: low curiosity
        orchestrator.tick("a", "t", descriptor);

        // Tick 2: curiosity spikes
        source[0] = forAxis(DriveAxis.CURIOSITY, 0.9);
        orchestrator.tick("a", "t", descriptor);
        double afterSpike = orchestrator.currentDrives("a", "t")
                .map(p -> p.drives().get(DriveAxis.CURIOSITY).intensity()).orElse(0.0);

        // Activation should be instant — no smoothing dampening the spike
        assertThat(afterSpike).as("Activation should be instant, not smoothed")
                .isGreaterThan(0.8);
    }
}
