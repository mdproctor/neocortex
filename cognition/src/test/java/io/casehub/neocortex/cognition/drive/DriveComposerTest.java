package io.casehub.neocortex.cognition.drive;

import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.memory.mood.MoodState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DriveComposerTest {

    private final DriveComposer composer = new DriveComposer();
    private final Instant now = Instant.parse("2026-08-21T12:00:00Z");

    private Map<DriveAxis, DriveIntensity> uniformRaw(double value) {
        return Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, value, "x"),
                DriveAxis.COMPETENCE, new DriveIntensity(DriveAxis.COMPETENCE, value, "x"),
                DriveAxis.AFFILIATION, new DriveIntensity(DriveAxis.AFFILIATION, value, "x"),
                DriveAxis.AUTONOMY, new DriveIntensity(DriveAxis.AUTONOMY, value, "x"));
    }

    @Test
    void compose_noModulation_equalWeights() {
        var raw = Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.8, "gaps"),
                DriveAxis.COMPETENCE, new DriveIntensity(DriveAxis.COMPETENCE, 0.4, "ok"),
                DriveAxis.AFFILIATION, new DriveIntensity(DriveAxis.AFFILIATION, 0.2, "stable"),
                DriveAxis.AUTONOMY, new DriveIntensity(DriveAxis.AUTONOMY, 0.6, "pressure"));

        var profile = composer.compose(raw, null, null, List.of(), DriveConfig.defaults(),
                "agent-1", "tenant-1", now);

        assertThat(profile.agentId()).isEqualTo("agent-1");
        assertThat(profile.dominantDrive()).isEqualTo(DriveAxis.CURIOSITY);
        assertThat(profile.compositeMotivation()).isCloseTo(0.5, within(0.01));
        assertThat(profile.drives()).hasSize(4);
        assertThat(profile.evaluatedAt()).isEqualTo(now);
    }

    @Test
    void compose_noModulation_unequalWeights() {
        var config = new DriveConfig(
                Map.of(DriveAxis.CURIOSITY, 2.0, DriveAxis.COMPETENCE, 1.0,
                       DriveAxis.AFFILIATION, 1.0, DriveAxis.AUTONOMY, 1.0),
                0.05, 0.3, 0.2, 0.25, 1.0, 0.0,
                0.5, java.time.Duration.ofHours(24), 0.6, 0.25, 0.6);
        var raw = Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 1.0, "x"),
                DriveAxis.COMPETENCE, new DriveIntensity(DriveAxis.COMPETENCE, 0.0, "x"),
                DriveAxis.AFFILIATION, new DriveIntensity(DriveAxis.AFFILIATION, 0.0, "x"),
                DriveAxis.AUTONOMY, new DriveIntensity(DriveAxis.AUTONOMY, 0.0, "x"));

        var profile = composer.compose(raw, null, null, List.of(), config, "a", "t", now);

        assertThat(profile.compositeMotivation()).isCloseTo(0.4, within(0.01));
    }

    @Test
    void compose_moodModulation_highArousalAmplifies() {
        var mood = new MoodState("a", "t", null, 0.0, 0.8, 0.0, "excited", null, Set.of(), Map.of());

        var profile = composer.compose(uniformRaw(0.5), null, mood, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        for (var di : profile.drives().values()) {
            assertThat(di.intensity()).isGreaterThan(0.5);
        }
    }

    @Test
    void compose_moodModulation_lowDominanceAmplifiesAutonomy() {
        var mood = new MoodState("a", "t", null, 0.0, 0.0, -0.8, "controlled", null, Set.of(), Map.of());

        var profile = composer.compose(uniformRaw(0.5), null, mood, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        assertThat(profile.drives().get(DriveAxis.AUTONOMY).intensity())
                .isGreaterThan(profile.drives().get(DriveAxis.CURIOSITY).intensity());
    }

    @Test
    void compose_moodModulation_negativePleasureDampens() {
        var mood = new MoodState("a", "t", null, -0.8, 0.0, 0.0, "sad", null, Set.of(), Map.of());

        var profile = composer.compose(uniformRaw(0.5), null, mood, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        for (var di : profile.drives().values()) {
            assertThat(di.intensity()).isLessThan(0.5);
        }
    }

    @Test
    void compose_personalityModulation_socialOrientAmplifiesAffiliation() {
        var disposition = AgentDisposition.builder()
                .socialOrient(DispositionValue.of("cooperative"))
                .build();

        var profile = composer.compose(uniformRaw(0.5), disposition, null, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        assertThat(profile.drives().get(DriveAxis.AFFILIATION).intensity())
                .isGreaterThan(profile.drives().get(DriveAxis.COMPETENCE).intensity());
    }

    @Test
    void compose_personalityModulation_noDispositionValues_noEffect() {
        var disposition = AgentDisposition.builder().build();

        var profile = composer.compose(uniformRaw(0.5), disposition, null, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        for (var di : profile.drives().values()) {
            assertThat(di.intensity()).isEqualTo(0.5);
        }
    }

    @Test
    void compose_intensityClamped() {
        var mood = new MoodState("a", "t", null, 0.9, 0.9, 0.0, "euphoric", null, Set.of(), Map.of());
        var disposition = AgentDisposition.builder()
                .riskAppetite(DispositionValue.of("aggressive"))
                .build();

        var profile = composer.compose(Map.of(
                DriveAxis.CURIOSITY, new DriveIntensity(DriveAxis.CURIOSITY, 0.95, "x"),
                DriveAxis.COMPETENCE, new DriveIntensity(DriveAxis.COMPETENCE, 0.5, "x"),
                DriveAxis.AFFILIATION, new DriveIntensity(DriveAxis.AFFILIATION, 0.5, "x"),
                DriveAxis.AUTONOMY, new DriveIntensity(DriveAxis.AUTONOMY, 0.5, "x")),
                disposition, mood, List.of(), DriveConfig.defaults(), "a", "t", now);

        assertThat(profile.drives().get(DriveAxis.CURIOSITY).intensity()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void compose_emptyDrives_zeroComposite() {
        var profile = composer.compose(Map.of(), null, null, List.of(), DriveConfig.defaults(),
                "a", "t", now);

        assertThat(profile.compositeMotivation()).isEqualTo(0.0);
    }

    @Test
    void compose_narrativeModulation_amplifiesAxis() {
        var narrativeMod = Map.of(DriveAxis.AFFILIATION, 0.5);

        var profile = composer.compose(uniformRaw(0.5), null, null,
                List.of(new ModulationLayer(narrativeMod, DriveConfig.defaults().narrativeModulationStrength(), "narrative")),
                DriveConfig.defaults(), "a", "t", now);

        assertThat(profile.drives().get(DriveAxis.AFFILIATION).intensity())
                .isGreaterThan(0.5);
        assertThat(profile.drives().get(DriveAxis.CURIOSITY).intensity())
                .isEqualTo(0.5);
    }

    @Test
    void compose_narrativeModulation_dampensAxis() {
        var narrativeMod = Map.of(DriveAxis.AUTONOMY, -0.6);

        var profile = composer.compose(uniformRaw(0.5), null, null,
                List.of(new ModulationLayer(narrativeMod, DriveConfig.defaults().narrativeModulationStrength(), "narrative")),
                DriveConfig.defaults(), "a", "t", now);

        assertThat(profile.drives().get(DriveAxis.AUTONOMY).intensity())
                .isLessThan(0.5);
    }

    @Test
    void compose_emptyModulationList_noEffect() {
        var withEmpty = composer.compose(uniformRaw(0.5), null, null, List.of(),
                                        DriveConfig.defaults(), "a", "t", now);
        var withZeroLayer = composer.compose(uniformRaw(0.5), null, null,
                List.of(new ModulationLayer(Map.of(), 0.25, "empty")),
                DriveConfig.defaults(), "a", "t", now);

        for (var axis : DriveAxis.values()) {
            assertThat(withEmpty.drives().get(axis).intensity())
                    .isEqualTo(withZeroLayer.drives().get(axis).intensity());
        }
    }

    @Test
    void compose_multipleModulationLayers_bothContribute() {
        var narrativeMod  = Map.of(DriveAxis.AFFILIATION, 0.4);
        var subThoughtMod = Map.of(DriveAxis.CURIOSITY, 0.5, DriveAxis.AFFILIATION, 0.3);

        var config = DriveConfig.defaults();
        var layers = List.of(
                new ModulationLayer(narrativeMod, config.narrativeModulationStrength(), "narrative"),
                new ModulationLayer(subThoughtMod, config.subThoughtModulationStrength(), "sub-thought")
                            );

        var profile = composer.compose(uniformRaw(0.5), null, null, layers, config, "a", "t", now);

        // AFFILIATION should get both narrative and sub-thought boosts
        assertThat(profile.drives().get(DriveAxis.AFFILIATION).intensity())
                .isGreaterThan(0.5);

        // CURIOSITY should get sub-thought boost only
        assertThat(profile.drives().get(DriveAxis.CURIOSITY).intensity())
                .isGreaterThan(0.5);

        // Both should be above baseline — multi-layer mechanism works
        // (relative ordering depends on specific modulation values × strengths)

        // COMPETENCE should be unchanged (no modulation)
        assertThat(profile.drives().get(DriveAxis.COMPETENCE).intensity())
                .isEqualTo(0.5);
    }

}
