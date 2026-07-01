package io.casehub.inference.tasks;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;
import io.casehub.inference.inmem.InMemoryInferenceModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TextClassifierTest {

    @Nested
    @DisplayName("classify()")
    class Classify {

        @Test
        void labelsMatchOutputIndices() {
            var model = InMemoryInferenceModel.returning(0.1f, 0.9f);
            var tc = new TextClassifier(model, List.of("low", "high"));
            ClassificationResult result = tc.classify("some text");
            assertThat(result.label()).isEqualTo("high");
            assertThat(result.confidence()).isGreaterThan(0.5f);
        }

        @Test
        void softmaxApplied() {
            var model = InMemoryInferenceModel.returning(2.0f, 1.0f);
            var tc = new TextClassifier(model, List.of("a", "b"));
            ClassificationResult result = tc.classify("text");
            float sum = 0;
            for (float v : result.scores().values()) sum += v;
            assertThat(sum).isCloseTo(1.0f, within(1e-6f));
        }

        @Test
        void scoresContainsAllLabels() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            var tc = new TextClassifier(model, List.of("a", "b", "c"));
            ClassificationResult result = tc.classify("text");
            assertThat(result.scores()).hasSize(3);
            assertThat(result.scores()).containsKeys("a", "b", "c");
        }

        @Test
        void singleLabelDegenerateCase() {
            var model = InMemoryInferenceModel.returning(5.0f);
            var tc = new TextClassifier(model, List.of("only"));
            ClassificationResult result = tc.classify("text");
            assertThat(result.label()).isEqualTo("only");
            assertThat(result.confidence()).isCloseTo(1.0f, within(1e-6f));
        }
    }

    @Nested
    @DisplayName("ClassificationResult defensiveness")
    class ResultDefensiveness {

        @Test
        void scoresMapIsUnmodifiable() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f);
            var tc = new TextClassifier(model, List.of("a", "b"));
            ClassificationResult result = tc.classify("text");
            assertThatThrownBy(() -> result.scores().put("c", 0.5f))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("construction validation")
    class ConstructionValidation {

        @Test
        void rejectsLabelCountMismatch() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            assertThatThrownBy(() -> new TextClassifier(model, List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void labelsImmutableAfterConstruction() {
            var labels = new ArrayList<>(List.of("a", "b"));
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f);
            var tc = new TextClassifier(model, labels);
            labels.set(0, "mutated");
            ClassificationResult result = tc.classify("text");
            assertThat(result.scores()).containsKey("a");
            assertThat(result.scores()).doesNotContainKey("mutated");
        }

        @Test
        void rejectsNullLabels() {
            var model = InMemoryInferenceModel.returning(1.0f);
            assertThatThrownBy(() -> new TextClassifier(model, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labels");
        }

        @Test
        void rejectsEmptyLabels() {
            var model = InMemoryInferenceModel.returning(1.0f);
            assertThatThrownBy(() -> new TextClassifier(model, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labels");
        }

        @Test
        void rejectsNullModel() {
            assertThatThrownBy(() -> new TextClassifier(null, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        }

        @Test
        void acceptsUnknownOutputSize() {
            InferenceModel model = new InferenceModel() {
                @Override public InferenceOutput run(final InferenceInput input) {
                    return InferenceOutput.of(1.0f, 2.0f);
                }
                @Override public List<InferenceOutput> runBatch(final List<InferenceInput> inputs) {
                    return inputs.stream().map(this::run).toList();
                }
                @Override public OptionalInt outputSize() { return OptionalInt.empty(); }
                @Override public void close() {}
            };
            var tc = new TextClassifier(model, List.of("a", "b"));
            ClassificationResult result = tc.classify("text");
            assertThat(result.label()).isEqualTo("b");
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        void rejectsNullText() {
            var model = InMemoryInferenceModel.returning(1.0f);
            var tc = new TextClassifier(model, List.of("a"));
            assertThatThrownBy(() -> tc.classify(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
        }
    }

    @Nested
    @DisplayName("runtime output-length guard")
    class RuntimeGuard {

        @Test
        void throwsOnWrongLengthOutput() {
            var model = InMemoryInferenceModel.withFunction(2, input -> new float[]{1.0f, 2.0f, 3.0f});
            var tc = new TextClassifier(model, List.of("a", "b"));
            assertThatThrownBy(() -> tc.classify("text"))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("3");
        }
    }
}
