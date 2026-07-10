package io.casehub.neocortex.memory.cbr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface FeatureField permits FeatureField.Categorical, FeatureField.Numeric, FeatureField.Text,
                                             FeatureField.CategoricalList, FeatureField.NestedObject, FeatureField.ObjectList {
    String name();

    record Categorical(String name, SimilaritySpec similaritySpec) implements FeatureField {
        public Categorical(String name) {this(name, null);}

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
        public Numeric(String name, double min, double max) {this(name, min, max, null);}

        public Numeric {
            Objects.requireNonNull(name, "name");
            if (min > max) {
                throw new IllegalArgumentException(
                        "min must be <= max, got min=" + min + " max=" + max);
            }
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

    record CategoricalList(String name) implements FeatureField {
        public CategoricalList {
            Objects.requireNonNull(name, "name");
        }
    }

    record NestedObject(String name, List<FeatureField> innerFields) implements FeatureField {
        public NestedObject {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(innerFields, "innerFields");
            innerFields = List.copyOf(innerFields);
            validateFlatFields(innerFields);
        }
    }

    record ObjectList(String name, List<FeatureField> innerFields) implements FeatureField {
        public ObjectList {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(innerFields, "innerFields");
            innerFields = List.copyOf(innerFields);
            validateFlatFields(innerFields);
        }
    }

    private static void validateFlatFields(List<FeatureField> fields) {
        Set<String> names = new HashSet<>();
        for (FeatureField f : fields) {
            if (!names.add(f.name())) {
                throw new IllegalArgumentException("Duplicate inner field name: '" + f.name() + "'");
            }
            switch (f) {
                case Categorical c -> {
                    if (c.similaritySpec() != null) {
                        throw new IllegalArgumentException(
                                "Inner field '" + c.name() + "': SimilaritySpec not supported — inner fields are filter-only");
                    }
                }
                case Numeric n -> {
                    if (n.similaritySpec() != null) {
                        throw new IllegalArgumentException(
                                "Inner field '" + n.name() + "': SimilaritySpec not supported — inner fields are filter-only");
                    }
                }
                case Text t -> {
                    if (t.semantic()) {
                        throw new IllegalArgumentException(
                                "Inner field '" + t.name() + "': semantic matching not supported — inner fields are filter-only");
                    }
                }
                case CategoricalList cl -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: CategoricalList");
                case NestedObject no -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: NestedObject");
                case ObjectList ol -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: ObjectList");
            }
        }
    }

    static FeatureField categorical(String name) {return new Categorical(name);}

    static FeatureField categorical(String name, SimilaritySpec similaritySpec) {
        return new Categorical(name, similaritySpec);
    }

    static FeatureField numeric(String name, double min, double max) {return new Numeric(name, min, max);}

    static FeatureField numeric(String name, double min, double max, SimilaritySpec similaritySpec) {
        return new Numeric(name, min, max, similaritySpec);
    }

    static FeatureField text(String name)            {return new Text(name);}

    static FeatureField semanticText(String name)    {return new Text(name, true);}

    static FeatureField categoricalList(String name) {return new CategoricalList(name);}

    static FeatureField nestedObject(String name, FeatureField... innerFields) {
        return new NestedObject(name, List.of(innerFields));
    }

    static FeatureField objectList(String name, FeatureField... innerFields) {
        return new ObjectList(name, List.of(innerFields));
    }
}
