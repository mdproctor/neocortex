package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

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
}
