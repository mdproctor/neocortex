package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface CbrFilter {

    record Contains(String value) implements CbrFilter {
        public Contains {
            Objects.requireNonNull(value, "value");
        }
    }

    record ContainsAll(List<String> values) implements CbrFilter {
        public ContainsAll {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            values = List.copyOf(values);
        }
    }

    record ContainsAny(List<String> values) implements CbrFilter {
        public ContainsAny {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            values = List.copyOf(values);
        }
    }

    record HasMatch(Map<String, FeatureValue> subFields) implements CbrFilter {
        public HasMatch {
            Objects.requireNonNull(subFields, "subFields");
            if (subFields.isEmpty()) {throw new IllegalArgumentException("subFields must not be empty");}
            for (Map.Entry<String, FeatureValue> e : subFields.entrySet()) {
                FeatureValue v = e.getValue();
                if (!(v instanceof FeatureValue.StringVal) && !(v instanceof FeatureValue.NumberVal) && !(v instanceof FeatureValue.RangeVal)) {
                    throw new IllegalArgumentException(
                            "Sub-field '" + e.getKey() + "' value must be StringVal, NumberVal, or RangeVal, got: "
                            + v.getClass().getSimpleName());
                }
            }
            subFields = Map.copyOf(subFields);
        }
    }

    record NotContains(String value) implements CbrFilter {
        public NotContains {
            Objects.requireNonNull(value, "value");
        }
    }

    record NotContainsAny(List<String> values) implements CbrFilter {
        public NotContainsAny {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {throw new IllegalArgumentException("values must not be empty");}
            values = List.copyOf(values);
        }
    }

    record ContainsRange(NumericRange range) implements CbrFilter {
        public ContainsRange {
            Objects.requireNonNull(range, "range");
        }
    }

    record AllOf(List<CbrFilter> filters) implements CbrFilter {
        public AllOf {
            Objects.requireNonNull(filters, "filters");
            if (filters.size() < 2) {throw new IllegalArgumentException("AllOf requires at least 2 filters");}
            for (CbrFilter f : filters) {
                if (f instanceof AllOf) {throw new IllegalArgumentException("AllOf cannot contain nested AllOf");}
            }
            filters = List.copyOf(filters);
        }
    }


    static Contains contains(String value) { return new Contains(value); }
    static ContainsAll containsAll(List<String> values) { return new ContainsAll(values); }
    static ContainsAny containsAny(List<String> values) { return new ContainsAny(values); }

    static HasMatch hasMatch(Map<String, FeatureValue> subFields) {return new HasMatch(subFields);}

    static NotContains notContains(String value)            {return new NotContains(value);}

    static NotContainsAny notContainsAny(List<String> values) {return new NotContainsAny(values);}

    static ContainsRange containsRange(NumericRange range)    {return new ContainsRange(range);}

    static AllOf allOf(CbrFilter... filters)                  {return new AllOf(List.of(filters));}


}
