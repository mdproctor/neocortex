package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EditDistanceSimilarityTest {

    @Test
    void identicalSequences_perfectSimilarity() {
        assertThat(EditDistanceSimilarity.compute(
            List.of("A", "B", "C"), List.of("A", "B", "C")))
            .isEqualTo(1.0);
    }

    @Test
    void oneSubstitution() {
        double sim = EditDistanceSimilarity.compute(
            List.of("A", "B", "C"), List.of("A", "X", "C"));
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void completelyDifferent() {
        double sim = EditDistanceSimilarity.compute(
            List.of("A", "B", "C"), List.of("X", "Y", "Z"));
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void bothEmpty_perfectSimilarity() {
        assertThat(EditDistanceSimilarity.compute(List.of(), List.of()))
            .isEqualTo(1.0);
    }

    @Test
    void oneEmpty_zeroSimilarity() {
        assertThat(EditDistanceSimilarity.compute(List.of("A", "B"), List.of()))
            .isEqualTo(0.0);
        assertThat(EditDistanceSimilarity.compute(List.of(), List.of("A", "B")))
            .isEqualTo(0.0);
    }

    @Test
    void insertion() {
        double sim = EditDistanceSimilarity.compute(
            List.of("A", "B"), List.of("A", "X", "B"));
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void deletion() {
        double sim = EditDistanceSimilarity.compute(
            List.of("A", "X", "B"), List.of("A", "B"));
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void singleElementMatch() {
        assertThat(EditDistanceSimilarity.compute(
            List.of("A"), List.of("A"))).isEqualTo(1.0);
    }

    @Test
    void singleElementMismatch() {
        assertThat(EditDistanceSimilarity.compute(
            List.of("A"), List.of("B"))).isEqualTo(0.0);
    }
}
