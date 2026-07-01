package io.casehub.neocortex.inference;

import java.util.List;
import java.util.OptionalInt;

/**
 * SPI for text-in, tensor-out inference. Thread-safe for concurrent
 * {@link #run}/{@link #runBatch} calls. One-shot lifecycle: construct,
 * use, close. Post-close calls throw {@link InferenceException}.
 */
public interface InferenceModel extends AutoCloseable {

    /**
     * Run inference on a single input.
     *
     * @param input must not be null
     * @throws InferenceException if model is closed or inference fails
     */
    InferenceOutput run(InferenceInput input);

    /**
     * Batch inference. Returns one output per input, in order. The returned
     * list is unmodifiable.
     *
     * @throws IllegalArgumentException if inputs is null or contains null elements
     * @throws InferenceException if model is closed or inference fails
     */
    List<InferenceOutput> runBatch(List<InferenceInput> inputs);

    /**
     * Number of values in each output. Empty if unknown or dynamic.
     */
    default OptionalInt outputSize() {
        return OptionalInt.empty();
    }

    /**
     * Releases resources. Idempotent — second and subsequent calls are no-ops.
     * Must not throw — implementations swallow cleanup errors.
     */
    @Override
    void close();
}
