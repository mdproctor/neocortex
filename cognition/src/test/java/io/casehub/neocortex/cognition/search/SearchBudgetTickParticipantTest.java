package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.appraisal.ActionReadiness;
import io.casehub.neocortex.cognition.appraisal.ActionTendency;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveIntensity;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.SearchFatigueEffects;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.memory.mood.MoodState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchBudgetTickParticipantTest {

    private static final String AGENT = "agent1";
    private static final String TENANT = "tenant1";

    @Test
    void curiosity_dominant_boosts_breadth_and_laterality() {
        var participant = withDrive(DriveAxis.CURIOSITY, 0.8);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().breadth() > 0.5, "Curiosity should boost breadth, got " + budget.get().breadth());
        assertTrue(budget.get().laterality() > 0.3, "Curiosity should boost laterality, got " + budget.get().laterality());
        assertEquals(SearchMotivation.CURIOSITY, budget.get().source());
    }

    @Test
    void competence_dominant_boosts_depth() {
        var participant = withDrive(DriveAxis.COMPETENCE, 0.8);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().depth() > 0.5, "Competence should boost depth, got " + budget.get().depth());
        assertEquals(SearchMotivation.COMPETENCE, budget.get().source());
    }

    @Test
    void high_threat_collapses_laterality() {
        var participant = withThreat(0.9);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertEquals(0.0, budget.get().laterality(),
                "High AVOIDANCE should collapse laterality to 0");
        assertEquals(SearchMotivation.THREAT, budget.get().source());
    }

    @Test
    void no_drives_returns_empty() {
        var driveOrch = mock(DriveOrchestrator.class);
        when(driveOrch.currentDrives(AGENT, TENANT)).thenReturn(Optional.empty());
        var participant = new SearchBudgetTickParticipant(
                driveOrch, null, null, null, registry(), null, null, null);
        participant.tick(context());
        assertTrue(participant.currentBudget(AGENT, TENANT).isEmpty());
    }

    @Test
    void low_intensity_produces_small_budget() {
        var participant = withDrive(DriveAxis.CURIOSITY, 0.1);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertTrue(budget.get().breadth() < 0.3,
                "Low intensity should produce small breadth, got " + budget.get().breadth());
    }

    @Test
    void positive_mood_boosts_breadth() {
        var driveOrch = mockDriveOrch(DriveAxis.CURIOSITY, 0.5);
        var moodOrch = mock(MoodOrchestrator.class);
        when(moodOrch.currentMood(AGENT, TENANT)).thenReturn(
                Optional.of(new MoodState(AGENT, TENANT, Instant.now(), 0.6, 0.3, 0.2, "test", null, null, Map.of())));

        var participant = new SearchBudgetTickParticipant(
                driveOrch, null, null, moodOrch, registry(), null, null, null);
        participant.tick(context());

        var withMood = participant.currentBudget(AGENT, TENANT);

        var participantNoMood = new SearchBudgetTickParticipant(
                driveOrch, null, null, null, registry(), null, null, null);
        participantNoMood.tick(context());
        var withoutMood = participantNoMood.currentBudget(AGENT, TENANT);

        assertTrue(withMood.isPresent());
        assertTrue(withoutMood.isPresent());
        assertTrue(withMood.get().breadth() >= withoutMood.get().breadth(),
                "Positive mood should boost or maintain breadth");
    }

    @Test
    void autonomy_drive_boosts_laterality_and_depth() {
        var participant = withDrive(DriveAxis.AUTONOMY, 0.8);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertEquals(SearchMotivation.AUTONOMY, budget.get().source());
    }

    @Test
    void affiliation_drive_accelerates_convergence() {
        var participant = withDrive(DriveAxis.AFFILIATION, 0.8);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertEquals(SearchMotivation.AFFILIATION, budget.get().source());
    }

    @Test
    void threat_overrides_drive_as_source_when_higher() {
        var drives = Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.4, "interest"));
        var profile = new DriveProfile(AGENT, TENANT, drives, 0.4, DriveAxis.CURIOSITY, Instant.now());
        var driveOrch = mock(DriveOrchestrator.class);
        when(driveOrch.currentDrives(AGENT, TENANT)).thenReturn(Optional.of(profile));

        var appraisal = mock(AppraisalTickParticipant.class);
        var avoidance = new ActionTendency(ActionReadiness.AVOIDANCE, 0.9, "danger");
        when(appraisal.currentResult(AGENT, TENANT)).thenReturn(
                Optional.of(new AppraisalResult(List.of(), List.of(avoidance), null)));

        var participant = new SearchBudgetTickParticipant(
                driveOrch, appraisal, null, null, registry(), null, null, null);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT);
        assertTrue(budget.isPresent());
        assertEquals(SearchMotivation.THREAT, budget.get().source(),
                "Threat at 0.9 should override curiosity at 0.4");
    }
// --- channel modulation tests ---

    @Test
    void specific_focus_high_gap_boosts_depth_reduces_breadth() {
        var channel = new io.casehub.neocortex.cognition.drive.CuriosityChannel(
                io.casehub.neocortex.cognition.drive.CuriosityChannel.HedonicType.I_TYPE, 0.3,
                io.casehub.neocortex.cognition.drive.CuriosityChannel.FocusType.SPECIFIC, 0.9);
        var participant = withChannelAndConfig(DriveAxis.CURIOSITY, 0.5, channel, io.casehub.neocortex.cognitive.ChannelModulationConfig.defaults());
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertTrue(budget.depth() > baseline.depth(),
                   "SPECIFIC + high gap proximity should boost depth: " + budget.depth() + " vs " + baseline.depth());
        assertTrue(budget.breadth() < baseline.breadth(),
                   "SPECIFIC + high gap proximity should reduce breadth: " + budget.breadth() + " vs " + baseline.breadth());
    }

    @Test
    void diversive_focus_boosts_breadth_laterality_reduces_convergence() {
        var channel = new io.casehub.neocortex.cognition.drive.CuriosityChannel(
                io.casehub.neocortex.cognition.drive.CuriosityChannel.HedonicType.I_TYPE, 0.5,
                io.casehub.neocortex.cognition.drive.CuriosityChannel.FocusType.DIVERSIVE, 0.5);
        var participant = withChannelAndConfig(DriveAxis.CURIOSITY, 0.5, channel, io.casehub.neocortex.cognitive.ChannelModulationConfig.defaults());
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertTrue(budget.breadth() > baseline.breadth(), "DIVERSIVE should boost breadth");
        assertTrue(budget.laterality() > baseline.laterality(), "DIVERSIVE should boost laterality");
        assertTrue(budget.convergence() < baseline.convergence(), "DIVERSIVE should reduce convergence");
    }

    @Test
    void d_type_hedonic_boosts_convergence() {
        var channel = new io.casehub.neocortex.cognition.drive.CuriosityChannel(
                io.casehub.neocortex.cognition.drive.CuriosityChannel.HedonicType.D_TYPE, 0.5,
                io.casehub.neocortex.cognition.drive.CuriosityChannel.FocusType.SPECIFIC, 0.5);
        var participant = withChannelAndConfig(DriveAxis.CURIOSITY, 0.5, channel, io.casehub.neocortex.cognitive.ChannelModulationConfig.defaults());
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertTrue(budget.convergence() > baseline.convergence(), "D_TYPE should boost convergence");
    }

    @Test
    void null_channel_preserves_baseline() {
        var participant = withChannelAndConfig(DriveAxis.CURIOSITY, 0.5, null, io.casehub.neocortex.cognitive.ChannelModulationConfig.defaults());
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertEquals(baseline.breadth(), budget.breadth(), 0.001);
        assertEquals(baseline.laterality(), budget.laterality(), 0.001);
        assertEquals(baseline.depth(), budget.depth(), 0.001);
        assertEquals(baseline.convergence(), budget.convergence(), 0.001);
    }

    @Test
    void null_channel_modulation_config_preserves_baseline() {
        var channel = new io.casehub.neocortex.cognition.drive.CuriosityChannel(
                io.casehub.neocortex.cognition.drive.CuriosityChannel.HedonicType.D_TYPE, 0.5,
                io.casehub.neocortex.cognition.drive.CuriosityChannel.FocusType.SPECIFIC, 0.9);
        var participant = withChannelAndConfig(DriveAxis.CURIOSITY, 0.5, channel, null);
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertEquals(baseline.breadth(), budget.breadth(), 0.001);
        assertEquals(baseline.depth(), budget.depth(), 0.001);
    }
    @Test
    void channel_weight_somatic_dominance_boosts_convergence() {
        var driveOrch = mockDriveOrch(DriveAxis.COMPETENCE, 0.5);
        var appraisal = mock(AppraisalTickParticipant.class);
        var cw = new io.casehub.neocortex.cognition.appraisal.ChannelWeightProfile(0.40, 0.15, 0.10, 0.35);
        when(appraisal.currentChannelWeights(AGENT, TENANT)).thenReturn(Optional.of(cw));
        when(appraisal.currentResult(AGENT, TENANT)).thenReturn(Optional.empty());

        var withCw = new SearchBudgetTickParticipant(
                driveOrch, appraisal, null, null, registry(), null, null, null);
        withCw.tick(context());
        var cwBudget = withCw.currentBudget(AGENT, TENANT).orElseThrow();

        var noCw = new SearchBudgetTickParticipant(
                driveOrch, null, null, null, registry(), null, null, null);
        noCw.tick(context());
        var baseBudget = noCw.currentBudget(AGENT, TENANT).orElseThrow();

        assertTrue(cwBudget.convergence() > baseBudget.convergence(),
                "Somatic+actionTendency dominant channel weights should boost convergence: " +
                cwBudget.convergence() + " vs " + baseBudget.convergence());
    }

// --- activation decay tests ---

    @Test
    void activation_with_decay_below_peak_equals_logistic() {
        double k        = 6.0, mid = 0.5, decay = 2.0, peak = 0.8;
        double x        = 0.6;
        double expected = SearchBudgetTickParticipant.logistic(x, k, mid);
        double actual   = SearchBudgetTickParticipant.activationWithDecay(x, k, mid, decay, peak);
        assertEquals(expected, actual, 1e-10);
    }

    @Test
    void activation_with_decay_past_peak_is_lower() {
        double k        = 6.0, mid = 0.5, decay = 2.0, peak = 0.7;
        double atPeak   = SearchBudgetTickParticipant.activationWithDecay(0.7, k, mid, decay, peak);
        double pastPeak = SearchBudgetTickParticipant.activationWithDecay(0.9, k, mid, decay, peak);
        assertTrue(pastPeak < atPeak, "Past-peak activation should be lower: at=" + atPeak + " past=" + pastPeak);
    }

    @Test
    void activation_with_zero_decay_equals_logistic() {
        double k        = 6.0, mid = 0.5, decay = 0.0, peak = 0.5;
        double x        = 0.9;
        double expected = SearchBudgetTickParticipant.logistic(x, k, mid);
        double actual   = SearchBudgetTickParticipant.activationWithDecay(x, k, mid, decay, peak);
        assertEquals(expected, actual, 1e-10);
    }

// --- extreme interaction tests ---

    @Test
    void extreme_interaction_narrows_breadth_past_peak() {
        var extreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults();
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.7)
                                       .withExtremeInteraction(extreme);
        var participant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.9, config);
        participant.tick(context());

        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertTrue(budget.extremeActivation(), "Should be extreme past peak");

        var defaultParticipant = withDrive(DriveAxis.CURIOSITY, 0.9);
        defaultParticipant.tick(context());
        var defaultBudget = defaultParticipant.currentBudget(AGENT, TENANT).orElseThrow();

        assertTrue(budget.breadth() < defaultBudget.breadth(),
                   "Extreme breadth should be narrower: " + budget.breadth() + " vs " + defaultBudget.breadth());
        assertFalse(defaultBudget.extremeActivation(), "Default config should not be extreme");
    }

    @Test
    void default_config_does_not_set_extreme_activation() {
        var participant = withDrive(DriveAxis.CURIOSITY, 0.95);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertFalse(budget.extremeActivation(), "Default config (null sub-records) should never set extremeActivation");
    }

    @Test
    void per_parameter_peak_differentiates_decay_onset() {
        var extreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                             .withBreadthPeak(0.6)
                                                                             .withDepthPeak(0.95);
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.85)
                                       .withExtremeInteraction(extreme);
        var participant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.8, config);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();

        var uniformExtreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults();
        var uniformConfig = SearchBudgetConfig.defaults()
                                              .withExtremePeak(0.85)
                                              .withExtremeInteraction(uniformExtreme);
        var uniformParticipant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.8, uniformConfig);
        uniformParticipant.tick(context());
        var uniformBudget = uniformParticipant.currentBudget(AGENT, TENANT).orElseThrow();

        assertTrue(budget.breadth() < uniformBudget.breadth(),
                   "Lower breadth peak (0.6) should cause more decay at intensity 0.8: per-param=" + budget.breadth() + " uniform=" + uniformBudget.breadth());
        assertTrue(budget.depth() >= uniformBudget.depth(),
                   "Higher depth peak (0.95) should not decay at intensity 0.8: per-param=" + budget.depth() + " uniform=" + uniformBudget.depth());
    }

    @Test
    void null_per_parameter_peaks_fall_back_to_global() {
        var extreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults();
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.7)
                                       .withExtremeInteraction(extreme);
        var participant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.9, config);
        participant.tick(context());
        var nullPeakBudget = participant.currentBudget(AGENT, TENANT).orElseThrow();

        var explicitExtreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                                     .withBreadthPeak(0.7).withDepthPeak(0.7).withLateralityPeak(0.7).withConvergencePeak(0.7);
        var explicitConfig = SearchBudgetConfig.defaults()
                                               .withExtremePeak(0.7)
                                               .withExtremeInteraction(explicitExtreme);
        var explicitParticipant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.9, explicitConfig);
        explicitParticipant.tick(context());
        var explicitPeakBudget = explicitParticipant.currentBudget(AGENT, TENANT).orElseThrow();

        assertEquals(nullPeakBudget.breadth(), explicitPeakBudget.breadth(), 1e-10,
                     "Null peaks should behave identically to explicit peaks matching the global");
        assertEquals(nullPeakBudget.depth(), explicitPeakBudget.depth(), 1e-10);
        assertEquals(nullPeakBudget.laterality(), explicitPeakBudget.laterality(), 1e-10);
        assertEquals(nullPeakBudget.convergence(), explicitPeakBudget.convergence(), 1e-10);
    }

    @Test
    void separate_amplification_threshold_diverges_from_decay_peak() {
        var extreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                             .withBreadthPeak(0.9)
                                                                             .withAmplificationThreshold(0.6);
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.85)
                                       .withExtremeInteraction(extreme);
        var participant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.75, config);
        participant.tick(context());
        var separateBudget = participant.currentBudget(AGENT, TENANT).orElseThrow();

        var sharedExtreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                                   .withBreadthPeak(0.9);
        var sharedConfig = SearchBudgetConfig.defaults()
                                             .withExtremePeak(0.85)
                                             .withExtremeInteraction(sharedExtreme);
        var sharedParticipant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.75, sharedConfig);
        sharedParticipant.tick(context());
        var sharedBudget = sharedParticipant.currentBudget(AGENT, TENANT).orElseThrow();

        assertNotEquals(separateBudget.breadth(), sharedBudget.breadth(),
                        "Separate amplification threshold (0.6) should produce different breadth than shared peak (0.9) at intensity 0.75");
    }

    @Test
    void null_amplification_threshold_falls_back_to_peak() {
        var extreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                             .withBreadthPeak(0.7);
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.85)
                                       .withExtremeInteraction(extreme);
        var participant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.9, config);
        participant.tick(context());
        var nullThresholdBudget = participant.currentBudget(AGENT, TENANT).orElseThrow();

        var explicitExtreme = io.casehub.neocortex.cognitive.ExtremeInteractionConfig.defaults()
                                                                                     .withBreadthPeak(0.7)
                                                                                     .withAmplificationThreshold(0.7);
        var explicitConfig = SearchBudgetConfig.defaults()
                                               .withExtremePeak(0.85)
                                               .withExtremeInteraction(explicitExtreme);
        var explicitParticipant = withDriveAndConfig(DriveAxis.CURIOSITY, 0.9, explicitConfig);
        explicitParticipant.tick(context());
        var explicitThresholdBudget = explicitParticipant.currentBudget(AGENT, TENANT).orElseThrow();

        assertEquals(nullThresholdBudget.breadth(), explicitThresholdBudget.breadth(), 1e-10,
                     "Null amplification threshold should behave identically to explicit threshold matching the peak");
    }


// --- fatigue tests ---

    @Test
    void fatigue_accumulates_and_degrades_breadth() {
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.6)
                                       .withFatigueEffects(SearchFatigueEffects.defaults());
        var participant = withDriveConfigAndPool(DriveAxis.CURIOSITY, 0.8, config);

        participant.tick(context());
        var budget1 = participant.currentBudget(AGENT, TENANT).orElseThrow();

        for (int i = 0; i < 4; i++) {participant.tick(context());}
        var budget5 = participant.currentBudget(AGENT, TENANT).orElseThrow();

        assertTrue(budget5.breadth() < budget1.breadth(),
                   "Fatigue should reduce breadth over time: tick1=" + budget1.breadth() + " tick5=" + budget5.breadth());
    }

    @Test
    void fatigue_does_not_accumulate_below_peak() {
        var config = SearchBudgetConfig.defaults()
                                       .withExtremePeak(0.9)
                                       .withFatigueEffects(SearchFatigueEffects.defaults());
        var participant = withDriveConfigAndPool(DriveAxis.CURIOSITY, 0.5, config);

        participant.tick(context());
        var budget1 = participant.currentBudget(AGENT, TENANT).orElseThrow();

        for (int i = 0; i < 4; i++) {participant.tick(context());}
        var budget5 = participant.currentBudget(AGENT, TENANT).orElseThrow();

        assertEquals(budget1.breadth(), budget5.breadth(), 0.001,
                     "Below peak, breadth should not change from fatigue");
    }


    // --- helpers ---

    private SearchBudgetTickParticipant withDrive(DriveAxis axis, double intensity) {
        return new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, registry(), null, null, null);
    }

    private SearchBudgetTickParticipant withThreat(double threatIntensity) {
        var drives = Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.2, "mild"));
        var profile = new DriveProfile(AGENT, TENANT, drives, 0.2, DriveAxis.CURIOSITY, Instant.now());
        var driveOrch = mock(DriveOrchestrator.class);
        when(driveOrch.currentDrives(AGENT, TENANT)).thenReturn(Optional.of(profile));

        var appraisal = mock(AppraisalTickParticipant.class);
        var avoidance = new ActionTendency(ActionReadiness.AVOIDANCE, threatIntensity, "threat");
        when(appraisal.currentResult(AGENT, TENANT)).thenReturn(
                Optional.of(new AppraisalResult(List.of(), List.of(avoidance), null)));

        return new SearchBudgetTickParticipant(
                driveOrch, appraisal, null, null, registry(), null, null, null);
    }

    private DriveOrchestrator mockDriveOrch(DriveAxis axis, double intensity) {
        var drives = Map.of(axis, new DriveIntensity(axis, intensity, "test"));
        var profile = new DriveProfile(AGENT, TENANT, drives, intensity, axis, Instant.now());
        var driveOrch = mock(DriveOrchestrator.class);
        when(driveOrch.currentDrives(AGENT, TENANT)).thenReturn(Optional.of(profile));
        return driveOrch;
    }

    private EffectiveCognitiveDefaults registry() {
        var registry = mock(EffectiveCognitiveDefaults.class);
        when(registry.forAgentOrDefaults(anyString(), anyString())).thenReturn(
                CognitiveDefaults.empty(AGENT).withSearchBudgetConfig(SearchBudgetConfig.defaults()));
        return registry;
    }

    private CognitionTickContext context() {
        var ctx = mock(CognitionTickContext.class);
        when(ctx.agentId()).thenReturn(AGENT);
        when(ctx.tenantId()).thenReturn(TENANT);
        when(ctx.observation()).thenReturn("test observation");
        return ctx;
    }

    private SearchBudgetTickParticipant withChannelAndConfig(
            DriveAxis axis, double intensity,
            io.casehub.neocortex.cognition.drive.CuriosityChannel channel,
            io.casehub.neocortex.cognitive.ChannelModulationConfig channelMod) {
        var curiosityDrive = mock(io.casehub.neocortex.cognition.drive.CuriosityDrive.class);
        when(curiosityDrive.lastChannel()).thenReturn(channel);
        var config = channelMod != null
                     ? new io.casehub.neocortex.cognitive.SearchBudgetConfig(6.0, 0.5, 6.0, 0.5, 0.7, 3.0, 0.5, 6.0, 0.5, 1.4, channelMod, 0.85, null, null)
                     : io.casehub.neocortex.cognitive.SearchBudgetConfig.defaults();
        var reg = mock(EffectiveCognitiveDefaults.class);
        when(reg.forAgentOrDefaults(anyString(), anyString())).thenReturn(
                CognitiveDefaults.empty(AGENT).withSearchBudgetConfig(config));
        return new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, reg, null, curiosityDrive, null);
    }

    private SearchBudget baselineBudget(DriveAxis axis, double intensity) {
        var participant = new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, registry(), null, null, null);
        participant.tick(context());
        return participant.currentBudget(AGENT, TENANT).orElseThrow();
    }

    private SearchBudgetTickParticipant withDriveAndConfig(DriveAxis axis, double intensity, SearchBudgetConfig config) {
        var reg = mock(EffectiveCognitiveDefaults.class);
        when(reg.forAgentOrDefaults(anyString(), anyString())).thenReturn(
                CognitiveDefaults.empty(AGENT).withSearchBudgetConfig(config));
        return new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, reg, null, null, null);
    }

    private SearchBudgetTickParticipant withDriveConfigAndPool(DriveAxis axis, double intensity, SearchBudgetConfig config) {
        var poolConfig = io.casehub.neocortex.cognitive.CognitiveResourcePoolConfig.defaults();
        var reg        = mock(EffectiveCognitiveDefaults.class);
        when(reg.forAgentOrDefaults(anyString(), anyString())).thenReturn(
                CognitiveDefaults.empty(AGENT).withSearchBudgetConfig(config).withCognitiveResourcePoolConfig(poolConfig));
        var pool = new io.casehub.neocortex.cognition.core.DefaultCognitiveResourcePool(reg);
        return new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, reg, null, null, pool);
    }

// --- capacity ceiling tests ---

    @Test
    void capacity_ceiling_bounds_depth() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.4, 1.0, 1.0);
        var participant = withDriveAndCapacity(DriveAxis.COMPETENCE, 0.8, capacity);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertTrue(budget.depth() <= 0.4, "Depth should be capped at 0.4, got " + budget.depth());
    }

    @Test
    void capacity_below_activation_has_no_effect() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.95, 0.95, 0.95);
        var participant = withDriveAndCapacity(DriveAxis.CURIOSITY, 0.5, capacity);
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.CURIOSITY, 0.5);
        assertEquals(baseline.depth(), budget.depth(), 0.001, "High ceiling should not change depth");
        assertEquals(baseline.breadth(), budget.breadth(), 0.001, "High ceiling should not change breadth");
    }

    @Test
    void capacity_gap_signal_fires_above_threshold() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.4, 1.0, 1.0);
        var participant = withDriveAndCapacity(DriveAxis.COMPETENCE, 0.8, capacity);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertTrue(budget.capacityConstrained(), "Should be capacity constrained with depth ceiling 0.4");
        assertTrue(budget.depthGap() > 0.05, "Depth gap should be > 0.05, got " + budget.depthGap());
    }

    @Test
    void capacity_gap_below_threshold_does_not_fire() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.95, 0.95, 0.95);
        var participant = withDriveAndCapacity(DriveAxis.CURIOSITY, 0.5, capacity);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertFalse(budget.capacityConstrained(), "Should not be constrained when ceiling is high");
    }

    @Test
    void null_capacity_preserves_existing_behaviour() {
        var participant = withDrive(DriveAxis.CURIOSITY, 0.8);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertFalse(budget.capacityConstrained());
        assertEquals(0.0, budget.depthGap());
        assertEquals(0.0, budget.breadthGap());
        assertEquals(0.0, budget.lateralityGap());
    }

    @Test
    void evictIdleRemovesStaleEntries() {
        var participant = withDrive(DriveAxis.CURIOSITY, 0.8);
        participant.tick(context());
        assertTrue(participant.currentBudget(AGENT, TENANT).isPresent());

        participant.evictIdle(java.time.Duration.ZERO);

        assertTrue(participant.currentBudget(AGENT, TENANT).isEmpty());
    }

    @Test
    void convergence_never_capped_by_capacity() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.1, 0.1, 0.1);
        var participant = withDriveAndCapacity(DriveAxis.COMPETENCE, 0.8, capacity);
        participant.tick(context());
        var budget   = participant.currentBudget(AGENT, TENANT).orElseThrow();
        var baseline = baselineBudget(DriveAxis.COMPETENCE, 0.8);
        assertEquals(baseline.convergence(), budget.convergence(), 0.001,
                     "Convergence should be unaffected by capacity ceiling");
    }

    @Test
    void per_parameter_gap_values_are_correct() {
        var capacity    = new io.casehub.neocortex.cognitive.CognitiveCapacityConfig(0.3, 0.3, 0.3);
        var participant = withDriveAndCapacity(DriveAxis.CURIOSITY, 0.8, capacity);
        participant.tick(context());
        var budget = participant.currentBudget(AGENT, TENANT).orElseThrow();
        assertTrue(budget.depthGap() >= 0.0, "depthGap should be non-negative");
        assertTrue(budget.breadthGap() >= 0.0, "breadthGap should be non-negative");
        assertTrue(budget.lateralityGap() >= 0.0, "lateralityGap should be non-negative");
        double totalGap = budget.depthGap() + budget.breadthGap() + budget.lateralityGap();
        assertTrue(totalGap > 0.0, "At least one gap should be non-zero with ceiling 0.3 and intensity 0.8");
    }

    private SearchBudgetTickParticipant withDriveAndCapacity(DriveAxis axis, double intensity,
                                                             io.casehub.neocortex.cognitive.CognitiveCapacityConfig capacity) {
        var reg = mock(EffectiveCognitiveDefaults.class);
        when(reg.forAgentOrDefaults(anyString(), anyString())).thenReturn(
                CognitiveDefaults.empty(AGENT)
                                 .withSearchBudgetConfig(SearchBudgetConfig.defaults())
                                 .withCapacityConfig(capacity));
        return new SearchBudgetTickParticipant(
                mockDriveOrch(axis, intensity), null, null, null, reg, null, null, null);
    }


}
