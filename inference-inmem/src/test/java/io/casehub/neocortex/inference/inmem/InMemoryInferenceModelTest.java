package io.casehub.neocortex.inference.inmem;

import io.casehub.neocortex.inference.InferenceException;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryInferenceModelTest {

    // ── returning() factory ─────────────────────────────────────────

    @Nested
    @DisplayName("returning()")
    class ReturningFactory {

        @Test
        void returnsExpectedValues() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f);
            InferenceOutput out = model.run(InferenceInput.of("anything"));
            assertThat(out.values()).containsExactly(1.0f, 2.0f, 3.0f);
        }

        @Test
        void outputSizeMatchesVarargs() {
            var model = InMemoryInferenceModel.returning(0.1f, 0.9f);
            assertThat(model.outputSize()).hasValue(2);
        }

        @Test
        void sameOutputRegardlessOfInput() {
            var model = InMemoryInferenceModel.returning(0.5f);
            InferenceOutput out1 = model.run(InferenceInput.of("hello"));
            InferenceOutput out2 = model.run(InferenceInput.of("goodbye"));
            InferenceOutput out3 = model.run(InferenceInput.pair("a", "b"));
            assertThat(out1).isEqualTo(out2).isEqualTo(out3);
        }

        @Test
        void clonesVarargsOnConstruction() {
            float[] values = {1.0f, 2.0f};
            var model = InMemoryInferenceModel.returning(values);
            values[0] = 999.0f; // mutate original
            InferenceOutput out = model.run(InferenceInput.of("test"));
            assertThat(out.values()).containsExactly(1.0f, 2.0f);
        }

        @Test
        void clonesOnEachCall() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f);
            InferenceOutput out1 = model.run(InferenceInput.of("a"));
            InferenceOutput out2 = model.run(InferenceInput.of("b"));
            // InferenceOutput defensively copies, so values() returns distinct arrays
            assertThat(out1.values()).isNotSameAs(out2.values());
            assertThat(out1).isEqualTo(out2);
        }
    }

    // ── returningMulti() factory ────────────────────────────────────

    @Nested
    @DisplayName("returningMulti()")
    class ReturningMultiFactory {

        @Test
        void returnsMultiOutputs() {
            var outputs = Map.of(
                "dense", new float[][]{{1f, 2f}},
                "sparse", new float[][]{{3f, 4f, 5f}}
            );
            var model = InMemoryInferenceModel.returningMulti(outputs);
            InferenceOutput out = model.run(InferenceInput.of("anything"));
            assertThat(out.outputNames()).isEqualTo(Set.of("dense", "sparse"));
            assertThat(out.vector("dense")).containsExactly(1f, 2f);
            assertThat(out.vector("sparse")).containsExactly(3f, 4f, 5f);
        }

        @Test
        void outputSizeIsEmpty() {
            var outputs = Map.of("a", new float[][]{{1f}});
            var model = InMemoryInferenceModel.returningMulti(outputs);
            assertThat(model.outputSize()).isEmpty();
        }

        @Test
        void sameOutputRegardlessOfInput() {
            var outputs = Map.of("vec", new float[][]{{0.5f}});
            var model = InMemoryInferenceModel.returningMulti(outputs);
            InferenceOutput out1 = model.run(InferenceInput.of("hello"));
            InferenceOutput out2 = model.run(InferenceInput.of("goodbye"));
            assertThat(out1).isEqualTo(out2);
        }

        @Test
        void rejectsNullOutputs() {
            assertThatThrownBy(() -> InMemoryInferenceModel.returningMulti(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsEmptyOutputs() {
            assertThatThrownBy(() -> InMemoryInferenceModel.returningMulti(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputs must not be empty");
        }
    }

    // ── withFunction() factory ──────────────────────────────────────

    @Nested
    @DisplayName("withFunction()")
    class WithFunctionFactory {

        @Test
        void functionReceivesCorrectInput() {
            var model = InMemoryInferenceModel.withFunction(1, input -> {
                float val = input.texts().get(0).length();
                return new float[]{val};
            });
            InferenceOutput out = model.run(InferenceInput.of("hello"));
            assertThat(out.values()).containsExactly(5.0f);
        }

        @Test
        void outputSizeReturnsConfiguredValue() {
            var model = InMemoryInferenceModel.withFunction(42, input -> new float[42]);
            assertThat(model.outputSize()).hasValue(42);
        }

        @Test
        void rejectsNullFunction() {
            assertThatThrownBy(() -> InMemoryInferenceModel.withFunction(3, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fn must not be null");
        }

        @Test
        void rejectsNonPositiveOutputSize() {
            assertThatThrownBy(() -> InMemoryInferenceModel.withFunction(0, i -> new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputSize must be positive");

            assertThatThrownBy(() -> InMemoryInferenceModel.withFunction(-1, i -> new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputSize must be positive");
        }
    }

    // ── run() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        void rejectsNullInput() {
            var model = InMemoryInferenceModel.returning(1.0f);
            assertThatThrownBy(() -> model.run(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("input must not be null");
        }
    }

    // ── runBatch() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("runBatch()")
    class RunBatch {

        @Test
        void oneOutputPerInput() {
            var model = InMemoryInferenceModel.returning(0.5f);
            List<InferenceInput> inputs = List.of(
                InferenceInput.of("a"),
                InferenceInput.of("b"),
                InferenceInput.of("c")
            );
            List<InferenceOutput> outputs = model.runBatch(inputs);
            assertThat(outputs).hasSize(3);
            assertThat(outputs.get(0).values()).containsExactly(0.5f);
            assertThat(outputs.get(1).values()).containsExactly(0.5f);
            assertThat(outputs.get(2).values()).containsExactly(0.5f);
        }

        @Test
        void emptyListReturnsEmpty() {
            var model = InMemoryInferenceModel.returning(1.0f);
            assertThat(model.runBatch(List.of())).isEmpty();
        }

        @Test
        void rejectsNullList() {
            var model = InMemoryInferenceModel.returning(1.0f);
            assertThatThrownBy(() -> model.runBatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs must not be null");
        }

        @Test
        void rejectsNullElement() {
            var model = InMemoryInferenceModel.returning(1.0f);
            List<InferenceInput> inputs = new ArrayList<>();
            inputs.add(InferenceInput.of("a"));
            inputs.add(null);
            assertThatThrownBy(() -> model.runBatch(inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs[1] must not be null");
        }

        @Test
        void consistentWithRun() {
            var model = InMemoryInferenceModel.withFunction(1, input -> {
                float val = input.texts().get(0).length();
                return new float[]{val};
            });
            InferenceInput in1 = InferenceInput.of("hi");
            InferenceInput in2 = InferenceInput.of("hello");

            InferenceOutput single1 = model.run(in1);
            InferenceOutput single2 = model.run(in2);
            List<InferenceOutput> batch = model.runBatch(List.of(in1, in2));

            assertThat(batch.get(0)).isEqualTo(single1);
            assertThat(batch.get(1)).isEqualTo(single2);
        }

        @Test
        void resultListIsUnmodifiable() {
            var model = InMemoryInferenceModel.returning(1.0f);
            List<InferenceOutput> results = model.runBatch(List.of(InferenceInput.of("a")));
            assertThatThrownBy(() -> results.add(InferenceOutput.of(0f)))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── close / lifecycle ───────────────────────────────────────────

    @Nested
    @DisplayName("close / lifecycle")
    class Lifecycle {

        @Test
        void runThrowsAfterClose() {
            var model = InMemoryInferenceModel.returning(1.0f);
            model.close();
            assertThatThrownBy(() -> model.run(InferenceInput.of("test")))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void runBatchThrowsAfterClose() {
            var model = InMemoryInferenceModel.returning(1.0f);
            model.close();
            assertThatThrownBy(() -> model.runBatch(List.of(InferenceInput.of("test"))))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void runBatchEmptyAfterCloseThrows() {
            var model = InMemoryInferenceModel.returning(1.0f);
            model.close();
            // closed check MUST precede the empty-list early return
            assertThatThrownBy(() -> model.runBatch(List.of()))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        void closeIsIdempotent() {
            var model = InMemoryInferenceModel.returning(1.0f);
            model.close();
            model.close(); // second close is a no-op, no exception
            model.close(); // third close is a no-op, no exception
        }
    }
}
