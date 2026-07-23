package io.casehub.neocortex.memory.cbr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface FeatureField permits FeatureField.Categorical, FeatureField.Numeric, FeatureField.Text,
                                             FeatureField.CategoricalList, FeatureField.NumericList,
                                             FeatureField.NestedObject, FeatureField.ObjectList,
                                             FeatureField.TimeSeries, FeatureField.DiscreteSequence {
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
                    case SimilaritySpec.DtwSpec ds -> throw new IllegalArgumentException(
                            "Categorical fields only support CategoricalTable specs");
                    case SimilaritySpec.EditDistanceSpec es -> throw new IllegalArgumentException(
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
                    case SimilaritySpec.DtwSpec ds -> throw new IllegalArgumentException(
                            "Numeric fields do not support DtwSpec specs");
                    case SimilaritySpec.EditDistanceSpec es -> throw new IllegalArgumentException(
                            "Numeric fields do not support EditDistanceSpec specs");
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

    record NumericList(String name, double min, double max) implements FeatureField {
        public NumericList {
            Objects.requireNonNull(name, "name");
            if (min > max) {
                throw new IllegalArgumentException(
                        "min must be <= max, got min=" + min + " max=" + max);
            }
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

    record TimeSeries(String name, List<FeatureField> innerFields, String timestampField,
                      SimilaritySpec similaritySpec, TrendSpec trendSpec) implements FeatureField {
        public TimeSeries(String name, List<FeatureField> innerFields, String timestampField) {
            this(name, innerFields, timestampField, null, null);
        }

        public TimeSeries(String name, List<FeatureField> innerFields, String timestampField,
                          SimilaritySpec similaritySpec) {
            this(name, innerFields, timestampField, similaritySpec, null);
        }

        public TimeSeries {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(innerFields, "innerFields");
            Objects.requireNonNull(timestampField, "timestampField");
            innerFields = List.copyOf(innerFields);
            if (innerFields.isEmpty()) {
                throw new IllegalArgumentException("innerFields must not be empty");
            }
            validateFlatFields(innerFields);
            FeatureField tsField = null;
            for (FeatureField f : innerFields) {
                if (f.name().equals(timestampField)) {
                    tsField = f;
                    break;
                }
            }
            if (tsField == null) {
                throw new IllegalArgumentException(
                        "timestampField '" + timestampField + "' not found in innerFields");
            }
            if (!(tsField instanceof Numeric)) {
                throw new IllegalArgumentException(
                        "timestampField '" + timestampField + "' must be Numeric, got: "
                        + tsField.getClass().getSimpleName());
            }
            boolean hasNonTimestampNumeric = false;
            for (FeatureField f : innerFields) {
                if (f instanceof Numeric && !f.name().equals(timestampField)) {
                    hasNonTimestampNumeric = true;
                    break;
                }
            }
            if (!hasNonTimestampNumeric) {
                throw new IllegalArgumentException(
                        "TimeSeries requires at least one non-timestamp Numeric inner field for DTW distance");
            }
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.DtwSpec ds -> {}
                    case SimilaritySpec.CategoricalTable ct -> throw new IllegalArgumentException(
                            "TimeSeries fields only support DtwSpec");
                    case SimilaritySpec.GaussianDecay gd -> throw new IllegalArgumentException(
                            "TimeSeries fields only support DtwSpec");
                    case SimilaritySpec.StepDecay sd -> throw new IllegalArgumentException(
                            "TimeSeries fields only support DtwSpec");
                    case SimilaritySpec.ExponentialDecay ed -> throw new IllegalArgumentException(
                            "TimeSeries fields only support DtwSpec");
                    case SimilaritySpec.EditDistanceSpec es -> throw new IllegalArgumentException(
                            "TimeSeries fields only support DtwSpec");
                }
            }
        }
    }

    record DiscreteSequence(String name, SimilaritySpec similaritySpec) implements FeatureField {
        public DiscreteSequence(String name) {
            this(name, null);
        }

        public DiscreteSequence {
            Objects.requireNonNull(name, "name");
            if (similaritySpec != null) {
                switch (similaritySpec) {
                    case SimilaritySpec.EditDistanceSpec es -> {}
                    case SimilaritySpec.CategoricalTable ct -> throw new IllegalArgumentException(
                            "DiscreteSequence fields only support EditDistanceSpec");
                    case SimilaritySpec.GaussianDecay gd -> throw new IllegalArgumentException(
                            "DiscreteSequence fields only support EditDistanceSpec");
                    case SimilaritySpec.StepDecay sd -> throw new IllegalArgumentException(
                            "DiscreteSequence fields only support EditDistanceSpec");
                    case SimilaritySpec.ExponentialDecay ed -> throw new IllegalArgumentException(
                            "DiscreteSequence fields only support EditDistanceSpec");
                    case SimilaritySpec.DtwSpec ds -> throw new IllegalArgumentException(
                            "DiscreteSequence fields only support EditDistanceSpec");
                }
            }
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
                                "Inner field '" + c.name() + "': SimilaritySpec not supported on inner fields — scored with type defaults only");
                    }
                }
                case Numeric n -> {
                    if (n.similaritySpec() != null) {
                        throw new IllegalArgumentException(
                                "Inner field '" + n.name() + "': SimilaritySpec not supported on inner fields — scored with type defaults only");
                    }
                }
                case Text t -> {
                    if (t.semantic()) {
                        throw new IllegalArgumentException(
                                "Inner field '" + t.name() + "': semantic matching not supported on inner fields — scored with type defaults only");
                    }
                }
                case CategoricalList cl -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: CategoricalList");
                case NumericList nl -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: NumericList");
                case NestedObject no -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: NestedObject");
                case ObjectList ol -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: ObjectList");
                case TimeSeries ts -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: TimeSeries");
                case DiscreteSequence ds -> throw new IllegalArgumentException(
                        "Inner fields must be flat (Categorical/Numeric/Text), got: DiscreteSequence");
            }
        }}

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

    static FeatureField numericList(String name, double min, double max) {
        return new NumericList(name, min, max);
    }


    static FeatureField nestedObject(String name, FeatureField... innerFields) {
        return new NestedObject(name, List.of(innerFields));
    }

    static FeatureField objectList(String name, FeatureField... innerFields) {
        return new ObjectList(name, List.of(innerFields));
    }

    static FeatureField timeSeries(String name, String timestampField, FeatureField... innerFields) {
        return new TimeSeries(name, List.of(innerFields), timestampField);
    }

    static FeatureField discreteSequence(String name) {return new DiscreteSequence(name);}

    static FeatureField timeSeries(String name, String timestampField,
                                   SimilaritySpec spec, FeatureField... innerFields) {
        return new TimeSeries(name, List.of(innerFields), timestampField, spec);
    }

    static FeatureField discreteSequence(String name, SimilaritySpec spec) {
        return new DiscreteSequence(name, spec);
    }

    static FeatureField timeSeries(String name, String timestampField,
                                   SimilaritySpec spec, TrendSpec trendSpec, FeatureField... innerFields) {
        return new TimeSeries(name, List.of(innerFields), timestampField, spec, trendSpec);
    }


}
