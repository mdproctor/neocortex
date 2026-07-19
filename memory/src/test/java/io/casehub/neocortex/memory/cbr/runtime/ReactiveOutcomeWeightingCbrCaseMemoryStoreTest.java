package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ReactiveOutcomeWeightingCbrCaseMemoryStoreTest {

    private final DefaultOutcomeWeightingFunction fn = new DefaultOutcomeWeightingFunction(0.3);

    @Test void successfulCaseRanksHigher() {
        var highConf = testCase("high", 0.9);
        var lowConf = testCase("low", 0.5);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(lowConf, "c1", 0.8, false, Map.of(), null, io.casehub.platform.api.path.Path.root()),
                new ScoredCbrCase<>(highConf, "c2", 0.8, false, Map.of(), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results.getFirst().cbrCase().confidence()).isEqualTo(0.9);
    }

    @Test void nullConfidence_treatedAsOne() {
        var noOutcome = testCase("none", null);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(noOutcome, "c1", 0.8, false, Map.of(), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results.getFirst().score()).isCloseTo(0.8, within(1e-9));
    }

    @Test void allConfidenceOne_orderUnchanged() {
        var a = testCase("a", 1.0);
        var b = testCase("b", 1.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(a, "c1", 0.9, false, Map.of(), null, io.casehub.platform.api.path.Path.root()),
                new ScoredCbrCase<>(b, "c2", 0.7, false, Map.of(), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("a");
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("b");
    }

    @Test void influenceZero_noEffect() {
        var lowConf = testCase("low", 0.1);
        var noEffect = new DefaultOutcomeWeightingFunction(0.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(lowConf, "c1", 0.8, false, Map.of(), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, noEffect);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results.getFirst().score()).isCloseTo(0.8, within(1e-9));
    }

    @Test void preservesCaseIdAndRerankedFlag() {
        var c = testCase("p", 0.8);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "case-42", 0.9, true, Map.of("f", 0.95), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var result = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely().getFirst();
        assertThat(result.caseId()).isEqualTo("case-42");
        assertThat(result.reranked()).isTrue();
        assertThat(result.featureSimilarities()).containsEntry("f", 0.95);
    }

    @Test void emptyResults_noError() {
        var delegate = stubDelegate(List.of());
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results).isEmpty();
    }

    @Test void resortsAfterWeighting() {
        var highSim = testCase("highSim", 0.3);
        var lowSim = testCase("lowSim", 1.0);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(highSim, "c1", 0.9, false, Map.of(), null, io.casehub.platform.api.path.Path.root()),
                new ScoredCbrCase<>(lowSim, "c2", 0.5, false, Map.of(), null, io.casehub.platform.api.path.Path.root())));
        var decorator = new ReactiveOutcomeWeightingCbrCaseMemoryStore(delegate, fn);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class)
                .await().indefinitely();
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
    }

    private FeatureVectorCbrCase testCase(String problem, Double confidence) {
        return new FeatureVectorCbrCase(problem, "sol", null, confidence, Map.of());
    }

    private CbrQuery testQuery() {
        return CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 10);
    }

    @SuppressWarnings("unchecked")
    private ReactiveCbrCaseMemoryStore stubDelegate(List<ScoredCbrCase<FeatureVectorCbrCase>> results) {
        return new ReactiveCbrCaseMemoryStore() {
            @Override public Uni<Void> registerSchema(CbrFeatureSchema s) { return Uni.createFrom().voidItem(); }
            @Override public Uni<String> store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return Uni.createFrom().item("id"); }
            @Override public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                return Uni.createFrom().item((List<ScoredCbrCase<C>>) (List<?>) results);
            }
            @Override public Uni<Integer> erase(EraseRequest r) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseEntity(String e, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> recordOutcome(String c, String t, CbrOutcome o) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Integer> purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy p) { return Uni.createFrom().item(0); }
            @Override public Uni<Void> supersede(String c, String t, String s, String r) { return Uni.createFrom().voidItem(); }
            @Override public Uni<Void> reinstate(String c, String t) { return Uni.createFrom().voidItem(); }

        @Override public io.smallrye.mutiny.Uni<io.casehub.neocortex.memory.cbr.SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) { return io.smallrye.mutiny.Uni.createFrom().item(io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED); }
        @Override public io.smallrye.mutiny.Uni<java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return io.smallrye.mutiny.Uni.createFrom().item(java.util.List.of()); }

        };
    }
}
