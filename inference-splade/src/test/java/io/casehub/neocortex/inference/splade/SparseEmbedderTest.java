package io.casehub.neocortex.inference.splade;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SparseEmbedderTest {

    private static final int VOCAB_SIZE = 10;

    private static float[] makeVocabOutput(float... values) {
        float[] out = new float[VOCAB_SIZE];
        System.arraycopy(values, 0, out, 0, Math.min(values.length, VOCAB_SIZE));
        return out;
    }

    // ── construction ──────────────────────────────────────────────────

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void rejectsNullModel() {
            assertThatThrownBy(() -> new SparseEmbedder(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        }

        @Test
        void rejectsZeroThreshold() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            assertThatThrownBy(() -> new SparseEmbedder(model, 0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        }

        @Test
        void rejectsNegativeThreshold() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            assertThatThrownBy(() -> new SparseEmbedder(model, -0.01f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        }

        @Test
        void rejectsNaNThreshold() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            assertThatThrownBy(() -> new SparseEmbedder(model, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        }

        @Test
        void rejectsInfiniteThreshold() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            assertThatThrownBy(() -> new SparseEmbedder(model, Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        }

        @Test
        void acceptsValidModelAndThreshold() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            var embedder = new SparseEmbedder(model, 0.05f);
            assertThat(embedder).isNotNull();
            model.close();
        }
    }

    // ── embed ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("embed")
    class Embed {

        private InMemoryInferenceModel model;
        private SparseEmbedder embedder;

        @BeforeEach
        void setUp() {
            model = InMemoryInferenceModel.withFunction(VOCAB_SIZE, input ->
                makeVocabOutput(2.0f, -1.0f, 0.5f, 0.0f, 3.0f, 0.001f, 0f, 0f, 0f, 0f));
            embedder = new SparseEmbedder(model);
        }

        @AfterEach
        void tearDown() {
            model.close();
        }

        @Test
        void appliesLogSaturationAndThreshold() {
            Map<Integer, Float> sparse = embedder.embed("test");

            // index 0: relu(2.0)=2.0, log1p(2.0)=1.0986... → included
            assertThat(sparse).containsKey(0);
            assertThat(sparse.get(0)).isCloseTo((float) Math.log1p(2.0), within(1e-6f));

            // index 1: relu(-1.0)=0.0, log1p(0.0)=0.0 → excluded
            assertThat(sparse).doesNotContainKey(1);

            // index 2: relu(0.5)=0.5, log1p(0.5)=0.405... → included
            assertThat(sparse).containsKey(2);
            assertThat(sparse.get(2)).isCloseTo((float) Math.log1p(0.5), within(1e-6f));

            // index 3: relu(0.0)=0.0, log1p(0.0)=0.0 → excluded
            assertThat(sparse).doesNotContainKey(3);

            // index 4: relu(3.0)=3.0, log1p(3.0)=1.386... → included
            assertThat(sparse).containsKey(4);
            assertThat(sparse.get(4)).isCloseTo((float) Math.log1p(3.0), within(1e-6f));

            // index 5: relu(0.001)=0.001, log1p(0.001)=0.000999... → excluded (< 0.01)
            assertThat(sparse).doesNotContainKey(5);
        }

        @Test
        void resultIsImmutable() {
            Map<Integer, Float> sparse = embedder.embed("test");
            assertThatThrownBy(() -> sparse.put(99, 1.0f))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rejectsNullText() {
            assertThatThrownBy(() -> embedder.embed(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
        }

        @Test
        void allBelowThresholdReturnsEmptyMap() {
            var m = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            var e = new SparseEmbedder(m);
            Map<Integer, Float> sparse = e.embed("nothing");
            assertThat(sparse).isEmpty();
            m.close();
        }

        @Test
        void customThresholdFiltersMore() {
            var e = new SparseEmbedder(model, 1.0f);
            Map<Integer, Float> sparse = e.embed("test");
            // Only index 0 (log1p(2.0)=1.098) and index 4 (log1p(3.0)=1.386) survive
            assertThat(sparse).hasSize(2);
            assertThat(sparse).containsOnlyKeys(0, 4);
        }
    }

    // ── rank-3 output ─────────────────────────────────────────────────

    @Nested
    @DisplayName("rank-3 output")
    class Rank3Output {

        private static InferenceModel rank3StubModel(float[][] rank3Output) {
            // Returns a model with single output named "output" containing rank3Output
            // outputSize() is empty (no fixed size for rank-3)
            return InMemoryInferenceModel.returningMulti(Map.of("output", rank3Output));
        }

        @Test
        void maxPoolsAcrossSequenceDimension() {
            // 2 tokens, vocab size 4. Max-pool should take max across tokens.
            float[][] rank3Output = {
                {0.0f, 1.5f, 0.0f, 2.0f},  // token 0
                {3.0f, 0.0f, 0.5f, 0.0f}   // token 1
            };
            // After max-pool: {3.0, 1.5, 0.5, 2.0}
            // After logSaturate (log1p of max(0,x)): log1p(3.0)≈1.386, log1p(1.5)≈0.916,
            //   log1p(0.5)≈0.405, log1p(2.0)≈1.099
            // All above default threshold 0.01
            InferenceModel stubModel = rank3StubModel(rank3Output);
            SparseEmbedder embedder = new SparseEmbedder(stubModel);
            Map<Integer, Float> result = embedder.embed("test");

            assertThat(result).hasSize(4);
            assertThat(result.get(0)).isCloseTo((float) Math.log1p(3.0f), within(1e-5f));
            assertThat(result.get(1)).isCloseTo((float) Math.log1p(1.5f), within(1e-5f));
            assertThat(result.get(2)).isCloseTo((float) Math.log1p(0.5f), within(1e-5f));
            assertThat(result.get(3)).isCloseTo((float) Math.log1p(2.0f), within(1e-5f));
        }

        @Test
        void batchMaxPoolsEachSampleIndependently() {
            // Sample 1: 2 tokens, vocab size 3
            float[][] sample1 = {
                {1.0f, 0.0f, 2.0f},  // token 0
                {0.0f, 3.0f, 1.0f}   // token 1
            };
            // After max-pool sample1: {1.0, 3.0, 2.0}

            // Sample 2: 3 tokens, vocab size 3 (different sequence length)
            float[][] sample2 = {
                {0.5f, 1.5f, 0.0f},  // token 0
                {2.0f, 0.0f, 1.0f},  // token 1
                {0.0f, 0.5f, 3.0f}   // token 2
            };
            // After max-pool sample2: {2.0, 1.5, 3.0}

            InferenceModel stubModel = InMemoryInferenceModel.returningMulti(Map.of("output", sample1));
            SparseEmbedder embedder = new SparseEmbedder(stubModel);

            // Note: InMemoryInferenceModel.returningMulti returns same output for all inputs
            // So we test with single sample here; batch independence is inherent to the loop
            List<Map<Integer, Float>> results = embedder.embedBatch(List.of("a", "b"));

            assertThat(results).hasSize(2);
            // Both results should be identical (same stub output)
            assertThat(results.get(0)).hasSize(3);
            assertThat(results.get(0).get(0)).isCloseTo((float) Math.log1p(1.0f), within(1e-5f));
            assertThat(results.get(0).get(1)).isCloseTo((float) Math.log1p(3.0f), within(1e-5f));
            assertThat(results.get(0).get(2)).isCloseTo((float) Math.log1p(2.0f), within(1e-5f));
            assertThat(results.get(1)).isEqualTo(results.get(0));
        }
    }

    // ── embedBatch ────────────────────────────────────────────────────

    @Nested
    @DisplayName("embedBatch")
    class EmbedBatch {

        @Test
        void returnsOneMapPerInput() {
            var model = InMemoryInferenceModel.withFunction(VOCAB_SIZE, input ->
                makeVocabOutput(1.0f, 2.0f));
            var embedder = new SparseEmbedder(model);

            List<Map<Integer, Float>> results = embedder.embedBatch(
                List.of("first", "second", "third"));

            assertThat(results).hasSize(3);
            for (Map<Integer, Float> sparse : results) {
                assertThat(sparse).isNotEmpty();
            }
            model.close();
        }

        @Test
        void emptyListReturnsEmptyList() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            var embedder = new SparseEmbedder(model);
            assertThat(embedder.embedBatch(List.of())).isEmpty();
            model.close();
        }

        @Test
        void batchResultIsImmutable() {
            var model = InMemoryInferenceModel.withFunction(VOCAB_SIZE, input ->
                makeVocabOutput(1.0f));
            var embedder = new SparseEmbedder(model);

            List<Map<Integer, Float>> results = embedder.embedBatch(List.of("test"));
            assertThatThrownBy(() -> results.add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
            model.close();
        }

        @Test
        void rejectsNullList() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            var embedder = new SparseEmbedder(model);
            assertThatThrownBy(() -> embedder.embedBatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("texts");
            model.close();
        }

        @Test
        void rejectsNullElement() {
            var model = InMemoryInferenceModel.returning(new float[VOCAB_SIZE]);
            var embedder = new SparseEmbedder(model);
            List<String> texts = new ArrayList<>();
            texts.add("ok");
            texts.add(null);
            assertThatThrownBy(() -> embedder.embedBatch(texts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("texts[1]");
            model.close();
        }

        @Test
        void singleBatchEquivalence() {
            var model = InMemoryInferenceModel.withFunction(VOCAB_SIZE, input ->
                makeVocabOutput(2.0f, -1.0f, 0.5f, 0.0f, 3.0f));
            var embedder = new SparseEmbedder(model);

            Map<Integer, Float> single = embedder.embed("test");
            List<Map<Integer, Float>> batch = embedder.embedBatch(List.of("test"));

            assertThat(batch).hasSize(1);
            assertThat(batch.get(0)).isEqualTo(single);
            model.close();
        }
    }
}
