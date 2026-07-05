package io.casehub.neocortex.inference.runtime;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceOutput;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OnnxInferenceModelTest {

    private static final Path TEST_MODEL_DIR = Path.of("src/test/resources/test-model");
    private static final Path MODEL_PATH = TEST_MODEL_DIR.resolve("model.onnx");
    private static final Path TOKENIZER_PATH = TEST_MODEL_DIR.resolve("tokenizer.json");
    private static final Path WRONG_INPUTS_MODEL = TEST_MODEL_DIR.resolve("wrong-inputs-model.onnx");
    private static final Path BERT_MODEL_PATH = TEST_MODEL_DIR.resolve("bert-model.onnx");
    private static final Path MULTI_OUTPUT_MODEL = TEST_MODEL_DIR.resolve("multi-output-model.onnx");
    private static final Path RANK3_OUTPUT_MODEL = TEST_MODEL_DIR.resolve("rank3-output-model.onnx");
    private static final Path MULTI_RANK3_MODEL = TEST_MODEL_DIR.resolve("multi-rank3-model.onnx");

    // ── load + run ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("load and run")
    class LoadAndRun {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void loadAndRunSingleText() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThat(out.values()).hasSize(3);
        }

        @Test
        void loadAndRunTextPair() {
            InferenceOutput out = model.run(InferenceInput.pair("premise", "hypothesis"));
            assertThat(out.values()).hasSize(3);
        }

        @Test
        void outputSizeReturnsThree() {
            assertThat(model.outputSize()).isEqualTo(OptionalInt.of(3));
        }
    }

    // ── batch ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("runBatch")
    class Batch {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void runBatchReturnsOneOutputPerInput() {
            List<InferenceInput> inputs = List.of(
                InferenceInput.of("first"),
                InferenceInput.of("second"),
                InferenceInput.of("third")
            );
            List<InferenceOutput> outputs = model.runBatch(inputs);
            assertThat(outputs).hasSize(3);
            for (InferenceOutput out : outputs) {
                assertThat(out.values()).hasSize(3);
            }
        }

        @Test
        void runBatchEmptyReturnsEmpty() {
            assertThat(model.runBatch(List.of())).isEmpty();
        }

        @Test
        void batchSingleEquivalence() {
            InferenceInput input = InferenceInput.of("test equivalence");
            InferenceOutput single = model.run(input);
            List<InferenceOutput> batch = model.runBatch(List.of(input));

            assertThat(batch).hasSize(1);
            float[] singleValues = single.values();
            float[] batchValues = batch.get(0).values();
            assertThat(batchValues).hasSize(singleValues.length);
            for (int i = 0; i < singleValues.length; i++) {
                assertThat(batchValues[i]).isCloseTo(singleValues[i], within(1e-5f));
            }
        }

        @Test
        void runBatchNullListThrowsIAE() {
            assertThatThrownBy(() -> model.runBatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs must not be null");
        }

        @Test
        void runBatchNullElementThrowsIAE() {
            List<InferenceInput> inputs = new ArrayList<>();
            inputs.add(InferenceInput.of("a"));
            inputs.add(null);
            assertThatThrownBy(() -> model.runBatch(inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs[1] must not be null");
        }
    }

    // ── token_type_ids (BERT-style 3-input model) ───────────────────────

    @Nested
    @DisplayName("token_type_ids support")
    class TokenTypeIds {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(BERT_MODEL_PATH, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void loadsModelWithTokenTypeIds() {
            assertThat(model.outputSize()).isEqualTo(OptionalInt.of(3));
        }

        @Test
        void runSingleTextWithTokenTypeIds() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThat(out.values()).hasSize(3);
        }

        @Test
        void runTextPairWithTokenTypeIds() {
            InferenceOutput out = model.run(InferenceInput.pair("premise", "hypothesis"));
            assertThat(out.values()).hasSize(3);
        }

        @Test
        void runBatchWithTokenTypeIds() {
            List<InferenceInput> inputs = List.of(
                InferenceInput.of("first"),
                InferenceInput.of("second"),
                InferenceInput.pair("query", "document")
            );
            List<InferenceOutput> outputs = model.runBatch(inputs);
            assertThat(outputs).hasSize(3);
            for (InferenceOutput out : outputs) {
                assertThat(out.values()).hasSize(3);
            }
        }

        @Test
        void batchSingleEquivalenceWithTokenTypeIds() {
            InferenceInput input = InferenceInput.of("test equivalence");
            InferenceOutput single = model.run(input);
            List<InferenceOutput> batch = model.runBatch(List.of(input));

            assertThat(batch).hasSize(1);
            float[] singleValues = single.values();
            float[] batchValues = batch.get(0).values();
            assertThat(batchValues).hasSize(singleValues.length);
            for (int i = 0; i < singleValues.length; i++) {
                assertThat(batchValues[i]).isCloseTo(singleValues[i], within(1e-5f));
            }
        }
    }

    // ── model loading errors ───────────────────────────────────────────

    @Nested
    @DisplayName("model loading errors")
    class LoadingErrors {

        @Test
        void acceptsModelWithBertInputAliases() {
            ModelConfig config = new ModelConfig(WRONG_INPUTS_MODEL, TOKENIZER_PATH);
            try (OnnxInferenceModel model = new OnnxInferenceModel(config)) {
                InferenceOutput output = model.run(InferenceInput.of("test"));
                assertThat(output).isNotNull();
            }
        }

        @Test
        void acceptsExplicitInputNameOverrides() {
            ModelConfig config = new ModelConfig(WRONG_INPUTS_MODEL, TOKENIZER_PATH, 512, 0, 0,
                Map.of("input_ids", "tokens", "attention_mask", "mask"));
            try (OnnxInferenceModel model = new OnnxInferenceModel(config)) {
                InferenceOutput output = model.run(InferenceInput.of("test"));
                assertThat(output).isNotNull();
            }
        }

        @Test
        void rejectsModelWithUnknownInputNames() {
            ModelConfig config = new ModelConfig(WRONG_INPUTS_MODEL, TOKENIZER_PATH, 512, 0, 0,
                Map.of("input_ids", "nonexistent_name"));
            assertThatThrownBy(() -> new OnnxInferenceModel(config))
                .isInstanceOf(ModelLoadException.class);
        }

        @Test
        void rejectsNonExistentModelPath() {
            assertThatThrownBy(() -> new OnnxInferenceModel(
                    new ModelConfig(Path.of("nonexistent/model.onnx"), TOKENIZER_PATH)))
                .isInstanceOf(ModelLoadException.class);
        }

        @Test
        void rejectsNonExistentTokenizerPath() {
            assertThatThrownBy(() -> new OnnxInferenceModel(
                    new ModelConfig(MODEL_PATH, Path.of("nonexistent/tokenizer.json"))))
                .isInstanceOf(ModelLoadException.class);
        }
    }

    // ── close / lifecycle ──────────────────────────────────────────────

    @Nested
    @DisplayName("close / lifecycle")
    class Lifecycle {

        @Test
        void runAfterCloseThrows() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.close();
            assertThatThrownBy(() -> model.run(InferenceInput.of("test")))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void runBatchAfterCloseThrows() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.close();
            assertThatThrownBy(() -> model.runBatch(List.of(InferenceInput.of("test"))))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void runBatchEmptyAfterCloseThrows() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.close();
            assertThatThrownBy(() -> model.runBatch(List.of()))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void closeIsIdempotent() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.close();
            model.close();
            model.close();
        }

        @Test
        void closeReleasesAllResources() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.run(InferenceInput.of("warm up"));
            model.close();

            assertThatThrownBy(() -> model.run(InferenceInput.of("after close")))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
            assertThatThrownBy(() -> model.runBatch(List.of(InferenceInput.of("after close"))))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }
    }

    // ── thread safety ─────────────────────────────────────────────────

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        void concurrentRunCallsProduceCorrectResults() throws Exception {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            try {
                int threadCount = 8;
                int iterationsPerThread = 10;
                CountDownLatch startLatch = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);

                List<Future<List<InferenceOutput>>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    futures.add(executor.submit(() -> {
                        startLatch.await();
                        List<InferenceOutput> results = new ArrayList<>();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            results.add(model.run(InferenceInput.of("hello world")));
                        }
                        return results;
                    }));
                }

                startLatch.countDown();

                InferenceOutput baseline = model.run(InferenceInput.of("hello world"));

                for (Future<List<InferenceOutput>> future : futures) {
                    List<InferenceOutput> results = future.get(10, TimeUnit.SECONDS);
                    assertThat(results).hasSize(iterationsPerThread);
                    for (InferenceOutput out : results) {
                        assertThat(out.values()).hasSize(baseline.values().length);
                        for (int i = 0; i < baseline.values().length; i++) {
                            assertThat(out.values()[i])
                                .isCloseTo(baseline.values()[i], within(1e-5f));
                        }
                    }
                }

                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                model.close();
            }
        }

        @Test
        void concurrentRunBatchCallsProduceCorrectResults() throws Exception {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            try {
                int threadCount = 4;
                CountDownLatch startLatch = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);

                List<InferenceInput> batchInputs = List.of(
                    InferenceInput.of("first"),
                    InferenceInput.of("second")
                );

                List<InferenceOutput> baseline = model.runBatch(batchInputs);

                List<Future<List<InferenceOutput>>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    futures.add(executor.submit(() -> {
                        startLatch.await();
                        return model.runBatch(batchInputs);
                    }));
                }

                startLatch.countDown();

                for (Future<List<InferenceOutput>> future : futures) {
                    List<InferenceOutput> results = future.get(10, TimeUnit.SECONDS);
                    assertThat(results).hasSize(baseline.size());
                    for (int i = 0; i < baseline.size(); i++) {
                        float[] expected = baseline.get(i).values();
                        float[] actual = results.get(i).values();
                        assertThat(actual).hasSize(expected.length);
                        for (int j = 0; j < expected.length; j++) {
                            assertThat(actual[j]).isCloseTo(expected[j], within(1e-5f));
                        }
                    }
                }

                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                model.close();
            }
        }

        @Test
        void concurrentMixedRunAndRunBatch() throws Exception {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            try {
                int threadCount = 6;
                CountDownLatch startLatch = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);

                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    int thread = t;
                    futures.add(executor.submit(() -> {
                        startLatch.await();
                        if (thread % 2 == 0) {
                            InferenceOutput out = model.run(InferenceInput.of("text " + thread));
                            assertThat(out.values()).hasSize(3);
                        } else {
                            List<InferenceOutput> outs = model.runBatch(List.of(
                                InferenceInput.of("a"),
                                InferenceInput.pair("b", "c")
                            ));
                            assertThat(outs).hasSize(2);
                        }
                        return null;
                    }));
                }

                startLatch.countDown();

                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                model.close();
            }
        }
    }

    // ── multi-output models ──────────────────────────────────────────

    @Nested
    @DisplayName("multi-output models")
    class MultiOutput {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(MULTI_OUTPUT_MODEL, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void loadsMultiOutputModel() {
            assertThat(model.outputSize()).isEqualTo(OptionalInt.empty());
        }

        @Test
        void runReturnsAllNamedOutputs() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThat(out.outputNames()).isEqualTo(Set.of("dense", "sparse"));
            assertThat(out.vector("dense")).hasSize(4);
            assertThat(out.vector("sparse")).hasSize(3);
        }

        @Test
        void runBatchReturnsAllNamedOutputsPerSample() {
            List<InferenceOutput> outputs = model.runBatch(List.of(
                InferenceInput.of("first"),
                InferenceInput.of("second")
            ));
            assertThat(outputs).hasSize(2);
            for (InferenceOutput out : outputs) {
                assertThat(out.outputNames()).isEqualTo(Set.of("dense", "sparse"));
                assertThat(out.vector("dense")).hasSize(4);
                assertThat(out.vector("sparse")).hasSize(3);
            }
        }

        @Test
        void valuesThrowsForMultiOutputModel() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThatThrownBy(out::values)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-output");
        }
    }

    // ── rank-3 output models ─────────────────────────────────────────

    @Nested
    @DisplayName("rank-3 output models")
    class Rank3Output {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(RANK3_OUTPUT_MODEL, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void outputSizeEmptyForRank3() {
            assertThat(model.outputSize()).isEqualTo(OptionalInt.empty());
        }

        @Test
        void runReturnsRank3AsTokenLevelVectors() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThat(out.outputNames()).isEqualTo(Set.of("colbert"));
            // rank-3 output: [seq_len, dim] per sample — seq_len varies with tokenization
            float[][] colbert = out.output("colbert");
            assertThat(colbert.length).isGreaterThan(0);
            for (float[] vec : colbert) {
                assertThat(vec).hasSize(5);
            }
        }

        @Test
        void runBatchStripsPaddingFromRank3() {
            // Different-length inputs will be padded to batch-max in the model
            // but runBatch should strip padding based on attention mask
            InferenceOutput single1 = model.run(InferenceInput.of("hi"));
            InferenceOutput single2 = model.run(InferenceInput.of("hello world this is a longer sentence for testing"));

            List<InferenceOutput> batch = model.runBatch(List.of(
                InferenceInput.of("hi"),
                InferenceInput.of("hello world this is a longer sentence for testing")
            ));

            assertThat(batch).hasSize(2);

            // After padding stripping, each batch output should have the same number
            // of token vectors as the corresponding single-input run
            float[][] batchColbert1 = batch.get(0).output("colbert");
            float[][] singleColbert1 = single1.output("colbert");
            assertThat(batchColbert1.length).isEqualTo(singleColbert1.length);

            float[][] batchColbert2 = batch.get(1).output("colbert");
            float[][] singleColbert2 = single2.output("colbert");
            assertThat(batchColbert2.length).isEqualTo(singleColbert2.length);
        }
    }

    // ── multi-output with rank-3 (BGE-M3 style) ─────────────────────

    @Nested
    @DisplayName("multi-output with rank-3 (BGE-M3 style)")
    class MultiOutputRank3 {

        private OnnxInferenceModel model;

        @BeforeEach
        void setUp() {
            model = new OnnxInferenceModel(new ModelConfig(MULTI_RANK3_MODEL, TOKENIZER_PATH));
        }

        @AfterEach
        void tearDown() {
            if (model != null) model.close();
        }

        @Test
        void outputSizeEmptyForMultiRank3() {
            assertThat(model.outputSize()).isEqualTo(OptionalInt.empty());
        }

        @Test
        void runReturnsDenseSparseAndColbert() {
            InferenceOutput out = model.run(InferenceInput.of("hello world"));
            assertThat(out.outputNames()).isEqualTo(Set.of("dense", "sparse", "colbert"));
            assertThat(out.vector("dense")).hasSize(4);
            assertThat(out.vector("sparse")).hasSize(3);
            float[][] colbert = out.output("colbert");
            assertThat(colbert.length).isGreaterThan(0);
            for (float[] vec : colbert) {
                assertThat(vec).hasSize(5);
            }
        }

        @Test
        void runBatchHandlesMixedRanks() {
            List<InferenceOutput> outputs = model.runBatch(List.of(
                InferenceInput.of("first"),
                InferenceInput.of("second sentence is longer")
            ));
            assertThat(outputs).hasSize(2);
            for (InferenceOutput out : outputs) {
                assertThat(out.outputNames()).isEqualTo(Set.of("dense", "sparse", "colbert"));
                assertThat(out.vector("dense")).hasSize(4);
                assertThat(out.vector("sparse")).hasSize(3);
                float[][] colbert = out.output("colbert");
                assertThat(colbert.length).isGreaterThan(0);
                for (float[] vec : colbert) {
                    assertThat(vec).hasSize(5);
                }
            }
        }

        @Test
        void batchColbertPaddingStripping() {
            // Verify that ColBERT vectors are stripped to actual token count per sample
            InferenceOutput single = model.run(InferenceInput.of("short"));

            List<InferenceOutput> batch = model.runBatch(List.of(
                InferenceInput.of("short"),
                InferenceInput.of("a much longer sentence for padding verification")
            ));

            // The shorter input should have the same colbert length in both modes
            float[][] singleColbert = single.output("colbert");
            float[][] batchColbert = batch.get(0).output("colbert");
            assertThat(batchColbert.length).isEqualTo(singleColbert.length);

            // The longer input should have more colbert vectors than the shorter one
            float[][] longerColbert = batch.get(1).output("colbert");
            assertThat(longerColbert.length).isGreaterThan(batchColbert.length);
        }
    }
}
