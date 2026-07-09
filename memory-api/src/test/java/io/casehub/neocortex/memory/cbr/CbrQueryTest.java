package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrQueryTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    @Test
    void of_createsValidQuery() {
        var q = CbrQuery.of("tenant1", CBR, "starcraft-game",
            Map.of("race", "Zerg"), 5);
        assertThat(q.tenantId()).isEqualTo("tenant1");
        assertThat(q.domain()).isEqualTo(CBR);
        assertThat(q.caseType()).isEqualTo("starcraft-game");
        assertThat(q.topK()).isEqualTo(5);
        assertThat(q.minSimilarity()).isEqualTo(0.0);
        assertThat(q.notBefore()).isNull();
        assertThat(q.problem()).isNull();
        assertThat(q.weights()).isEmpty();
        assertThat(q.vectorWeight()).isEqualTo(0.5);
    }

    @Test
    void nullTenantIdRejected() {
        assertThatThrownBy(() -> CbrQuery.of(null, CBR, "type", Map.of(), 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullDomainRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", null, "type", Map.of(), 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void topKLessThanOneRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minSimilarityOutOfRangeRejected() {
        assertThatThrownBy(() -> new CbrQuery("t", CBR, "type", Map.of(), Map.of(), 5, 1.5, null, null, 0.5,
                RetrievalMode.HYBRID, FusionStrategy.RRF))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void featuresDefensivelyCopied() {
        var features = new java.util.HashMap<String, Object>();
        features.put("race", "Zerg");
        var q = CbrQuery.of("t", CBR, "type", features, 5);
        features.put("extra", "value");
        assertThat(q.features()).doesNotContainKey("extra");
    }

    @Test
    void blankProblemRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 5).withProblem(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("problem must not be blank");
    }

    @Test
    void withProblem_setsNonNullProblem() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5).withProblem("Zerg rush");
        assertThat(q.problem()).isEqualTo("Zerg rush");
    }

    @Test
    void withMinSimilarity_updatesThreshold() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5).withMinSimilarity(0.7);
        assertThat(q.minSimilarity()).isEqualTo(0.7);
    }

    @Test
    void withNotBefore_updatesTimeBoundary() {
        var now = java.time.Instant.now();
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5).withNotBefore(now);
        assertThat(q.notBefore()).isEqualTo(now);
    }

    @Test
    void vectorWeightOutOfRangeRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 5).withVectorWeight(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vectorWeight");
    }

    @Test
    void negativeVectorWeightRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 5).withVectorWeight(-0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vectorWeight");
    }

    @Test
    void negativeWeightValueRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 5)
                .withWeight("field", -1.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weight for 'field'");
    }

    @Test
    void withWeights_setsWeights() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeights(Map.of("a", 2.0, "b", 1.0));
        assertThat(q.weights()).containsEntry("a", 2.0).containsEntry("b", 1.0);
    }

    @Test
    void withWeight_addsSingleWeight() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeight("a", 2.0).withWeight("b", 1.0);
        assertThat(q.weights()).containsEntry("a", 2.0).containsEntry("b", 1.0);
    }

    @Test
    void withVectorWeight_setsVectorWeight() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5).withVectorWeight(0.3);
        assertThat(q.vectorWeight()).isEqualTo(0.3);
    }

    @Test
    void weightsDefensivelyCopied() {
        var weights = new java.util.HashMap<String, Double>();
        weights.put("a", 1.0);
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5).withWeights(weights);
        weights.put("b", 2.0);
        assertThat(q.weights()).doesNotContainKey("b");
    }

    @Test
    void withProblem_preservesWeightsAndVectorWeight() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeights(Map.of("a", 2.0)).withVectorWeight(0.3)
            .withProblem("test");
        assertThat(q.weights()).containsEntry("a", 2.0);
        assertThat(q.vectorWeight()).isEqualTo(0.3);
    }

    @Test
    void withMinSimilarity_preservesWeightsAndVectorWeight() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeights(Map.of("a", 2.0)).withVectorWeight(0.3)
            .withMinSimilarity(0.5);
        assertThat(q.weights()).containsEntry("a", 2.0);
        assertThat(q.vectorWeight()).isEqualTo(0.3);
    }

    @Test
    void withNotBefore_preservesWeightsAndVectorWeight() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeights(Map.of("a", 2.0)).withVectorWeight(0.3)
            .withNotBefore(java.time.Instant.now());
        assertThat(q.weights()).containsEntry("a", 2.0);
        assertThat(q.vectorWeight()).isEqualTo(0.3);
    }

    @Test
    void of_defaultsToHybridRetrievalMode() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5);
        assertThat(q.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void of_defaultsToRrfFusionStrategy() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5);
        assertThat(q.fusionStrategy()).isEqualTo(FusionStrategy.RRF);
    }

    @Test
    void withRetrievalMode_setsMode() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        assertThat(q.retrievalMode()).isEqualTo(RetrievalMode.FEATURE_ONLY);
    }

    @Test
    void withFusionStrategy_setsStrategy() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withFusionStrategy(FusionStrategy.CC);
        assertThat(q.fusionStrategy()).isEqualTo(FusionStrategy.CC);
    }

    @Test
    void withRetrievalMode_preservesOtherFields() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withWeights(Map.of("a", 2.0)).withVectorWeight(0.3)
            .withProblem("test").withFusionStrategy(FusionStrategy.CC)
            .withRetrievalMode(RetrievalMode.SEMANTIC_ONLY);
        assertThat(q.weights()).containsEntry("a", 2.0);
        assertThat(q.vectorWeight()).isEqualTo(0.3);
        assertThat(q.problem()).isEqualTo("test");
        assertThat(q.fusionStrategy()).isEqualTo(FusionStrategy.CC);
    }

    @Test
    void withFusionStrategy_preservesOtherFields() {
        var q = CbrQuery.of("t", CBR, "type", Map.of(), 5)
            .withRetrievalMode(RetrievalMode.FEATURE_ONLY)
            .withFusionStrategy(FusionStrategy.CC);
        assertThat(q.retrievalMode()).isEqualTo(RetrievalMode.FEATURE_ONLY);
    }
}
