package io.casehub.neocortex.inference;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable output from inference. Supports single-output models (backward compat)
 * and multi-output models (e.g., BGE-M3 dense+sparse+colbert).
 * Deep defensive copies on construction and all access methods prevent mutation.
 */
public final class InferenceOutput {

    private final Map<String, float[][]> outputs;

    /**
     * Constructs a multi-output inference result.
     * @param outputs map from output name to float[][] (rank-1 vectors in rank-2 array)
     * @throws NullPointerException if outputs is null
     * @throws IllegalArgumentException if outputs is empty
     */
    public InferenceOutput(Map<String, float[][]> outputs) {
        Objects.requireNonNull(outputs, "outputs must not be null");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty");
        }
        this.outputs = deepCopyOutputs(outputs);
    }

    /**
     * Static factory for single-output models (backward compatibility).
     * @param values output vector
     * @return InferenceOutput with single output named "output"
     */
    public static InferenceOutput of(float... values) {
        Objects.requireNonNull(values, "values must not be null");
        return new InferenceOutput(Map.of("output", new float[][]{values.clone()}));
    }

    /**
     * Returns the single output vector (backward compat for single-output models).
     * @return cloned float[] from the single output
     * @throws IllegalStateException if this is a multi-output model
     */
    public float[] values() {
        if (outputs.size() > 1) {
            throw new IllegalStateException(
                "values() is only valid for single-output models; this model has "
                + outputs.size() + " outputs: " + outputs.keySet()
            );
        }
        String key = outputs.keySet().iterator().next();
        float[][] data = outputs.get(key);
        return data[0].clone();
    }

    /**
     * Returns the full output array for a named output (rank-2).
     * @param name output name
     * @return cloned float[][] for the named output
     * @throws IllegalArgumentException if name is not in outputNames()
     */
    public float[][] output(String name) {
        float[][] data = outputs.get(name);
        if (data == null) {
            throw new IllegalArgumentException(
                "Unknown output name: " + name + "; available: " + outputs.keySet()
            );
        }
        return deepCopyArray(data);
    }

    /**
     * Returns the first vector from a named output (convenience for single-vector outputs).
     * @param name output name
     * @return cloned float[] from output(name)[0]
     * @throws IllegalArgumentException if name is not in outputNames()
     */
    public float[] vector(String name) {
        return output(name)[0].clone();
    }

    /**
     * Returns the set of output names.
     * @return unmodifiable set of output names
     */
    public Set<String> outputNames() {
        return outputs.keySet();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InferenceOutput other)) return false;
        if (!outputs.keySet().equals(other.outputs.keySet())) return false;
        for (String key : outputs.keySet()) {
            if (!Arrays.deepEquals(outputs.get(key), other.outputs.get(key))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (Map.Entry<String, float[][]> entry : outputs.entrySet()) {
            result = 31 * result + entry.getKey().hashCode();
            result = 31 * result + Arrays.deepHashCode(entry.getValue());
        }
        return result;
    }

    @Override
    public String toString() {
        if (outputs.size() == 1) {
            Map.Entry<String, float[][]> entry = outputs.entrySet().iterator().next();
            float[][] data = entry.getValue();
            if (data.length == 1) {
                float[] vec = data[0];
                if (vec.length <= 5) {
                    return "InferenceOutput" + Arrays.toString(vec);
                }
                return "InferenceOutput[" + vec[0] + ", " + vec[1] + ", " + vec[2]
                    + ", ... (" + vec.length + " values)]";
            }
        }
        return "InferenceOutput{" + outputs.keySet().stream()
            .map(k -> k + "=" + outputs.get(k).length + " vectors")
            .collect(Collectors.joining(", ")) + "}";
    }

    // --- Deep copy utilities ---

    private static Map<String, float[][]> deepCopyOutputs(Map<String, float[][]> src) {
        Map<String, float[][]> copy = new HashMap<>();
        for (Map.Entry<String, float[][]> entry : src.entrySet()) {
            copy.put(entry.getKey(), deepCopyArray(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static float[][] deepCopyArray(float[][] src) {
        float[][] copy = new float[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i].clone();
        }
        return copy;
    }
}
