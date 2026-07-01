package io.casehub.inference.inmem;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Deterministic {@link InferenceModel} stub for testing. No JNI, no native
 * libs — safe in all test contexts including {@code @QuarkusTest} and native image.
 */
public final class InMemoryInferenceModel implements InferenceModel {

    private final Function<InferenceInput, float[]> fn;
    private final int outputSize;
    private final Map<String, float[][]> multiOutputs;
    private volatile boolean closed;

    private InMemoryInferenceModel(Function<InferenceInput, float[]> fn, int outputSize) {
        this.fn = fn;
        this.outputSize = outputSize;
        this.multiOutputs = null;
    }

    private InMemoryInferenceModel(Map<String, float[][]> multiOutputs) {
        this.fn = null;
        this.outputSize = -1;
        this.multiOutputs = multiOutputs;
    }

    /**
     * Creates a stub that always returns the same values regardless of input.
     * Clones varargs on construction and on each {@link #run} call.
     */
    public static InMemoryInferenceModel returning(float... values) {
        float[] snapshot = values.clone();
        return new InMemoryInferenceModel(input -> snapshot.clone(), snapshot.length);
    }

    /**
     * Creates a stub with custom logic per input. The provided function must
     * be thread-safe — the model delegates directly with no synchronization.
     */
    public static InMemoryInferenceModel withFunction(
            int outputSize, Function<InferenceInput, float[]> fn) {
        Objects.requireNonNull(fn, "fn must not be null");
        if (outputSize <= 0) {
            throw new IllegalArgumentException("outputSize must be positive");
        }
        return new InMemoryInferenceModel(fn, outputSize);
    }

    /**
     * Creates a stub that always returns the same multi-output map regardless of input.
     * Useful for testing multi-output models (e.g., BGE-M3 dense+sparse+colbert).
     */
    public static InMemoryInferenceModel returningMulti(Map<String, float[][]> outputs) {
        Objects.requireNonNull(outputs, "outputs must not be null");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty");
        }
        return new InMemoryInferenceModel(outputs);
    }

    @Override
    public InferenceOutput run(InferenceInput input) {
        if (closed) throw new InferenceException("Model is closed");
        Objects.requireNonNull(input, "input must not be null");
        if (multiOutputs != null) {
            return new InferenceOutput(multiOutputs);
        }
        return InferenceOutput.of(fn.apply(input));
    }

    @Override
    public List<InferenceOutput> runBatch(List<InferenceInput> inputs) {
        if (closed) throw new InferenceException("Model is closed");
        if (inputs == null) throw new IllegalArgumentException("inputs must not be null");
        if (inputs.isEmpty()) return List.of();
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i) == null) {
                throw new IllegalArgumentException("inputs[" + i + "] must not be null");
            }
        }
        List<InferenceOutput> results = new ArrayList<>(inputs.size());
        if (multiOutputs != null) {
            InferenceOutput sharedOutput = new InferenceOutput(multiOutputs);
            for (int i = 0; i < inputs.size(); i++) {
                results.add(sharedOutput);
            }
        } else {
            for (InferenceInput input : inputs) {
                results.add(InferenceOutput.of(fn.apply(input)));
            }
        }
        return Collections.unmodifiableList(results);
    }

    @Override
    public OptionalInt outputSize() {
        if (multiOutputs != null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(outputSize);
    }

    @Override
    public void close() {
        closed = true;
    }
}
