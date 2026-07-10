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

    record HasMatch(Map<String, Object> subFields) implements CbrFilter {
        public HasMatch {
            Objects.requireNonNull(subFields, "subFields");
            if (subFields.isEmpty()) throw new IllegalArgumentException("subFields must not be empty");
            for (Map.Entry<String, Object> e : subFields.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof NumericRange))
                    throw new IllegalArgumentException(
                        "Sub-field '" + e.getKey() + "' value must be String, Number, or NumericRange, got: "
                        + v.getClass().getSimpleName());
            }
            subFields = Map.copyOf(subFields);
        }
    }

    static Contains contains(String value) { return new Contains(value); }
    static ContainsAll containsAll(List<String> values) { return new ContainsAll(values); }
    static ContainsAny containsAny(List<String> values) { return new ContainsAny(values); }
    static HasMatch hasMatch(Map<String, Object> subFields) { return new HasMatch(subFields); }
}
