package io.casehub.neocortex.memory.cbr;

import java.util.Objects;

public interface FeatureField {
    String name();

    record Categorical(String name) implements FeatureField {
        public Categorical { Objects.requireNonNull(name, "name"); }
    }

    record Numeric(String name, double min, double max) implements FeatureField {
        public Numeric {
            Objects.requireNonNull(name, "name");
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
        }
    }

    record Text(String name) implements FeatureField {
        public Text { Objects.requireNonNull(name, "name"); }
    }

    static FeatureField categorical(String name) { return new Categorical(name); }
    static FeatureField numeric(String name, double min, double max) { return new Numeric(name, min, max); }
    static FeatureField text(String name) { return new Text(name); }
}
