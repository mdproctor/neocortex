package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

public sealed interface FeatureField permits FeatureField.Categorical, FeatureField.Numeric, FeatureField.Text {
    String name();

    record Categorical(String name, SimilaritySpec similaritySpec) implements FeatureField {
        public Categorical(String name) { this(name, null); }
        public Categorical {
            Objects.requireNonNull(name, "name");
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.CategoricalTable ct -> {}
                    case SimilaritySpec.GaussianDecay gd -> throw new IllegalArgumentException(
                        "Categorical fields only support CategoricalTable specs");
                    case SimilaritySpec.StepDecay sd -> throw new IllegalArgumentException(
                        "Categorical fields only support CategoricalTable specs");
                    case SimilaritySpec.ExponentialDecay ed -> throw new IllegalArgumentException(
                        "Categorical fields only support CategoricalTable specs");
                }
            }
        }
    }

    record Numeric(String name, double min, double max, SimilaritySpec similaritySpec) implements FeatureField {
        public Numeric(String name, double min, double max) { this(name, min, max, null); }
        public Numeric {
            Objects.requireNonNull(name, "name");
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.GaussianDecay gd -> {}
                    case SimilaritySpec.StepDecay sd -> {}
                    case SimilaritySpec.ExponentialDecay ed -> {}
                    case SimilaritySpec.CategoricalTable ct -> throw new IllegalArgumentException(
                        "Numeric fields do not support CategoricalTable specs");
                }
            }
        }
    }

    record Text(String name, boolean semantic) implements FeatureField {
        public Text {
            Objects.requireNonNull(name, "name");
        }
        public Text(String name) {
            this(name, false);
        }
    }

    static FeatureField categorical(String name) { return new Categorical(name); }
    static FeatureField categorical(String name, SimilaritySpec similaritySpec) {
        return new Categorical(name, similaritySpec);
    }
    static FeatureField numeric(String name, double min, double max) { return new Numeric(name, min, max); }
    static FeatureField numeric(String name, double min, double max, SimilaritySpec similaritySpec) {
        return new Numeric(name, min, max, similaritySpec);
    }
    static FeatureField text(String name) { return new Text(name); }
    static FeatureField semanticText(String name) { return new Text(name, true); }
}
