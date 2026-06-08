package io.casehub.inference.tasks;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;
import io.casehub.inference.inmem.InMemoryInferenceModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalarRegressorTest {

    @Nested
    @DisplayName("predict()")
    class Predict {

        @Test
        void returnsRawScalarValue() {
            var model = InMemoryInferenceModel.returning(0.73f);
            var regressor = new ScalarRegressor(model);
            assertThat(regressor.predict("some text")).isEqualTo(0.73f);
        }

        @Test
        void noSoftmaxApplied() {
            var model = InMemoryInferenceModel.returning(5.0f);
            var regressor = new ScalarRegressor(model);
            assertThat(regressor.predict("text")).isEqualTo(5.0f);
        }

        @Test
        void negativeValuesPreserved() {
            var model = InMemoryInferenceModel.returning(-0.5f);
            var regressor = new ScalarRegressor(model);
            assertThat(regressor.predict("text")).isEqualTo(-0.5f);
        }
    }

    @Nested
    @DisplayName("construction validation")
    class ConstructionValidation {

        @Test
        void rejectsOutputSizeMismatch() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            assertThatThrownBy(() -> new ScalarRegressor(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("3");
        }

        @Test
        void rejectsNullModel() {
            assertThatThrownBy(() -> new ScalarRegressor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        }

        @Test
        void acceptsUnknownOutputSize() {
            InferenceModel model = new InferenceModel() {
                @Override public InferenceOutput run(final InferenceInput input) {
                    return new InferenceOutput(new float[]{0.42f});
                }
                @Override public List<InferenceOutput> runBatch(final List<InferenceInput> inputs) {
                    return inputs.stream().map(this::run).toList();
                }
                @Override public OptionalInt outputSize() { return OptionalInt.empty(); }
                @Override public void close() {}
            };
            var regressor = new ScalarRegressor(model);
            assertThat(regressor.predict("text")).isEqualTo(0.42f);
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        void rejectsNullText() {
            var model = InMemoryInferenceModel.returning(1.0f);
            var regressor = new ScalarRegressor(model);
            assertThatThrownBy(() -> regressor.predict(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
        }
    }

    @Nested
    @DisplayName("runtime output-length guard")
    class RuntimeGuard {

        @Test
        void throwsOnMultiElementOutput() {
            var model = InMemoryInferenceModel.withFunction(1, input -> new float[]{1.0f, 2.0f});
            var regressor = new ScalarRegressor(model);
            assertThatThrownBy(() -> regressor.predict("text"))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("2");
        }
    }
}
