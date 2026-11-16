package io.casehub.neocortex.cognition.drive;

import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.EmotionSource;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.PadProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AppraisalDriveModulatorTest {

    private static CognitiveEmotion emotion(EmotionType type, double intensity) {
        return new CognitiveEmotion(type, intensity, "subject",
                Instant.now(), EmotionSource.INTRINSIC, PadProjection.NEUTRAL);
    }

    @Test
    void angerBoostsCompetence() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.ANGER, 0.8)));
        assertThat(mods.get(DriveAxis.COMPETENCE)).isGreaterThan(0.1);
    }

    @Test
    void fearBoostsAutonomy() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.FEAR, 0.9)));
        assertThat(mods.get(DriveAxis.AUTONOMY)).isGreaterThan(0.1);
    }

    @Test
    void joyBoostsCuriosityAndAffiliation() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.JOY, 0.7)));
        assertThat(mods.get(DriveAxis.CURIOSITY)).isGreaterThan(0.05);
        assertThat(mods.get(DriveAxis.AFFILIATION)).isGreaterThan(0.05);
    }

    @Test
    void loveBoostsAffiliation() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.LOVE, 0.6)));
        assertThat(mods.get(DriveAxis.AFFILIATION)).isGreaterThan(0.1);
    }

    @Test
    void distressBoostsAutonomy() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.DISTRESS, 0.85)));
        assertThat(mods.get(DriveAxis.AUTONOMY)).isGreaterThan(0.1);
    }

    @Test
    void shameBoostsAutonomyReducesAffiliation() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.SHAME, 0.7)));
        assertThat(mods.get(DriveAxis.AUTONOMY)).isGreaterThan(0.05);
        assertThat(mods.get(DriveAxis.AFFILIATION)).isLessThan(0);
    }

    @Test
    void intensityScalesModulation() {
        var low = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.ANGER, 0.2)));
        var high = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.ANGER, 0.8)));
        assertThat(high.get(DriveAxis.COMPETENCE)).isGreaterThan(low.get(DriveAxis.COMPETENCE));
    }

    @Test
    void multipleEmotionsStack() {
        var single = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.ANGER, 0.5)));
        var dual = AppraisalDriveModulator.modulate(List.of(
                emotion(EmotionType.ANGER, 0.5),
                emotion(EmotionType.PRIDE, 0.5)));
        assertThat(dual.get(DriveAxis.COMPETENCE)).isGreaterThan(single.get(DriveAxis.COMPETENCE));
    }


    @Test
    void hopeBoostsCuriosityAboveTriggerThreshold() {
        var mods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.HOPE, 0.63)));
        assertThat(mods.get(DriveAxis.CURIOSITY))
                .as("HOPE at 0.63 should produce CURIOSITY delta > 0.2 trigger threshold")
                .isGreaterThan(0.2);
        assertThat(mods.get(DriveAxis.COMPETENCE))
                .as("HOPE should also give secondary COMPETENCE boost")
                .isGreaterThan(0.05);
    }

    @Test
    void hopeStrongerCuriosityDriverThanJoy() {
        var hopeMods = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.HOPE, 0.6)));
        var joyMods  = AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.JOY, 0.6)));
        assertThat(hopeMods.get(DriveAxis.CURIOSITY))
                .as("HOPE (BOOST=0.5) should produce larger CURIOSITY delta than JOY (SECONDARY_BOOST=0.25)")
                .isGreaterThan(joyMods.get(DriveAxis.CURIOSITY));
    }


    @Test
    void emptyEmotionsReturnsZeros() {
        var mods = AppraisalDriveModulator.modulate(List.of());
        assertThat(mods.get(DriveAxis.CURIOSITY)).isCloseTo(0.0, offset(0.001));
        assertThat(mods.get(DriveAxis.COMPETENCE)).isCloseTo(0.0, offset(0.001));
        assertThat(mods.get(DriveAxis.AFFILIATION)).isCloseTo(0.0, offset(0.001));
        assertThat(mods.get(DriveAxis.AUTONOMY)).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void coOccurringEmotions_diminishingReturns() {
        var mods = AppraisalDriveModulator.modulate(List.of(
                emotion(EmotionType.FEAR, 0.9),
                emotion(EmotionType.SHAME, 0.9)));
        assertThat(mods.get(DriveAxis.AUTONOMY))
                .as("FEAR+SHAME should not saturate AUTONOMY at 1.0")
                .isLessThan(0.9);
        assertThat(mods.get(DriveAxis.AUTONOMY))
                .as("Combined should still be higher than single emotion")
                .isGreaterThan(
                        AppraisalDriveModulator.modulate(List.of(emotion(EmotionType.FEAR, 0.9)))
                                               .get(DriveAxis.AUTONOMY));
    }

}
