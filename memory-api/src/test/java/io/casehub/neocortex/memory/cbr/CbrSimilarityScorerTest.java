package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CbrSimilarityScorerTest {

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("test",
        FeatureField.categorical("color"),
        FeatureField.numeric("score", 0.0, 100.0),
        FeatureField.text("label"));

    @Test
    void categoricalExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red"), Map.of("color", "red"), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void categoricalMismatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red"), Map.of("color", "blue"), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void numericLinearDecay() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", 80.0), Map.of("score", 60.0), Map.of(), SCHEMA);
        // |80-60| / (100-0) = 0.2, so sim = 1.0 - 0.2 = 0.8
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", 50.0), Map.of("score", 50.0), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericMaxDifference() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", 0.0), Map.of("score", 100.0), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericRangeInsideScoresOne() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", NumericRange.of(40.0, 60.0)),
            Map.of("score", 50.0), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericRangeOutsideDecaysLinearly() {
        // case value 80, range [40,60], field range [0,100]
        // distance to nearest bound = 80-60 = 20, decay = 20/100 = 0.2
        // sim = 1.0 - 0.2 = 0.8
        double sim = CbrSimilarityScorer.score(
            Map.of("score", NumericRange.of(40.0, 60.0)),
            Map.of("score", 80.0), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericRangeFarOutsideClampedToZero() {
        // case value 0, range [90,100], field range [0,100]
        // distance = 90, decay = 90/100 = 0.9, sim = 0.1
        double sim = CbrSimilarityScorer.score(
            Map.of("score", NumericRange.of(90.0, 100.0)),
            Map.of("score", 0.0), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void textExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("label", "hello"), Map.of("label", "hello"), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void textMismatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("label", "hello"), Map.of("label", "world"), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void weightedScoring() {
        // color matches (sim=1.0), score differs (sim=0.8)
        // weight color=2.0, score=1.0
        // weighted = (2*1.0 + 1*0.8) / (2+1) = 2.8/3 ≈ 0.933
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red", "score", 80.0),
            Map.of("color", "red", "score", 60.0),
            Map.of("color", 2.0, "score", 1.0),
            SCHEMA);
        assertThat(sim).isCloseTo(2.8 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void defaultWeightsAreUniform() {
        // No explicit weights → all fields weight 1.0
        // color matches (1.0), score differs (0.8) → (1+0.8)/2 = 0.9
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red", "score", 80.0),
            Map.of("color", "red", "score", 60.0),
            Map.of(),
            SCHEMA);
        assertThat(sim).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void emptyQueryFeaturesReturnsOne() {
        double sim = CbrSimilarityScorer.score(Map.of(), Map.of("color", "red"), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void nullSchemaReturnsOne() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red"), Map.of("color", "red"), Map.of(), null);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void missingFeatureInCaseScoresZero() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red"), Map.of(), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void unknownFieldInQueryIgnored() {
        // "unknown" not in schema → skipped, only "color" counts
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red", "unknown", "val"),
            Map.of("color", "red"),
            Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericZeroRangeExactMatch() {
        // Field with min==max → exact match semantics
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", 5.0), Map.of("x", 5.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericZeroRangeMismatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", 5.0), Map.of("x", 6.0), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void multipleFieldsWeightedAverage() {
        // color match (1.0, w=3), score partial (0.5, w=1), label mismatch (0.0, w=1)
        // weighted = (3*1.0 + 1*0.5 + 1*0.0) / (3+1+1) = 3.5/5 = 0.7
        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red", "score", 50.0, "label", "a"),
            Map.of("color", "red", "score", 0.0, "label", "b"),
            Map.of("color", 3.0, "score", 1.0, "label", 1.0),
            SCHEMA);
        assertThat(sim).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void overrideReplacesDefaultTextBehavior() {
        LocalSimilarityFunction prefixMatch = (q, c) ->
            ((String) c).startsWith((String) q) ? 1.0 : 0.0;

        double sim = CbrSimilarityScorer.score(
            Map.of("label", "hel"),
            Map.of("label", "hello world"),
            Map.of(),
            SCHEMA,
            Map.of("label", prefixMatch));
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void overrideForOneFieldDefaultForOthers() {
        LocalSimilarityFunction always1 = (q, c) -> 1.0;

        double sim = CbrSimilarityScorer.score(
            Map.of("color", "red", "label", "a"),
            Map.of("color", "blue", "label", "b"),
            Map.of(),
            SCHEMA,
            Map.of("label", always1));
        // color: exact match miss = 0.0, label: override = 1.0
        // (0.0 + 1.0) / 2 = 0.5
        assertThat(sim).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void emptyOverridesPreservesExistingBehavior() {
        double sim = CbrSimilarityScorer.score(
            Map.of("label", "hello"),
            Map.of("label", "hello"),
            Map.of(),
            SCHEMA,
            Map.of());
        assertThat(sim).isEqualTo(1.0);
    }

    // --- Categorical table tests ---
    static final CbrFeatureSchema TABLE_SCHEMA = CbrFeatureSchema.of("test",
        FeatureField.categorical("type",
            SimilaritySpec.categoricalTableBuilder()
                .add("headache", "migraine", 0.8)
                .add("headache", "fracture", 0.1)
                .build()));

    @Test
    void categoricalTable_graduatedSimilarity() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", "headache"), Map.of("type", "migraine"), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_symmetricLookup() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", "migraine"), Map.of("type", "headache"), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_unlistedPair_zero() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", "headache"), Map.of("type", "unknown"), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_selfPair_one() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", "headache"), Map.of("type", "headache"), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void categoricalTable_emptyTable_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.categorical("x", new SimilaritySpec.CategoricalTable(Map.of())));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", "a"), Map.of("x", "b"), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    // --- Numeric decay tests ---
    @Test
    void gaussianDecay_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 50.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_midRange() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 80.0), Map.of(), schema);
        // normalized distance = 0.3, gaussian = exp(-0.3^2 / (2 * 0.5^2)) = exp(-0.18)
        assertThat(sim).isCloseTo(Math.exp(-0.09 / 0.5), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void gaussianDecay_maxDistance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.3)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 0.0), Map.of("s", 100.0), Map.of(), schema);
        // normalized distance = 1.0, gaussian = exp(-1.0 / (2 * 0.09)) = exp(-5.56) ≈ 0.004
        assertThat(sim).isCloseTo(Math.exp(-1.0 / (2 * 0.3 * 0.3)),
            org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void stepDecay_withinTolerance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 55.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void stepDecay_outsideTolerance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 70.0), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void exponentialDecay_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.ExponentialDecay(3.0)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 50.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void exponentialDecay_fullRange() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.ExponentialDecay(3.0)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 0.0), Map.of("s", 100.0), Map.of(), schema);
        assertThat(sim).isCloseTo(Math.exp(-3.0), org.assertj.core.data.Offset.offset(1e-9));
    }

    // --- NumericRange + SimilaritySpec ---
    @Test
    void gaussianDecay_numericRange_inside() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", NumericRange.of(40, 60)), Map.of("s", 50.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_numericRange_outside() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", NumericRange.of(40, 60)), Map.of("s", 80.0), Map.of(), schema);
        // distance from nearest bound (60) = 20, normalized = 0.2
        assertThat(sim).isCloseTo(Math.exp(-0.04 / (2 * 0.25)),
            org.assertj.core.data.Offset.offset(1e-6));
    }

    // --- Zero range fallback ---
    @Test
    void gaussianDecay_zeroRange_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", 5.0), Map.of("x", 5.0), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_zeroRange_mismatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", 5.0), Map.of("x", 6.0), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    // --- Precedence chain ---
    @Test
    void callerOverride_beatsSimilaritySpec() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        LocalSimilarityFunction alwaysHalf = (q, c) -> 0.5;
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 80.0), Map.of(), schema, Map.of("s", alwaysHalf));
        assertThat(sim).isEqualTo(0.5);
    }

    @Test
    void similaritySpec_beatsTypeDefault() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        // Linear default would give 0.8 for distance 20/100.
        // Step with tolerance 0.1 gives 0.0 for distance 0.2 > 0.1
        double sim = CbrSimilarityScorer.score(
            Map.of("s", 50.0), Map.of("s", 70.0), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void nullSpec_fallsThrough_toTypeDefault() {
        // Numeric with null spec should use linear decay
        double sim = CbrSimilarityScorer.score(
            Map.of("score", 80.0), Map.of("score", 60.0), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }
}
