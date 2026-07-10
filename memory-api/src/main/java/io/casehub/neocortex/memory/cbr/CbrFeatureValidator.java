package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;

public final class CbrFeatureValidator {

    private CbrFeatureValidator() {}

    public static void validateStoreFeatures(Map<String, Object> features, CbrFeatureSchema schema) {
        for (var entry : features.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) continue;
            Object value = entry.getValue();
            switch (field) {
                case FeatureField.Categorical c -> requireType(entry.getKey(), value, String.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof Number) && !(value instanceof NumericRange))
                        throw new IllegalArgumentException(
                            "Numeric field '" + entry.getKey() + "' requires Number or NumericRange, got: "
                            + value.getClass().getSimpleName());
                }
                case FeatureField.Text t -> requireType(entry.getKey(), value, String.class, "Text");
                case FeatureField.CategoricalList cl -> {
                    if (!(value instanceof List<?> list))
                        throw new IllegalArgumentException(
                            "CategoricalList field '" + entry.getKey() + "' requires List, got: "
                            + value.getClass().getSimpleName());
                    for (Object elem : list) {
                        if (!(elem instanceof String))
                            throw new IllegalArgumentException(
                                "CategoricalList field '" + entry.getKey() + "' requires List<String>, element is: "
                                + elem.getClass().getSimpleName());
                    }
                }
                case FeatureField.NestedObject no -> {
                    if (!(value instanceof Map<?,?> map))
                        throw new IllegalArgumentException(
                            "NestedObject field '" + entry.getKey() + "' requires Map, got: "
                            + value.getClass().getSimpleName());
                    validateInnerValues(entry.getKey(), map, no.innerFields());
                }
                case FeatureField.ObjectList ol -> {
                    if (!(value instanceof List<?> list))
                        throw new IllegalArgumentException(
                            "ObjectList field '" + entry.getKey() + "' requires List, got: "
                            + value.getClass().getSimpleName());
                    for (Object elem : list) {
                        if (!(elem instanceof Map<?,?> map))
                            throw new IllegalArgumentException(
                                "ObjectList field '" + entry.getKey() + "' requires List<Map>, element is: "
                                + elem.getClass().getSimpleName());
                        validateInnerValues(entry.getKey(), map, ol.innerFields());
                    }
                }
            }
        }
    }

    public static void validateQueryFeatures(Map<String, Object> features, CbrFeatureSchema schema) {
        for (var entry : features.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) continue;
            Object value = entry.getValue();
            switch (field) {
                case FeatureField.Categorical c -> requireType(entry.getKey(), value, String.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof Number) && !(value instanceof NumericRange))
                        throw new IllegalArgumentException(
                            "Numeric field '" + entry.getKey() + "' requires Number or NumericRange, got: "
                            + value.getClass().getSimpleName());
                }
                case FeatureField.Text t -> requireType(entry.getKey(), value, String.class, "Text");
                case FeatureField.CategoricalList cl -> throw new IllegalArgumentException(
                    "Structured field '" + entry.getKey() + "' must be queried via filters, not features");
                case FeatureField.NestedObject no -> throw new IllegalArgumentException(
                    "Structured field '" + entry.getKey() + "' must be queried via filters, not features");
                case FeatureField.ObjectList ol -> throw new IllegalArgumentException(
                    "Structured field '" + entry.getKey() + "' must be queried via filters, not features");
            }
        }
    }

    public static void validateFilters(Map<String, CbrFilter> filters, CbrFeatureSchema schema) {
        for (var entry : filters.entrySet()) {
            String name = entry.getKey();
            CbrFilter filter = entry.getValue();
            FeatureField field = findField(schema, name);
            if (field == null)
                throw new IllegalArgumentException("Filter field '" + name + "' not found in schema");

            switch (filter) {
                case CbrFilter.Contains c -> requireCategoricalList(name, field);
                case CbrFilter.ContainsAll ca -> requireCategoricalList(name, field);
                case CbrFilter.ContainsAny ca -> requireCategoricalList(name, field);
                case CbrFilter.HasMatch hm -> {
                    if (!(field instanceof FeatureField.NestedObject) && !(field instanceof FeatureField.ObjectList))
                        throw new IllegalArgumentException(
                            "HasMatch filter on '" + name + "' requires NestedObject or ObjectList field, got: "
                            + field.getClass().getSimpleName());
                    List<FeatureField> innerFields = field instanceof FeatureField.NestedObject no
                        ? no.innerFields() : ((FeatureField.ObjectList) field).innerFields();
                    validateHasMatchSubFields(name, hm, innerFields);
                }
            }
        }
    }

    private static void validateHasMatchSubFields(String fieldName, CbrFilter.HasMatch hm,
                                                   List<FeatureField> innerFields) {
        for (var sub : hm.subFields().entrySet()) {
            FeatureField inner = null;
            for (FeatureField f : innerFields) {
                if (f.name().equals(sub.getKey())) { inner = f; break; }
            }
            if (inner == null)
                throw new IllegalArgumentException(
                    "HasMatch sub-field '" + sub.getKey() + "' not found in inner schema of '" + fieldName + "'");

            Object value = sub.getValue();
            switch (inner) {
                case FeatureField.Categorical c -> {
                    if (!(value instanceof String))
                        throw new IllegalArgumentException(
                            "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                            + "' requires String, got: " + value.getClass().getSimpleName());
                }
                case FeatureField.Text t -> {
                    if (!(value instanceof String))
                        throw new IllegalArgumentException(
                            "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                            + "' requires String, got: " + value.getClass().getSimpleName());
                }
                case FeatureField.Numeric n -> {
                    if (!(value instanceof Number) && !(value instanceof NumericRange))
                        throw new IllegalArgumentException(
                            "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                            + "' requires Number or NumericRange, got: " + value.getClass().getSimpleName());
                }
                default -> throw new IllegalStateException("Unexpected inner field type: " + inner);
            }
        }
    }

    private static void requireCategoricalList(String name, FeatureField field) {
        if (!(field instanceof FeatureField.CategoricalList))
            throw new IllegalArgumentException(
                "Contains/ContainsAll/ContainsAny filter on '" + name
                + "' requires CategoricalList field, got: " + field.getClass().getSimpleName());
    }

    private static void requireType(String name, Object value, Class<?> expected, String fieldTypeName) {
        if (!expected.isInstance(value))
            throw new IllegalArgumentException(
                fieldTypeName + " field '" + name + "' requires " + expected.getSimpleName()
                + ", got: " + value.getClass().getSimpleName());
    }

    private static void validateInnerValues(String parentName, Map<?,?> map,
                                             List<FeatureField> innerFields) {
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            FeatureField inner = null;
            for (FeatureField f : innerFields) {
                if (f.name().equals(key)) { inner = f; break; }
            }
            if (inner == null) continue;
            Object value = entry.getValue();
            switch (inner) {
                case FeatureField.Categorical c -> requireType(parentName + "." + key, value, String.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof Number))
                        throw new IllegalArgumentException(
                            "Inner field '" + parentName + "." + key + "' requires Number, got: "
                            + value.getClass().getSimpleName());
                }
                case FeatureField.Text t -> requireType(parentName + "." + key, value, String.class, "Text");
                default -> {}
            }
        }
    }

    public static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
