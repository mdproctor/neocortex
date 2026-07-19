package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static io.casehub.neocortex.memory.cbr.FeatureValue.stringList;
import static io.casehub.neocortex.memory.cbr.FeatureValue.structList;
import static org.assertj.core.api.Assertions.assertThat;

class CbrSimilarityScorerTest {

    static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("test",
        FeatureField.categorical("color"),
        FeatureField.numeric("score", 0.0, 100.0),
        FeatureField.text("label"));

    @Test
    void categoricalExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red")), Map.of("color", string("red")), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void categoricalMismatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red")), Map.of("color", string("blue")), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void numericLinearDecay() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", number(80.0)), Map.of("score", number(60.0)), Map.of(), SCHEMA);
        // |80-60| / (100-0) = 0.2, so sim = 1.0 - 0.2 = 0.8
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", number(50.0)), Map.of("score", number(50.0)), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericMaxDifference() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", number(0.0)), Map.of("score", number(100.0)), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericRangeInsideScoresOne() {
        double sim = CbrSimilarityScorer.score(
            Map.of("score", FeatureValue.range(40.0, 60.0)),
            Map.of("score", number(50.0)), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericRangeOutsideDecaysLinearly() {
        // case value 80, range [40,60], field range [0,100]
        // distance to nearest bound = 80-60 = 20, decay = 20/100 = 0.2
        // sim = 1.0 - 0.2 = 0.8
        double sim = CbrSimilarityScorer.score(
            Map.of("score", FeatureValue.range(40.0, 60.0)),
            Map.of("score", number(80.0)), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void numericRangeFarOutsideClampedToZero() {
        // case value 0, range [90,100], field range [0,100]
        // distance = 90, decay = 90/100 = 0.9, sim = 0.1
        double sim = CbrSimilarityScorer.score(
            Map.of("score", FeatureValue.range(90.0, 100.0)),
            Map.of("score", number(0.0)), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void textExactMatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("label", string("hello")), Map.of("label", string("hello")), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void textMismatch() {
        double sim = CbrSimilarityScorer.score(
            Map.of("label", string("hello")), Map.of("label", string("world")), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void weightedScoring() {
        // color matches (sim=1.0), score differs (sim=0.8)
        // weight color=2.0, score=1.0
        // weighted = (2*1.0 + 1*0.8) / (2+1) = 2.8/3 ≈ 0.933
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red"), "score", number(80.0)),
            Map.of("color", string("red"), "score", number(60.0)),
            Map.of("color", 2.0, "score", 1.0),
            SCHEMA);
        assertThat(sim).isCloseTo(2.8 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void defaultWeightsAreUniform() {
        // No explicit weights → all fields weight 1.0
        // color matches (1.0), score differs (0.8) → (1+0.8)/2 = 0.9
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red"), "score", number(80.0)),
            Map.of("color", string("red"), "score", number(60.0)),
            Map.of(),
            SCHEMA);
        assertThat(sim).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void emptyQueryFeaturesReturnsOne() {
        double sim = CbrSimilarityScorer.score(Map.of(), Map.of("color", string("red")), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void nullSchemaReturnsOne() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red")), Map.of("color", string("red")), Map.of(), null);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void missingFeatureInCaseScoresZero() {
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red")), Map.of(), Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void unknownFieldInQueryIgnored() {
        // "unknown" not in schema → skipped, only "color" counts
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red"), "unknown", string("val")),
            Map.of("color", string("red")),
            Map.of(), SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericZeroRangeExactMatch() {
        // Field with min==max → exact match semantics
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", number(5.0)), Map.of("x", number(5.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void numericZeroRangeMismatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", number(5.0)), Map.of("x", number(6.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void multipleFieldsWeightedAverage() {
        // color match (1.0, w=3), score partial (0.5, w=1), label mismatch (0.0, w=1)
        // weighted = (3*1.0 + 1*0.5 + 1*0.0) / (3+1+1) = 3.5/5 = 0.7
        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red"), "score", number(50.0), "label", string("a")),
            Map.of("color", string("red"), "score", number(0.0), "label", string("b")),
            Map.of("color", 3.0, "score", 1.0, "label", 1.0),
            SCHEMA);
        assertThat(sim).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void overrideReplacesDefaultTextBehavior() {
        LocalSimilarityFunction prefixMatch = (q, c) ->
            ((FeatureValue.StringVal) c).value().startsWith(((FeatureValue.StringVal) q).value()) ? 1.0 : 0.0;

        double sim = CbrSimilarityScorer.score(
            Map.of("label", string("hel")),
            Map.of("label", string("hello world")),
            Map.of(),
            SCHEMA,
            Map.of("label", prefixMatch));
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void overrideForOneFieldDefaultForOthers() {
        LocalSimilarityFunction always1 = (q, c) -> 1.0;

        double sim = CbrSimilarityScorer.score(
            Map.of("color", string("red"), "label", string("a")),
            Map.of("color", string("blue"), "label", string("b")),
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
            Map.of("label", string("hello")),
            Map.of("label", string("hello")),
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
            Map.of("type", string("headache")), Map.of("type", string("migraine")), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_symmetricLookup() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", string("migraine")), Map.of("type", string("headache")), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_unlistedPair_zero() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", string("headache")), Map.of("type", string("unknown")), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void categoricalTable_selfPair_one() {
        double sim = CbrSimilarityScorer.score(
            Map.of("type", string("headache")), Map.of("type", string("headache")), Map.of(), TABLE_SCHEMA);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void categoricalTable_emptyTable_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.categorical("x", new SimilaritySpec.CategoricalTable(Map.of())));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", string("a")), Map.of("x", string("b")), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    // --- Numeric decay tests ---
    @Test
    void gaussianDecay_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(50.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_midRange() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(80.0)), Map.of(), schema);
        // normalized distance = 0.3, gaussian = exp(-0.3^2 / (2 * 0.5^2)) = exp(-0.18)
        assertThat(sim).isCloseTo(Math.exp(-0.09 / 0.5), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void gaussianDecay_maxDistance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.3)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(0.0)), Map.of("s", number(100.0)), Map.of(), schema);
        // normalized distance = 1.0, gaussian = exp(-1.0 / (2 * 0.09)) = exp(-5.56) ≈ 0.004
        assertThat(sim).isCloseTo(Math.exp(-1.0 / (2 * 0.3 * 0.3)),
            org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void stepDecay_withinTolerance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(55.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void stepDecay_outsideTolerance() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(70.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void exponentialDecay_exactMatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.ExponentialDecay(3.0)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(50.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void exponentialDecay_fullRange() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.ExponentialDecay(3.0)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(0.0)), Map.of("s", number(100.0)), Map.of(), schema);
        assertThat(sim).isCloseTo(Math.exp(-3.0), org.assertj.core.data.Offset.offset(1e-9));
    }

    // --- NumericRange + SimilaritySpec ---
    @Test
    void gaussianDecay_numericRange_inside() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", FeatureValue.range(40, 60)), Map.of("s", number(50.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_numericRange_outside() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("s", FeatureValue.range(40, 60)), Map.of("s", number(80.0)), Map.of(), schema);
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
            Map.of("x", number(5.0)), Map.of("x", number(5.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(1.0);
    }

    @Test
    void gaussianDecay_zeroRange_mismatch() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("x", 5.0, 5.0, new SimilaritySpec.GaussianDecay(0.5)));
        double sim = CbrSimilarityScorer.score(
            Map.of("x", number(5.0)), Map.of("x", number(6.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    // --- Precedence chain ---
    @Test
    void callerOverride_beatsSimilaritySpec() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.5)));
        LocalSimilarityFunction alwaysHalf = (q, c) -> 0.5;
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(80.0)), Map.of(), schema, Map.of("s", alwaysHalf));
        assertThat(sim).isEqualTo(0.5);
    }

    @Test
    void similaritySpec_beatsTypeDefault() {
        var schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("s", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        // Linear default would give 0.8 for distance 20/100.
        // Step with tolerance 0.1 gives 0.0 for distance 0.2 > 0.1
        double sim = CbrSimilarityScorer.score(
            Map.of("s", number(50.0)), Map.of("s", number(70.0)), Map.of(), schema);
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void nullSpec_fallsThrough_toTypeDefault() {
        // Numeric with null spec should use linear decay
        double sim = CbrSimilarityScorer.score(
            Map.of("score", number(80.0)), Map.of("score", number(60.0)), Map.of(), SCHEMA);
        assertThat(sim).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void dtwSpec_windowedDtw_affectsScore() {
        var schema = CbrFeatureSchema.of("ts-test",
                                         FeatureField.timeSeries("curve", "t",
                                                                 new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(1)),
                                                                 FeatureField.numeric("t", 0, 10),
                                                                 FeatureField.numeric("val", 0, 100)));
        var q = java.util.Map.<String, FeatureValue>of("curve", structList(java.util.List.of(
                java.util.Map.<String, FeatureValue>of("t", number(1), "val", number(10)),
                java.util.Map.<String, FeatureValue>of("t", number(2), "val", number(90)))));
        var c = java.util.Map.<String, FeatureValue>of("curve", structList(java.util.List.of(
                java.util.Map.<String, FeatureValue>of("t", number(1), "val", number(90)),
                java.util.Map.<String, FeatureValue>of("t", number(2), "val", number(10)))));
        double score = CbrSimilarityScorer.score(q, c, java.util.Map.of(), schema);
        assertThat(score).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    void editDistanceSpec_weightedSubstitution_affectsScore() {
        var spec = new SimilaritySpec.EditDistanceSpec(java.util.Map.of(
                "MACRO", java.util.Map.of("DEFENSIVE", 0.8)));
        var schema = CbrFeatureSchema.of("seq-test",
                                         FeatureField.discreteSequence("phases", spec));
        var    q        = java.util.Map.<String, FeatureValue>of("phases", stringList("MACRO"));
        var    c        = java.util.Map.<String, FeatureValue>of("phases", stringList("DEFENSIVE"));
        double withSpec = CbrSimilarityScorer.score(q, c, Map.of(), schema);

        var schemaNoSpec = CbrFeatureSchema.of("seq-test2",
                                               FeatureField.discreteSequence("phases"));
        double withoutSpec = CbrSimilarityScorer.score(q, c, Map.of(), schemaNoSpec);

        assertThat(withSpec).isGreaterThan(withoutSpec);
    }

    @Test
    void scoreDetailed_returns_breakdown() {
        CbrFeatureSchema schema = CbrFeatureSchema.of("test",
            FeatureField.numeric("temperature", 0.0, 100.0),
            FeatureField.categorical("severity"));

        Map<String, FeatureValue> query = Map.of("temperature", number(50.0), "severity", string("high"));
        Map<String, FeatureValue> stored = Map.of("temperature", number(60.0), "severity", string("high"));
        Map<String, Double> weights = Map.of("temperature", 2.0, "severity", 1.0);

        CbrSimilarityScorer.SimilarityBreakdown breakdown =
            CbrSimilarityScorer.scoreDetailed(query, stored, weights, schema, Map.of());

        assertThat(breakdown.featureSimilarities()).hasSize(2);
        assertThat(breakdown.featureSimilarities()).containsKey("temperature");
        assertThat(breakdown.featureSimilarities()).containsKey("severity");
        double sum = breakdown.featureSimilarities().values().stream()
            .mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(breakdown.score(), org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void scoreDetailed_matches_score() {
        Map<String, FeatureValue> query = Map.of("score", number(80.0));
        Map<String, FeatureValue> stored = Map.of("score", number(60.0));

        double oldScore = CbrSimilarityScorer.score(query, stored, Map.of(), SCHEMA);
        CbrSimilarityScorer.SimilarityBreakdown breakdown =
            CbrSimilarityScorer.scoreDetailed(query, stored, Map.of(), SCHEMA, Map.of());

        assertThat(breakdown.score()).isCloseTo(oldScore, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void scoreDetailed_empty_features_returns_empty_breakdown() {
        CbrSimilarityScorer.SimilarityBreakdown breakdown =
            CbrSimilarityScorer.scoreDetailed(Map.of(), Map.of("score", number(50.0)), Map.of(), SCHEMA, Map.of());

        assertThat(breakdown.score()).isEqualTo(1.0);
        assertThat(breakdown.featureSimilarities()).isEmpty();
    }

    @Test
    void scoreDetailed_null_schema_returns_empty_breakdown() {
        CbrSimilarityScorer.SimilarityBreakdown breakdown =
            CbrSimilarityScorer.scoreDetailed(Map.of("score", number(50.0)), Map.of("score", number(50.0)), Map.of(), null, Map.of());

        assertThat(breakdown.score()).isEqualTo(1.0);
        assertThat(breakdown.featureSimilarities()).isEmpty();
    }

    @Test
    void scoreDetailed_single_feature_contribution_equals_score() {
        Map<String, FeatureValue> query = Map.of("color", string("red"));
        Map<String, FeatureValue> stored = Map.of("color", string("red"));

        CbrSimilarityScorer.SimilarityBreakdown breakdown =
            CbrSimilarityScorer.scoreDetailed(query, stored, Map.of(), SCHEMA, Map.of());

        assertThat(breakdown.score()).isEqualTo(1.0);
        assertThat(breakdown.featureSimilarities().get("color")).isEqualTo(1.0);
    }

    @Test
    void structuredField_overrideRespected() {
        var schema = CbrFeatureSchema.of("test",
                                         FeatureField.categorical("cat"),
                                         FeatureField.categoricalList("tags"));

        Map<String, FeatureValue> query = Map.of("cat", FeatureValue.string("a"),
                           "tags", FeatureValue.stringList("x", "y", "z"));
        Map<String, FeatureValue> caseF = Map.of("cat", FeatureValue.string("a"),
                           "tags", FeatureValue.stringList("x", "y"));

        double scoreWithout = CbrSimilarityScorer.score(query, caseF,
                                                        Map.of("cat", 1.0, "tags", 1.0), schema);
        assertThat(scoreWithout).isEqualTo(1.0);

        LocalSimilarityFunction jaccard = (q, c) -> {
            if (q instanceof FeatureValue.StringListVal ql
                && c instanceof FeatureValue.StringListVal cl) {
                var union = new java.util.HashSet<>(ql.values());
                union.addAll(cl.values());
                long intersection = ql.values().stream().filter(cl.values()::contains).count();
                return union.isEmpty() ? 1.0 : (double) intersection / union.size();
            }
            return 0.0;
        };

        double scoreWith = CbrSimilarityScorer.score(query, caseF,
                                                     Map.of("cat", 1.0, "tags", 1.0), schema,
                                                     Map.of("tags", jaccard));
        assertThat(scoreWith).isCloseTo(0.833, org.assertj.core.data.Offset.offset(0.01));
    }
}
