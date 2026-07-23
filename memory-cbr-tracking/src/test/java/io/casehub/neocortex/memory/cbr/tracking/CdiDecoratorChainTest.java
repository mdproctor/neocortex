package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

@QuarkusTest
class CdiDecoratorChainTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    @Inject
    CbrCaseMemoryStore store;

    @Inject
    EventCollector eventCollector;
    @Inject
    ReactiveCbrCaseMemoryStore reactiveStore;

    @Inject
    CbrRetrievalTracker tracker;


    @BeforeEach
    void clearState() {
        eventCollector.events().clear();
    }

    @Test
    void trackingCapturesPostWeightedPostRerankedScores() {
        store.registerSchema(new CbrFeatureSchema("default",
                List.of(new FeatureField.Numeric("severity", 0.0, 10.0, null)),
                null));

        var c1 = new FeatureVectorCbrCase("problem-alpha", "summary1", null, 0.5,
                Map.of("severity", FeatureValue.number(3.0)));
        var c2 = new FeatureVectorCbrCase("problem-beta", "summary2", null, 1.0,
                Map.of("severity", FeatureValue.number(7.0)));
        store.store(c1, "default", "e1", CBR, "t1", "case1", Path.root());
        store.store(c2, "default", "e1", CBR, "t1", "case2", Path.root());

        var query = CbrQuery.of("t1", CBR, Path.root(), "default",
                Map.of("severity", FeatureValue.number(5.0)), 10)
                .withProblem("problem-alpha");

        List<ScoredCbrCase<FeatureVectorCbrCase>> results =
                store.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).isNotEmpty();

        assertThat(eventCollector.events()).as("tracking decorator should fire exactly one event")
                .hasSize(1);

        CbrRetrievalRecorded event = eventCollector.events().getFirst();
        List<CbrRetrievalTrace.TracedCase> traced = event.results();
        assertThat(traced).hasSameSizeAs(results);

        for (int i = 0; i < results.size(); i++) {
            assertThat(traced.get(i).score())
                    .as("tracked score should match what the caller received")
                    .isCloseTo(results.get(i).score(), offset(0.001));
        }

        assertThat(results.stream().anyMatch(ScoredCbrCase::reranked))
                .as("reranking decorator should have marked results as reranked")
                .isTrue();
    }

    @Test
    void reactivePathRecordsExactlyOnce_bridgeGuardPreventsDoubleRecording() {
        reactiveStore.registerSchema(new CbrFeatureSchema("guard-type",
                                                          List.of(new FeatureField.Numeric("score", 0.0, 10.0, null)),
                                                          null)).await().indefinitely();

        var c = new FeatureVectorCbrCase("problem-guard", "summary", null, 0.8,
                                         Map.of("score", FeatureValue.number(5.0)));
        reactiveStore.store(c, "guard-type", "e1", CBR, "t1", "case-g1", Path.root())
                     .await().indefinitely();

        var query = CbrQuery.of("t1", CBR, Path.root(), "guard-type",
                                Map.of("score", FeatureValue.number(5.0)), 10);

        reactiveStore.retrieveSimilar(query, FeatureVectorCbrCase.class)
                     .await().indefinitely();

        var traces = tracker.findTraces("guard-type", "t1", CBR,
                                        Instant.EPOCH, Instant.now().plusSeconds(60));
        assertThat(traces)
                .as("bridge-active path should record exactly one trace (blocking side only)")
                .hasSize(1);
    }


    @Singleton
    static class EventCollector {
        private final List<CbrRetrievalRecorded> events = new CopyOnWriteArrayList<>();

        void onEvent(@Observes CbrRetrievalRecorded event) {
            events.add(event);
        }

        List<CbrRetrievalRecorded> events() {
            return events;
        }
    }

    @ApplicationScoped
    static class TestConfig {
        @Produces
        @ApplicationScoped
        CrossEncoderReranker crossEncoderReranker() {
            var model = InMemoryInferenceModel.withFunction(1, (InferenceInput input) -> {
                String text = input.texts().isEmpty() ? "" : input.texts().getFirst();
                float score = text.contains("alpha") ? 2.0f : 0.5f;
                return new float[]{score};
            });
            return new CrossEncoderReranker(model);
        }
    }
}
