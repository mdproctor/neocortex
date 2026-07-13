package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface FeatureValue {

    record StringVal(String value) implements FeatureValue {
        public StringVal { Objects.requireNonNull(value, "value"); }
    }

    record NumberVal(double value) implements FeatureValue {}

    record RangeVal(double min, double max) implements FeatureValue {
        public RangeVal {
            if (min > max) throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
        }
    }

    record StringListVal(List<String> values) implements FeatureValue {
        public StringListVal {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record NumberListVal(List<Double> values) implements FeatureValue {
        public NumberListVal {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    record StructVal(Map<String, FeatureValue> fields) implements FeatureValue {
        public StructVal {
            Objects.requireNonNull(fields, "fields");
            fields = Map.copyOf(fields);
        }
    }

    record StructListVal(List<Map<String, FeatureValue>> items) implements FeatureValue {
        public StructListVal {
            Objects.requireNonNull(items, "items");
            items = items.stream().map(Map::copyOf).toList();
        }
    }

    static StringVal string(String value) { return new StringVal(value); }
    static NumberVal number(double value) { return new NumberVal(value); }
    static RangeVal range(double min, double max) { return new RangeVal(min, max); }
    static StringListVal stringList(String... values) { return new StringListVal(List.of(values)); }
    static StringListVal stringList(List<String> values) { return new StringListVal(values); }
    static NumberListVal numberList(Double... values) { return new NumberListVal(List.of(values)); }
    static NumberListVal numberList(List<Double> values) { return new NumberListVal(values); }
    static StructVal struct(Map<String, FeatureValue> fields) { return new StructVal(fields); }
    static StructListVal structList(List<Map<String, FeatureValue>> items) { return new StructListVal(items); }
    @SafeVarargs
    static StructListVal structList(Map<String, FeatureValue>... items) { return new StructListVal(List.of(items)); }

    @SuppressWarnings("unchecked")
    static FeatureValue of(Object value) {
        if (value instanceof FeatureValue fv) {return fv;}
        if (value instanceof String s) {return string(s);}
        if (value instanceof Number n) {return number(n.doubleValue());}
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {return stringList(List.of());}
            Object first = list.getFirst();
            if (first instanceof String) {return stringList((List<String>) list);}
            if (first instanceof Number) {
                return numberList(list.stream().map(e -> ((Number) e).doubleValue()).toList());
            }
            throw new IllegalArgumentException("unsupported list element type: " + first.getClass().getName());
        }
        throw new IllegalArgumentException("unsupported feature value type: " + value.getClass().getName());
    }

    static Map<String, FeatureValue> toFeatureMap(Map<String, Object> raw) {
        var result = new java.util.LinkedHashMap<String, FeatureValue>(raw.size());
        raw.forEach((k, v) -> {if (v != null) {result.put(k, of(v));}});
        return result;
    }

}
