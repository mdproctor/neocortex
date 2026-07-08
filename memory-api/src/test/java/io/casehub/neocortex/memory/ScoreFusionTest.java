package io.casehub.neocortex.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ScoreFusionTest {

    record Item(String id, double score) {}

    private static ScoreFusion.ScoredLeg<Item> leg(double weight, Item... items) {
        return new ScoreFusion.ScoredLeg<>(List.of(items), Item::score, weight);
    }

    private static Item item(String id, double score) {
        return new Item(id, score);
    }

    // --- RRF tests ---

    @Test
    void rrf_twoLegs_disjointCandidates_rankBasedScoring() {
        var legA = leg(1.0, item("a", 0.9), item("b", 0.8));
        var legB = leg(1.0, item("c", 0.7), item("d", 0.6));
        var results = ScoreFusion.rrf(List.of(legA, legB), Item::id, 4, 60);
        assertThat(results).hasSize(4);
        // a and c both rank 1 in their legs: 1/(60+1) each
        // normalized by 2/(60+1) → 0.5
        assertThat(results.get(0).score()).isCloseTo(0.5, within(0.01));
        assertThat(results.get(1).score()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void rrf_twoLegs_overlappingCandidates_scoresAccumulate() {
        var legA = leg(1.0, item("a", 0.9), item("b", 0.8));
        var legB = leg(1.0, item("a", 0.7), item("c", 0.6));
        var results = ScoreFusion.rrf(List.of(legA, legB), Item::id, 3, 60);
        // a: rank 1 in both → 1/(60+1) + 1/(60+1) = 2/61
        // normalized by 2/61 = 1.0
        assertThat(results.get(0).item().id()).isEqualTo("a");
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void rrf_scoreNormalization_outputInZeroToOne() {
        var legA = leg(1.0, item("a", 0.9));
        var legB = leg(1.0, item("b", 0.8));
        var results = ScoreFusion.rrf(List.of(legA, legB), Item::id, 10, 60);
        for (var r : results) {
            assertThat(r.score()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void rrf_sortsInternallyByScore_ranksDerivedCorrectly() {
        var leg = leg(1.0, item("a", 0.3), item("b", 0.9), item("c", 0.6));
        var results = ScoreFusion.rrf(List.of(leg), Item::id, 3, 60);
        assertThat(results.get(0).item().id()).isEqualTo("b");
    }

    @Test
    void rrf_singleLeg_degeneratesToPassthrough() {
        var leg = leg(1.0, item("a", 0.9), item("b", 0.5));
        var results = ScoreFusion.rrf(List.of(leg), Item::id, 10, 60);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).item().id()).isEqualTo("a");
    }

    @Test
    void rrf_emptyLegs_returnsEmpty() {
        List<ScoreFusion.ScoredLeg<Item>> empty = List.of();
        var results = ScoreFusion.rrf(empty, Item::id, 10, 60);
        assertThat(results).isEmpty();
    }

    @Test
    void rrf_emptyLeg_handledGracefully() {
        var legA = leg(1.0, item("a", 0.9));
        var legB = new ScoreFusion.ScoredLeg<>(List.<Item>of(), Item::score, 1.0);
        var results = ScoreFusion.rrf(List.of(legA, legB), Item::id, 10, 60);
        assertThat(results).hasSize(1);
    }

    @Test
    void rrf_topK_trims() {
        var leg = leg(1.0, item("a", 0.9), item("b", 0.8), item("c", 0.7));
        var results = ScoreFusion.rrf(List.of(leg), Item::id, 2, 60);
        assertThat(results).hasSize(2);
    }

    // --- CC tests ---

    @Test
    void cc_twoLegs_minMaxNormalizationAndWeightedSum() {
        var legA = leg(0.6, item("a", 0.2), item("b", 0.8));
        var legB = leg(0.4, item("a", 0.9), item("b", 0.1));
        var results = ScoreFusion.convexCombination(
            List.of(legA, legB), Item::id, 10);
        // legA normalized: b=1.0, a=0.0. legB normalized: a=1.0, b=0.0
        // a: 0.6*0.0 + 0.4*1.0 = 0.4
        // b: 0.6*1.0 + 0.4*0.0 = 0.6
        assertThat(results.get(0).item().id()).isEqualTo("b");
        assertThat(results.get(0).score()).isCloseTo(0.6, within(0.01));
        assertThat(results.get(1).item().id()).isEqualTo("a");
        assertThat(results.get(1).score()).isCloseTo(0.4, within(0.01));
    }

    @Test
    void cc_disjointCandidates_absentContributesZero() {
        var legA = leg(0.5, item("a", 0.9));
        var legB = leg(0.5, item("b", 0.8));
        var results = ScoreFusion.convexCombination(
            List.of(legA, legB), Item::id, 10);
        // single item per leg → min=max → normalized to 1.0
        // a: 0.5*1.0 + 0.5*0.0 = 0.5
        // b: 0.5*0.0 + 0.5*1.0 = 0.5
        assertThat(results).hasSize(2);
        assertThat(results.get(0).score()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void cc_constantScoreLeg_normalizesToOne() {
        var leg = leg(1.0, item("a", 0.5), item("b", 0.5));
        var results = ScoreFusion.convexCombination(
            List.of(leg), Item::id, 10);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.01));
        assertThat(results.get(1).score()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void cc_weightRenormalization() {
        var legA = leg(3.0, item("x", 1.0));
        var legB = leg(1.0, item("x", 1.0));
        var results = ScoreFusion.convexCombination(
            List.of(legA, legB), Item::id, 10);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void cc_singleLeg_degeneratesToPassthrough() {
        var leg = leg(1.0, item("a", 0.9), item("b", 0.5));
        var results = ScoreFusion.convexCombination(
            List.of(leg), Item::id, 10);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).item().id()).isEqualTo("a");
    }

    @Test
    void cc_emptyLegs_returnsEmpty() {
        List<ScoreFusion.ScoredLeg<Item>> empty = List.of();
        var results = ScoreFusion.convexCombination(empty, Item::id, 10);
        assertThat(results).isEmpty();
    }

    @Test
    void cc_topK_trims() {
        var leg = leg(1.0, item("a", 0.9), item("b", 0.8), item("c", 0.7));
        var results = ScoreFusion.convexCombination(
            List.of(leg), Item::id, 2);
        assertThat(results).hasSize(2);
    }
}
