package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Range;

import java.util.Map;

/**
 * Translates {@link CbrQuery} features + schema to Qdrant {@link Filter} conditions.
 *
 * <p>Base filters (tenantId, domain, caseType) are always applied.
 * Feature filters depend on the schema: categorical becomes keyword match,
 * numeric becomes equality match, text becomes keyword match.
 * Unknown fields (not in schema) are silently ignored.
 */
final class CbrQueryTranslator {

    private CbrQueryTranslator() {}

    /**
     * Build a Qdrant filter from a CBR query and its registered schema.
     *
     * @param query  the CBR query
     * @param schema the feature schema (may be null if no schema registered)
     * @return a Qdrant filter with all conditions combined via must
     */
    static Filter toFilter(CbrQuery query, CbrFeatureSchema schema) {
        Filter.Builder builder = Filter.newBuilder();

        // Always filter by tenant, domain, and case type
        builder.addMust(ConditionFactory.matchKeyword("tenantId", query.tenantId()));
        builder.addMust(ConditionFactory.matchKeyword("domain", query.domain().name()));
        builder.addMust(ConditionFactory.matchKeyword("caseType", query.caseType()));

        // Validate and add feature filters
        if (!query.features().isEmpty() && schema != null) {
            validateQueryFeatures(query.features(), schema);

            for (Map.Entry<String, Object> entry : query.features().entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();

                FeatureField field = findField(schema, name);
                if (field == null) {
                    continue; // unknown fields ignored
                }

                String payloadKey = "f_" + name;
                if (field instanceof FeatureField.Categorical) {
                    builder.addMust(ConditionFactory.matchKeyword(payloadKey, (String) value));
                } else if (field instanceof FeatureField.Numeric) {
                    builder.addMust(ConditionFactory.range(payloadKey,
                        Range.newBuilder()
                            .setGte(((Number) value).doubleValue())
                            .setLte(((Number) value).doubleValue())
                            .build()));
                } else if (field instanceof FeatureField.Text) {
                    builder.addMust(ConditionFactory.matchKeyword(payloadKey, (String) value));
                }
            }
        }

        return builder.build();
    }

    /**
     * Validate query features against schema types.
     * Throws IllegalArgumentException on type mismatches.
     */
    static void validateQueryFeatures(Map<String, Object> features, CbrFeatureSchema schema) {
        for (Map.Entry<String, Object> entry : features.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) continue;

            Object value = entry.getValue();
            if (field instanceof FeatureField.Categorical) {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                        "Categorical field '" + entry.getKey() + "' requires String, got: "
                        + value.getClass().getSimpleName());
                }
            } else if (field instanceof FeatureField.Numeric) {
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException(
                        "Numeric field '" + entry.getKey() + "' requires Number, got: "
                        + value.getClass().getSimpleName());
                }
            } else if (field instanceof FeatureField.Text) {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                        "Text field '" + entry.getKey() + "' requires String, got: "
                        + value.getClass().getSimpleName());
                }
            }
        }
    }

    private static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
