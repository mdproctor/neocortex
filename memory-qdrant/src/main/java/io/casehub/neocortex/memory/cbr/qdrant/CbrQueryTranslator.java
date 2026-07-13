package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
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

            for (var entry : query.features().entrySet()) {
                String name = entry.getKey();
                FeatureValue value = entry.getValue();

                FeatureField field = findField(schema, name);
                if (field == null) {
                    continue; // unknown fields ignored
                }

                String payloadKey = "f_" + name;
                switch (field) {
                    case FeatureField.Categorical c ->
                        builder.addMust(ConditionFactory.matchKeyword(payloadKey, ((FeatureValue.StringVal) value).value()));
                    case FeatureField.Numeric n -> {
                        if (value instanceof FeatureValue.RangeVal range) {
                            builder.addMust(ConditionFactory.range(payloadKey,
                                Range.newBuilder()
                                    .setGte(range.min())
                                    .setLte(range.max())
                                    .build()));
                        } else if (value instanceof FeatureValue.NumberVal nv) {
                            builder.addMust(ConditionFactory.range(payloadKey,
                                Range.newBuilder()
                                    .setGte(nv.value())
                                    .setLte(nv.value())
                                    .build()));
                        }
                    }
                    case FeatureField.Text t ->
                        builder.addMust(ConditionFactory.matchKeyword(payloadKey, ((FeatureValue.StringVal) value).value()));
                    case FeatureField.CategoricalList cl -> throw new IllegalStateException(
                        "Structured field in toFilter — use applyStructuralFilters");
                    case FeatureField.NumericList nl -> throw new IllegalStateException(
                        "Structured field in toFilter — use applyStructuralFilters");
                    case FeatureField.NestedObject no -> throw new IllegalStateException(
                        "Structured field in toFilter — use applyStructuralFilters");
                    case FeatureField.ObjectList ol -> throw new IllegalStateException(
                        "Structured field in toFilter — use applyStructuralFilters");
                    case FeatureField.TimeSeries ts -> {}
                    case FeatureField.DiscreteSequence ds -> {}
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

    static Filter applyStructuralFilters(Filter baseFilter,
                                         Map<String, CbrFilter> filters,
                                         CbrFeatureSchema schema) {
        if (filters.isEmpty()) {return baseFilter;}
        CbrFeatureValidator.validateFilters(filters, schema);

        Filter.Builder builder = baseFilter.toBuilder();
        for (var entry : filters.entrySet()) {
            String       payloadKey = "f_" + entry.getKey();
            CbrFilter    filter     = entry.getValue();
            FeatureField field      = CbrFeatureValidator.findField(schema, entry.getKey());

            applyFilter(builder, payloadKey, filter, field);
        }
        return builder.build();}

    private static void applyFilter(Filter.Builder builder, String payloadKey,
                                    CbrFilter filter, FeatureField field) {
        switch (filter) {
            case CbrFilter.Contains c -> builder.addMust(ConditionFactory.matchKeyword(payloadKey, c.value()));
            case CbrFilter.ContainsAll ca -> ca.values().forEach(v ->
                                                                         builder.addMust(ConditionFactory.matchKeyword(payloadKey, v)));
            case CbrFilter.ContainsAny ca -> builder.addMust(ConditionFactory.matchKeywords(payloadKey, ca.values()));
            case CbrFilter.NotContains nc -> builder.addMustNot(ConditionFactory.matchKeyword(payloadKey, nc.value()));
            case CbrFilter.NotContainsAny nca -> nca.values().forEach(v ->
                                                                              builder.addMustNot(ConditionFactory.matchKeyword(payloadKey, v)));
            case CbrFilter.ContainsRange cr -> builder.addMust(ConditionFactory.range(payloadKey,
                                                                                      Range.newBuilder().setGte(cr.range().min()).setLte(cr.range().max()).build()));
            case CbrFilter.HasMatch hm -> {
                if (field instanceof FeatureField.ObjectList) {
                    Filter.Builder inner = Filter.newBuilder();
                    for (var sub : hm.subFields().entrySet()) {
                        addSubFieldCondition(inner, sub.getKey(), sub.getValue());
                    }
                    builder.addMust(ConditionFactory.nested(payloadKey, inner.build()));
                } else {
                    for (var sub : hm.subFields().entrySet()) {
                        addSubFieldCondition(builder, payloadKey + "." + sub.getKey(), sub.getValue());
                    }
                }
            }
            case CbrFilter.AllOf allOf -> {
                for (CbrFilter inner : allOf.filters()) {
                    applyFilter(builder, payloadKey, inner, field);
                }
            }
        }
    }


    private static void addSubFieldCondition(Filter.Builder builder, String key, FeatureValue value) {
        switch (value) {
            case FeatureValue.StringVal s ->
                builder.addMust(ConditionFactory.matchKeyword(key, s.value()));
            case FeatureValue.RangeVal r ->
                builder.addMust(ConditionFactory.range(key,
                                                       Range.newBuilder().setGte(r.min()).setLte(r.max()).build()));
            case FeatureValue.NumberVal n ->
                builder.addMust(ConditionFactory.range(key,
                                                       Range.newBuilder().setGte(n.value()).setLte(n.value()).build()));
            default -> throw new IllegalArgumentException("Unsupported sub-field value type: " + value.getClass().getSimpleName());
        }
    }


    /**
     * Validate query features against schema types.
     * Throws IllegalArgumentException on type mismatches.
     */
    static void validateQueryFeatures(Map<String, FeatureValue> features, CbrFeatureSchema schema) {CbrFeatureValidator.validateQueryFeatures(features, schema);}

    private static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
