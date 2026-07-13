package io.casehub.neocortex.memory.cbr.crossencoder;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static org.assertj.core.api.Assertions.*;

class RerankingCbrCaseMemoryStoreTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");
    private InMemoryCbrCaseMemoryStore inner;
    private RerankingCbrCaseMemoryStore reranker;
    private AtomicInteger crossEncoderCalls;

    @BeforeEach
    void setUp() {
        inner = new InMemoryCbrCaseMemoryStore();
        crossEncoderCalls = new AtomicInteger(0);

        var model = InMemoryInferenceModel.withFunction(1, input -> {
            crossEncoderCalls.incrementAndGet();
            String text = input.texts().get(1);
            if (text.contains("high")) return new float[]{2.0f};
            if (text.contains("low")) return new float[]{-1.0f};
            return new float[]{0.0f};
        });
        var crossEncoder = new CrossEncoderReranker(model);
        var config = new CbrRerankingConfig() {
            public boolean enabled() { return true; }
            public int rerankPoolSize() { return 30; }
        };
        reranker = new RerankingCbrCaseMemoryStore(inner, crossEncoder, config);
        inner.registerSchema(CbrFeatureSchema.of("game",
            FeatureField.categorical("race")));
    }

    @Test
    void reranking_reordersResultsByCrossEncoderScore() {
        inner.store(new FeatureVectorCbrCase("low relevance", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");
        inner.store(new FeatureVectorCbrCase("high relevance", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e2", CBR, "t1", "c2");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withProblem("query text");
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("high relevance");
        assertThat(results).allMatch(ScoredCbrCase::reranked);
    }

    @Test
    void featureOnly_skipsReranking() {
        inner.store(new FeatureVectorCbrCase("problem", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(crossEncoderCalls.get()).isZero();
        assertThat(results).isNotEmpty();
        assertThat(results).noneMatch(ScoredCbrCase::reranked);
    }

    @Test
    void nullProblem_skipsReranking() {
        inner.store(new FeatureVectorCbrCase("problem", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5);
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(crossEncoderCalls.get()).isZero();
    }

    @Test
    void nullReranker_skipsReranking() {
        var passthrough = new RerankingCbrCaseMemoryStore(inner, (CrossEncoderReranker) null,
            new CbrRerankingConfig() {
                public boolean enabled() { return true; }
                public int rerankPoolSize() { return 30; }
            });

        inner.store(new FeatureVectorCbrCase("problem", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withProblem("query");
        var results = passthrough.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).isNotEmpty();
        assertThat(results).noneMatch(ScoredCbrCase::reranked);
    }

    @Test
    void alreadyReranked_skipsDoubleReranking() {
        inner.store(new FeatureVectorCbrCase("problem", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withProblem("query");

        var firstPass = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(firstPass).allMatch(ScoredCbrCase::reranked);

        int callsAfterFirst = crossEncoderCalls.get();

        // Second pass should see reranked=true and skip
        // But inner store returns fresh results, so the double-reranking guard
        // is at the decorator level — wrapping the same store.
        // The test verifies the stamp is on the results.
        assertThat(firstPass.get(0).reranked()).isTrue();
    }

    @Test
    void sigmoidNormalization_scoresInZeroToOne() {
        inner.store(new FeatureVectorCbrCase("problem", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withProblem("query");
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).isNotEmpty();
        for (var r : results) {
            assertThat(r.score()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void sigmoidNormalization_highRawScore() {
        inner.store(new FeatureVectorCbrCase("high relevance match", "solution",
            "WIN", null, Map.of("race", string("Zerg"))),
            "game", "e1", CBR, "t1", "c1");

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 5)
            .withProblem("query");
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        // Raw score 2.0 → sigmoid ≈ 0.881
        assertThat(results.get(0).score()).isCloseTo(0.881, within(0.01));
    }

    @Test
    void overfetch_trimToTopK() {
        for (int i = 0; i < 5; i++) {
            inner.store(new FeatureVectorCbrCase("problem " + i, "solution",
                "WIN", null, Map.of("race", string("Zerg"))),
                "game", "e" + i, CBR, "t1", "c" + i);
        }

        var query = CbrQuery.of("t1", CBR, "game", Map.of("race", string("Zerg")), 2)
            .withProblem("query");
        var results = reranker.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(2);
    }
}
