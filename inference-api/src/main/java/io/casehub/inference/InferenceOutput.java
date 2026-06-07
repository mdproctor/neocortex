package io.casehub.inference;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable output from inference — a float array of logits, probabilities,
 * or scores. Defensive copies on construction and access prevent mutation.
 */
public record InferenceOutput(float[] values) {

    public InferenceOutput {
        Objects.requireNonNull(values, "values must not be null");
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InferenceOutput other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        if (values.length <= 5) return "InferenceOutput" + Arrays.toString(values);
        return "InferenceOutput[" + values[0] + ", " + values[1] + ", " + values[2]
            + ", ... (" + values.length + " values)]";
    }
}
