package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.NumericRange;
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
     * Build an identity-only Qdrant filter — tenant, domain, caseType, and notBefore.
     * Feature conditions are excluded; they are handled by client-side graded scoring.
     *
     * @param query the CBR query
     * @return a Qdrant filter with identity conditions only
     */
    static Filter toIdentityFilter(CbrQuery query) {
        Filter.Builder builder = Filter.newBuilder();

        builder.addMust(ConditionFactory.matchKeyword("tenantId", query.tenantId()));
        builder.addMust(ConditionFactory.matchKeyword("domain", query.domain().name()));
        builder.addMust(ConditionFactory.matchKeyword("caseType", query.caseType()));

        if (query.notBefore() != null) {
            builder.addMust(ConditionFactory.range("_stored_at",
                Range.newBuilder()
                    .setGte(query.notBefore().toEpochMilli())
                    .build()));
        }

        return builder.build();
    }

    /**
     * Build a Qdrant filter from a CBR query and its registered schema.
     * Retained for backward compatibility — includes feature conditions as hard filters.
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
                switch (field) {
                    case FeatureField.Categorical c ->
                        builder.addMust(ConditionFactory.matchKeyword(payloadKey, (String) value));
                    case FeatureField.Numeric n -> {
                        if (value instanceof NumericRange range) {
                            builder.addMust(ConditionFactory.range(payloadKey,
                                Range.newBuilder()
                                    .setGte(range.min())
                                    .setLte(range.max())
                                    .build()));
                        } else {
                            builder.addMust(ConditionFactory.range(payloadKey,
                                Range.newBuilder()
                                    .setGte(((Number) value).doubleValue())
                                    .setLte(((Number) value).doubleValue())
                                    .build()));
                        }
                    }
                    case FeatureField.Text t ->
                        builder.addMust(ConditionFactory.matchKeyword(payloadKey, (String) value));
                }
            }
        }

        if (query.notBefore() != null) {
            builder.addMust(ConditionFactory.range("_stored_at",
                Range.newBuilder()
                    .setGte(query.notBefore().toEpochMilli())
                    .build()));
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
            switch (field) {
                case FeatureField.Categorical c -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException(
                            "Categorical field '" + entry.getKey() + "' requires String, got: "
                            + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Numeric n -> {
                    if (!(value instanceof Number) && !(value instanceof NumericRange)) {
                        throw new IllegalArgumentException(
                            "Numeric field '" + entry.getKey() + "' requires Number or NumericRange, got: "
                            + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Text t -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException(
                            "Text field '" + entry.getKey() + "' requires String, got: "
                            + value.getClass().getSimpleName());
                    }
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
