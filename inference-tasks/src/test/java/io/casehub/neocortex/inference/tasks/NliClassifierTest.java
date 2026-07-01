package io.casehub.neocortex.inference.tasks;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class NliClassifierTest {

    @Nested
    @DisplayName("convention constructor")
    class ConventionConstructor {

        @Test
        void softmaxApplied() {
            var model = InMemoryInferenceModel.returning(2.0f, 1.0f, 0.5f);
            var nli = new NliClassifier(model);
            NliResult result = nli.classify("premise", "hypothesis");
            float sum = result.entailment() + result.neutral() + result.contradiction();
            assertThat(sum).isCloseTo(1.0f, within(1e-6f));
        }

        @Test
        void predictedReturnsHighestLabel() {
            var model = InMemoryInferenceModel.returning(5.0f, 0.1f, 0.1f);
            var nli = new NliClassifier(model);
            NliResult result = nli.classify("p", "h");
            assertThat(result.predicted()).isEqualTo(NliLabel.CONTRADICTION);
        }

        @Test
        void conventionOrder_index0IsContradiction_index2IsEntailment() {
            var model = InMemoryInferenceModel.returning(10.0f, 0.0f, 0.0f);
            var nli = new NliClassifier(model);
            NliResult result = nli.classify("p", "h");
            assertThat(result.contradiction()).isGreaterThan(0.9f);
            assertThat(result.predicted()).isEqualTo(NliLabel.CONTRADICTION);

            var model2 = InMemoryInferenceModel.returning(0.0f, 0.0f, 10.0f);
            var nli2 = new NliClassifier(model2);
            NliResult result2 = nli2.classify("p", "h");
            assertThat(result2.entailment()).isGreaterThan(0.9f);
            assertThat(result2.predicted()).isEqualTo(NliLabel.ENTAILMENT);
        }

        @Test
        void scoresReturnsAllThreeLabels() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            var nli = new NliClassifier(model);
            NliResult result = nli.classify("p", "h");
            Map<NliLabel, Float> scores = result.scores();
            assertThat(scores).hasSize(3);
            assertThat(scores).containsKeys(NliLabel.ENTAILMENT, NliLabel.NEUTRAL, NliLabel.CONTRADICTION);
            assertThat(scores.get(NliLabel.ENTAILMENT)).isEqualTo(result.entailment());
            assertThat(scores.get(NliLabel.NEUTRAL)).isEqualTo(result.neutral());
            assertThat(scores.get(NliLabel.CONTRADICTION)).isEqualTo(result.contradiction());
        }
    }

    @Nested
    @DisplayName("explicit-index constructor")
    class ExplicitIndexConstructor {

        @Test
        void customMappingProducesCorrectAssignment() {
            var model = InMemoryInferenceModel.returning(10.0f, 0.0f, 0.0f);
            var nli = new NliClassifier(model, 0, 1, 2);
            NliResult result = nli.classify("p", "h");
            assertThat(result.entailment()).isGreaterThan(0.9f);
            assertThat(result.predicted()).isEqualTo(NliLabel.ENTAILMENT);
        }

        @Test
        void rejectsDuplicateIndices() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            assertThatThrownBy(() -> new NliClassifier(model, 0, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsOutOfRangeIndices() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            assertThatThrownBy(() -> new NliClassifier(model, 0, 1, 3))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NliClassifier(model, -1, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("construction validation")
    class ConstructionValidation {

        @Test
        void rejectsOutputSizeMismatch() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f, 4.0f, 5.0f);
            assertThatThrownBy(() -> new NliClassifier(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("5");
        }

        @Test
        void acceptsUnknownOutputSize() {
            InferenceModel model = new InferenceModel() {
                @Override public InferenceOutput run(final InferenceInput input) {
                    return InferenceOutput.of(1.0f, 2.0f, 3.0f);
                }
                @Override public List<InferenceOutput> runBatch(final List<InferenceInput> inputs) {
                    return inputs.stream().map(this::run).toList();
                }
                @Override public OptionalInt outputSize() { return OptionalInt.empty(); }
                @Override public void close() {}
            };
            var nli = new NliClassifier(model);
            NliResult result = nli.classify("p", "h");
            assertThat(result.entailment() + result.neutral() + result.contradiction())
                .isCloseTo(1.0f, within(1e-6f));
        }

        @Test
        void rejectsNullModel() {
            assertThatThrownBy(() -> new NliClassifier(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        void rejectsNullPremise() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            var nli = new NliClassifier(model);
            assertThatThrownBy(() -> nli.classify(null, "h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("premise");
        }

        @Test
        void rejectsNullHypothesis() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            var nli = new NliClassifier(model);
            assertThatThrownBy(() -> nli.classify("p", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hypothesis");
        }
    }

    @Nested
    @DisplayName("runtime output-length guard")
    class RuntimeGuard {

        @Test
        void throwsOnWrongLengthOutput() {
            var model = InMemoryInferenceModel.withFunction(3, input -> new float[]{1.0f, 2.0f});
            var nli = new NliClassifier(model);
            assertThatThrownBy(() -> nli.classify("p", "h"))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("2");
        }
    }
}
