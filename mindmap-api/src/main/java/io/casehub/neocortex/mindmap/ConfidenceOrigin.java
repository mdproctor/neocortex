package io.casehub.neocortex.mindmap;

public enum ConfidenceOrigin {
    STATED(1.0),
    INFERRED(0.7),
    SPECULATED(0.3);

    private final double initialConfidence;

    ConfidenceOrigin(double initialConfidence) {
        this.initialConfidence = initialConfidence;
    }

    public double initialConfidence() {
        return initialConfidence;
    }
}
