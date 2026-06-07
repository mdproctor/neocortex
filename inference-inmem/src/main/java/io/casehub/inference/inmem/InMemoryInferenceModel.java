package io.casehub.inference.inmem;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private volatile boolean closed;

    private InMemoryInferenceModel(Function<InferenceInput, float[]> fn, int outputSize) {
        this.fn = fn;
        this.outputSize = outputSize;
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

    @Override
    public InferenceOutput run(InferenceInput input) {
        if (closed) throw new InferenceException("Model is closed");
        Objects.requireNonNull(input, "input must not be null");
        return new InferenceOutput(fn.apply(input));
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
        for (InferenceInput input : inputs) {
            results.add(new InferenceOutput(fn.apply(input)));
        }
        return Collections.unmodifiableList(results);
    }

    @Override
    public OptionalInt outputSize() {
        return OptionalInt.of(outputSize);
    }

    @Override
    public void close() {
        closed = true;
    }
}
