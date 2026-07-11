package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EditDistanceSimilarityTest {

    @Test
    void identicalSequences_perfectSimilarity() {
        assertThat(EditDistanceSimilarity.compute(
                List.of("A", "B", "C"), List.of("A", "B", "C")).score())
                .isEqualTo(1.0);
    }

    @Test
    void oneSubstitution() {
        double sim = EditDistanceSimilarity.compute(
                List.of("A", "B", "C"), List.of("A", "X", "C")).score();
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void completelyDifferent() {
        double sim = EditDistanceSimilarity.compute(
                List.of("A", "B", "C"), List.of("X", "Y", "Z")).score();
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void bothEmpty_perfectSimilarity() {
        assertThat(EditDistanceSimilarity.compute(List.of(), List.of()).score())
                .isEqualTo(1.0);
    }

    @Test
    void oneEmpty_zeroSimilarity() {
        assertThat(EditDistanceSimilarity.compute(List.of("A", "B"), List.of()).score())
                .isEqualTo(0.0);
        assertThat(EditDistanceSimilarity.compute(List.of(), List.of("A", "B")).score())
                .isEqualTo(0.0);
    }

    @Test
    void insertion() {
        double sim = EditDistanceSimilarity.compute(
                List.of("A", "B"), List.of("A", "X", "B")).score();
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void deletion() {
        double sim = EditDistanceSimilarity.compute(
                List.of("A", "X", "B"), List.of("A", "B")).score();
        assertThat(sim).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void singleElementMatch() {
        assertThat(EditDistanceSimilarity.compute(
                List.of("A"), List.of("A")).score()).isEqualTo(1.0);
    }

    @Test
    void singleElementMismatch() {
        assertThat(EditDistanceSimilarity.compute(
                List.of("A"), List.of("B")).score()).isEqualTo(0.0);
    }

    @Test
    void alignmentPath_identicalSequences_allMatch() {
        var result = EditDistanceSimilarity.compute(
                List.of("A", "B", "C"), List.of("A", "B", "C"));
        assertThat(result.alignment()).containsExactly(
                new EditStep(0, 0, EditOp.MATCH),
                new EditStep(1, 1, EditOp.MATCH),
                new EditStep(2, 2, EditOp.MATCH));
    }

    @Test
    void alignmentPath_substitution() {
        var result = EditDistanceSimilarity.compute(
                List.of("A", "B", "C"), List.of("A", "X", "C"));
        assertThat(result.alignment()).contains(
                new EditStep(1, 1, EditOp.SUBSTITUTE));
    }

    @Test
    void alignmentPath_insertionAndDeletion() {
        var result = EditDistanceSimilarity.compute(
                List.of("A", "B"), List.of("A", "X", "B"));
        long inserts = result.alignment().stream()
                             .filter(s -> s.operation() == EditOp.INSERT).count();
        long deletes = result.alignment().stream()
                             .filter(s -> s.operation() == EditOp.DELETE).count();
        assertThat(inserts + deletes).isGreaterThan(0);
    }

    @Test
    void alignmentPath_deleteSteps_caseIndexMinusOne() {
        var result = EditDistanceSimilarity.compute(
                List.of("A", "B"), List.of("B"));
        var deleteSteps = result.alignment().stream()
                                .filter(s -> s.operation() == EditOp.DELETE).toList();
        for (var step : deleteSteps) {
            assertThat(step.queryIndex()).isGreaterThanOrEqualTo(0);
            assertThat(step.caseIndex()).isEqualTo(-1);
        }
    }

    @Test
    void alignmentPath_insertSteps_queryIndexMinusOne() {
        var result = EditDistanceSimilarity.compute(
                List.of("A"), List.of("A", "B"));
        var insertSteps = result.alignment().stream()
                                .filter(s -> s.operation() == EditOp.INSERT).toList();
        for (var step : insertSteps) {
            assertThat(step.queryIndex()).isEqualTo(-1);
            assertThat(step.caseIndex()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void alignmentPath_bothEmpty_emptyPath() {
        var result = EditDistanceSimilarity.compute(List.of(), List.of());
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.alignment()).isEmpty();
    }

    @Test
    void alignmentPath_oneEmpty_nonEmptyPath_allDeletes() {
        var result = EditDistanceSimilarity.compute(
                List.of("A", "B"), List.of());
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).hasSize(2);
        assertThat(result.alignment()).allMatch(s -> s.operation() == EditOp.DELETE);
    }

    @Test
    void alignmentPath_emptyQueryNonEmptyCase_allInserts() {
        var result = EditDistanceSimilarity.compute(
                List.of(), List.of("A", "B"));
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.alignment()).hasSize(2);
        assertThat(result.alignment()).allMatch(s -> s.operation() == EditOp.INSERT);
    }

    @Test
    void weightedSubstitution_closerLabelsLowerCost() {
        var subSim = java.util.Map.of("A", java.util.Map.of("B", 0.8));
        var close = EditDistanceSimilarity.compute(
                List.of("A"), List.of("B"), subSim);
        var uniform = EditDistanceSimilarity.compute(
                List.of("A"), List.of("B"));
        assertThat(close.score()).isGreaterThan(uniform.score());}

    @Test
    void weightedSubstitution_symmetricLookup() {
        var spec   = new SimilaritySpec.EditDistanceSpec(java.util.Map.of("A", java.util.Map.of("B", 0.7)));
        var subSim = spec.substitutionSimilarities();
        var forward = EditDistanceSimilarity.compute(
                List.of("A"), List.of("B"), subSim);
        var reverse = EditDistanceSimilarity.compute(
                List.of("B"), List.of("A"), subSim);
        assertThat(forward.score()).isCloseTo(reverse.score(), within(0.0001));}

    @Test
    void weightedSubstitution_unspecifiedPairsDefaultToUniform() {
        var subSim = java.util.Map.of("A", java.util.Map.of("B", 0.8));
        var result = EditDistanceSimilarity.compute(
                List.of("X"), List.of("Y"), subSim);
        var uniform = EditDistanceSimilarity.compute(
                List.of("X"), List.of("Y"));
        assertThat(result.score()).isCloseTo(uniform.score(), within(0.0001));}

    @Test
    void weightedSubstitution_fractionalDistance() {
        var subSim = java.util.Map.of("A", java.util.Map.of("B", 0.5));
        var result = EditDistanceSimilarity.compute(
                List.of("A"), List.of("B"), subSim);
        assertThat(result.score()).isCloseTo(0.5, within(0.0001));}

    @Test
    void weightedSubstitution_changesAlignmentPath() {
        var subSim = java.util.Map.of("A", java.util.Map.of("B", 0.8));
        var result = EditDistanceSimilarity.compute(
                List.of("A", "A"), List.of("B", "C"), subSim);
        assertThat(result.alignment()).anyMatch(
                s -> s.operation() == EditOp.SUBSTITUTE);}
}
