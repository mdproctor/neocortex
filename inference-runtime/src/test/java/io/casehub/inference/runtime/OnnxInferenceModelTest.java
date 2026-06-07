package io.casehub.inference.runtime;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceOutput;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OnnxInferenceModelTest {

    private static final Path TEST_MODEL_DIR = Path.of("src/test/resources/test-model");
    private static final Path MODEL_PATH = TEST_MODEL_DIR.resolve("model.onnx");
    private static final Path TOKENIZER_PATH = TEST_MODEL_DIR.resolve("tokenizer.json");
    private static final Path WRONG_INPUTS_MODEL = TEST_MODEL_DIR.resolve("wrong-inputs-model.onnx");

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

    // ── model loading errors ───────────────────────────────────────────

    @Nested
    @DisplayName("model loading errors")
    class LoadingErrors {

        @Test
        void rejectsModelMissingInputIds() {
            assertThatThrownBy(() -> new OnnxInferenceModel(
                    new ModelConfig(WRONG_INPUTS_MODEL, TOKENIZER_PATH)))
                .isInstanceOf(ModelLoadException.class)
                .hasMessageContaining("input_ids");
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
            // closed check MUST precede the empty-list early return
            assertThatThrownBy(() -> model.runBatch(List.of()))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void closeIsIdempotent() {
            var model = new OnnxInferenceModel(new ModelConfig(MODEL_PATH, TOKENIZER_PATH));
            model.close();
            model.close(); // second close is a no-op
            model.close(); // third close is a no-op
        }
    }
}
