package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.OutcomeWeightingFunction;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OutcomeWeightingCbrCaseMemoryStoreTest {

    private final DefaultOutcomeWeightingFunction fn = new DefaultOutcomeWeightingFunction(0.3);

    @Test void successfulCaseRanksHigher() {
        var highConf = testCase("high", 0.9);
        var lowConf = testCase("low", 0.5);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(lowConf, "c1", 0.8, false, Map.of()),
                new ScoredCbrCase<>(highConf, "c2", 0.8, false, Map.of())));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().confidence()).isEqualTo(0.9);
    }

    @Test void nullConfidence_treatedAsOne() {
        var noOutcome = testCase("none", null);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(noOutcome, "c1", 0.8, false, Map.of())));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().score()).isCloseTo(0.8, within(1e-9));
    }

    @Test void allConfidenceOne_orderUnchanged() {
        var a = testCase("a", 1.0);
        var b = testCase("b", 1.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(a, "c1", 0.9, false, Map.of()),
                new ScoredCbrCase<>(b, "c2", 0.7, false, Map.of())));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("a");
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("b");
    }

    @Test void influenceZero_noEffect() {
        var lowConf = testCase("low", 0.1);
        var noEffect = new DefaultOutcomeWeightingFunction(0.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(lowConf, "c1", 0.8, false, Map.of())));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, noEffect);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().score()).isCloseTo(0.8, within(1e-9));
    }

    @Test void preservesCaseIdAndRerankedFlag() {
        var c = testCase("p", 0.8);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "case-42", 0.9, true, Map.of("f", 0.95))));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var result = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class).getFirst();
        assertThat(result.caseId()).isEqualTo("case-42");
        assertThat(result.reranked()).isTrue();
        assertThat(result.featureSimilarities()).containsEntry("f", 0.95);
    }

    @Test void emptyResults_noError() {
        var delegate = stubDelegate(List.of());
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test void customWeightingFunction_applied() {
        var c = testCase("p", 0.8);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "c1", 0.9, false, Map.of())));
        OutcomeWeightingFunction custom = (sim, conf) -> sim * conf;
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, custom);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().score()).isCloseTo(0.9 * 0.8, within(1e-9));
    }

    @Test void resortsAfterWeighting() {
        var highSim = testCase("highSim", 0.3);
        var lowSim = testCase("lowSim", 1.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(highSim, "c1", 0.9, false, Map.of()),
                new ScoredCbrCase<>(lowSim, "c2", 0.5, false, Map.of())));
        var decorator = new OutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
    }

    private FeatureVectorCbrCase testCase(String problem, Double confidence) {
        return new FeatureVectorCbrCase(problem, "sol", null, confidence, Map.of());
    }

    private CbrQuery testQuery() {
        return CbrQuery.of("t1", new MemoryDomain("cbr"), "default", Map.of(), 10);
    }

    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubDelegate(List<ScoredCbrCase<FeatureVectorCbrCase>> results) {
        return new CbrCaseMemoryStore() {
            @Override public void registerSchema(CbrFeatureSchema schema) {}
            @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid) { return "id"; }
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                return (List<ScoredCbrCase<C>>) (List<?>) results;
            }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String e, String t) { return 0; }
            @Override public void recordOutcome(String c, String t, CbrOutcome o) {}
            @Override public Integer purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy p) { return 0; }
        };
    }
}
