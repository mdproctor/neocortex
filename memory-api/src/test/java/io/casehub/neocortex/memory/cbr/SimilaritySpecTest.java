package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimilaritySpecTest {

    // --- GaussianDecay ---
    @Test
    void gaussianDecay_validSigma() {
        var g = new SimilaritySpec.GaussianDecay(0.5);
        assertThat(g.sigma()).isEqualTo(0.5);
    }

    @Test
    void gaussianDecay_zeroSigma_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.GaussianDecay(0.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gaussianDecay_negativeSigma_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.GaussianDecay(-0.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- StepDecay ---
    @Test
    void stepDecay_validTolerance() {
        var s = new SimilaritySpec.StepDecay(0.1);
        assertThat(s.tolerance()).isEqualTo(0.1);
    }

    @Test
    void stepDecay_toleranceZero_valid() {
        var s = new SimilaritySpec.StepDecay(0.0);
        assertThat(s.tolerance()).isEqualTo(0.0);
    }

    @Test
    void stepDecay_toleranceOne_valid() {
        var s = new SimilaritySpec.StepDecay(1.0);
        assertThat(s.tolerance()).isEqualTo(1.0);
    }

    @Test
    void stepDecay_negativeTolerance_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.StepDecay(-0.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepDecay_toleranceAboveOne_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.StepDecay(1.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ExponentialDecay ---
    @Test
    void exponentialDecay_validRate() {
        var e = new SimilaritySpec.ExponentialDecay(3.0);
        assertThat(e.decayRate()).isEqualTo(3.0);
    }

    @Test
    void exponentialDecay_zeroRate_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.ExponentialDecay(0.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exponentialDecay_negativeRate_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.ExponentialDecay(-1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- CategoricalTable ---
    @Test
    void categoricalTable_symmetricLookup() {
        var table = new SimilaritySpec.CategoricalTable(
            Map.of("headache", Map.of("migraine", 0.8)));
        assertThat(table.similarities().get("headache").get("migraine")).isEqualTo(0.8);
        assertThat(table.similarities().get("migraine").get("headache")).isEqualTo(0.8);
    }

    @Test
    void categoricalTable_nullSimilarities_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.CategoricalTable(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void categoricalTable_scoreOutOfRange_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.CategoricalTable(
            Map.of("a", Map.of("b", 1.5))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoricalTable_negativeScore_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.CategoricalTable(
            Map.of("a", Map.of("b", -0.1))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoricalTable_conflictingSymmetricEntries_throws() {
        assertThatThrownBy(() -> new SimilaritySpec.CategoricalTable(
            Map.of("a", Map.of("b", 0.8), "b", Map.of("a", 0.7))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoricalTable_immutable() {
        var mutable = new java.util.HashMap<String, Map<String, Double>>();
        mutable.put("a", Map.of("b", 0.5));
        var table = new SimilaritySpec.CategoricalTable(mutable);
        mutable.put("c", Map.of("d", 0.9));
        assertThat(table.similarities()).doesNotContainKey("c");
    }

    @Test
    void categoricalTable_emptyTable_valid() {
        var table = new SimilaritySpec.CategoricalTable(Map.of());
        assertThat(table.similarities()).isEmpty();
    }

    // --- CategoricalTableBuilder ---
    @Test
    void builder_addAndBuild() {
        var table = SimilaritySpec.categoricalTableBuilder()
            .add("headache", "migraine", 0.8)
            .add("headache", "fracture", 0.1)
            .build();
        assertThat(table.similarities().get("headache").get("migraine")).isEqualTo(0.8);
        assertThat(table.similarities().get("migraine").get("headache")).isEqualTo(0.8);
        assertThat(table.similarities().get("headache").get("fracture")).isEqualTo(0.1);
    }

    @Test
    void builder_duplicatePair_throws() {
        assertThatThrownBy(() -> SimilaritySpec.categoricalTableBuilder()
            .add("a", "b", 0.8)
            .add("b", "a", 0.7))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_samePairSameScore_throws() {
        assertThatThrownBy(() -> SimilaritySpec.categoricalTableBuilder()
            .add("a", "b", 0.8)
            .add("a", "b", 0.8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_selfPair_silentlyIgnored() {
        var table = SimilaritySpec.categoricalTableBuilder()
            .add("a", "a", 0.9)
            .build();
        assertThat(table.similarities()).isEmpty();
    }

    @Test
    void builder_scoreOutOfRange_throws() {
        assertThatThrownBy(() -> SimilaritySpec.categoricalTableBuilder()
            .add("a", "b", 1.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Value semantics ---
    @Test
    void gaussianDecay_equalsBySigma() {
        assertThat(new SimilaritySpec.GaussianDecay(0.3))
            .isEqualTo(new SimilaritySpec.GaussianDecay(0.3));
    }

    @Test
    void categoricalTable_equalsByContent() {
        var t1 = SimilaritySpec.categoricalTableBuilder().add("a", "b", 0.5).build();
        var t2 = SimilaritySpec.categoricalTableBuilder().add("a", "b", 0.5).build();
        assertThat(t1).isEqualTo(t2);
    }

    // --- DtwSpec ---
    @Test
    void dtwSpec_nullWindowSize_accepted() {
        var spec = new SimilaritySpec.DtwSpec(null);
        assertThat(spec.windowSize()).isNull();
    }

    @Test
    void dtwSpec_positiveWindowSize_accepted() {
        var spec = new SimilaritySpec.DtwSpec(5);
        assertThat(spec.windowSize()).isEqualTo(5);
    }

    @Test
    void dtwSpec_zeroWindowSize_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.DtwSpec(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSize must be >= 1");
    }

    @Test
    void dtwSpec_negativeWindowSize_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.DtwSpec(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSize must be >= 1");
    }

    // --- EditDistanceSpec ---
    @Test
    void editDistanceSpec_emptyMap_accepted() {
        var spec = new SimilaritySpec.EditDistanceSpec(Map.of());
        assertThat(spec.substitutionSimilarities()).isEmpty();
    }

    @Test
    void editDistanceSpec_mirrorValidation() {
        var spec = new SimilaritySpec.EditDistanceSpec(Map.of(
                "A", Map.of("B", 0.5)));
        assertThat(spec.substitutionSimilarities().get("B").get("A")).isEqualTo(0.5);
    }

    @Test
    void editDistanceSpec_nanScore_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.EditDistanceSpec(Map.of(
                "A", Map.of("B", Double.NaN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editDistanceSpec_outOfRangeScore_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.EditDistanceSpec(Map.of(
                "A", Map.of("B", 1.5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editDistanceSpec_conflictingScores_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.EditDistanceSpec(Map.of(
                "A", Map.of("B", 0.5),
                "B", Map.of("A", 0.7))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting");
    }

    // --- NaN fix (pre-existing bug in CategoricalTable) ---
    @Test
    void categoricalTable_nanScore_rejected() {
        assertThatThrownBy(() -> new SimilaritySpec.CategoricalTable(Map.of(
                "A", Map.of("B", Double.NaN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_nanScore_rejected() {
        assertThatThrownBy(() -> SimilaritySpec.categoricalTableBuilder()
                                               .add("A", "B", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
