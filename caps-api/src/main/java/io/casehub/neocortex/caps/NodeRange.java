package io.casehub.neocortex.caps;

public enum NodeRange {
    BIPOLAR,
    UNIPOLAR;

    /**
     * Activation value of an unstimulated node under this range's
     * activation function: tanh(0) = 0.0 for bipolar,
     * logistic(0) = 0.5 for unipolar.
     */
    public double restingLevel() {
        return switch (this) {
            case BIPOLAR -> 0.0;
            case UNIPOLAR -> 0.5;
        };
    }
}
