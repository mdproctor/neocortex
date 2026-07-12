package io.casehub.neocortex.memory.cbr.inmem;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Alternative
@Priority(2)
@ApplicationScoped
public class InMemoryCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    private record StoredCase(
        String id, CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
        String tenantId, String caseId, Instant storedAt
    ) {}

    private final List<StoredCase> cases = new CopyOnWriteArrayList<>();

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }
        String id = UUID.randomUUID().toString();
        cases.add(new StoredCase(id, cbrCase, caseType, entityId, domain, tenantId, caseId, Instant.now()));
        return id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        if (query.retrievalMode() == RetrievalMode.SEMANTIC_ONLY) {
            return List.of();
        }
        if (query.retrievalMode() == RetrievalMode.HYBRID && query.problem() != null) {
            java.util.logging.Logger.getLogger(getClass().getName())
                .warning("HYBRID mode degraded to FEATURE_ONLY — no EmbeddingModel available");
        }

        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            CbrFeatureValidator.validateQueryFeatures(query.features(), schema);
        }

        if (!query.filters().isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                    "Cannot apply structural filters: no schema registered for caseType '"
                    + query.caseType() + "'");
            }
            CbrFeatureValidator.validateFilters(query.filters(), schema);
        }

        List<ScoredCbrCase<C>> candidates = new ArrayList<>();
        for (StoredCase stored : cases) {
            if (!stored.tenantId().equals(query.tenantId())) continue;
            if (!stored.domain().equals(query.domain())) continue;
            if (!stored.caseType().equals(query.caseType())) continue;
            if (query.notBefore() != null && stored.storedAt().isBefore(query.notBefore())) continue;
            if (!caseClass.isInstance(stored.cbrCase())) continue;

            if (!matchesFilters(stored.cbrCase(), query.filters(), schema)) continue;

            double featureScore = CbrSimilarityScorer.score(
                query.features(), stored.cbrCase().features(), query.weights(), schema, Map.of());

            if (featureScore >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>((C) stored.cbrCase(), featureScore));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<ScoredCbrCase<C>> results = candidates.size() <= query.topK()
            ? candidates
            : candidates.subList(0, query.topK());
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    @Override
    public Integer erase(EraseRequest request) {
        int before = cases.size();
        cases.removeIf(sc ->
            sc.entityId().equals(request.entityId())
            && sc.domain().equals(request.domain())
            && sc.tenantId().equals(request.tenantId())
            && (request.caseId() == null || sc.caseId().equals(request.caseId())));
        return before - cases.size();
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        int before = cases.size();
        cases.removeIf(sc ->
            sc.entityId().equals(entityId) && sc.tenantId().equals(tenantId));
        return before - cases.size();
    }

    @SuppressWarnings("unchecked")
    private boolean matchesFilters(CbrCase storedCase, Map<String, CbrFilter> filters,
                                    CbrFeatureSchema schema) {
        if (filters.isEmpty()) {return true;}
        for (var entry : filters.entrySet()) {
            String    fieldName   = entry.getKey();
            CbrFilter filter      = entry.getValue();
            Object    storedValue = storedCase.features().get(fieldName);
            if (storedValue == null) {return false;}

            FeatureField field = CbrFeatureValidator.findField(schema, fieldName);

            if (!matchesSingleFilter(storedValue, filter, field)) {return false;}
        }
        return true;}

    private boolean matchesSingleFilter(Object storedValue, CbrFilter filter, FeatureField field) {
        return switch (filter) {
            case CbrFilter.Contains c -> storedValue instanceof List<?> list && list.contains(c.value());
            case CbrFilter.ContainsAll ca -> storedValue instanceof List<?> list && list.containsAll(ca.values());
            case CbrFilter.ContainsAny ca -> storedValue instanceof List<?> list && ca.values().stream().anyMatch(list::contains);
            case CbrFilter.NotContains nc -> storedValue instanceof List<?> list && !list.contains(nc.value());
            case CbrFilter.NotContainsAny nca -> storedValue instanceof List<?> list && nca.values().stream().noneMatch(list::contains);
            case CbrFilter.ContainsRange cr -> storedValue instanceof List<?> list && list.stream()
                                                                                          .filter(Number.class::isInstance).map(Number.class::cast)
                                                                                          .anyMatch(n -> n.doubleValue() >= cr.range().min() && n.doubleValue() <= cr.range().max());
            case CbrFilter.HasMatch hm -> matchesHasMatch(storedValue, hm, field);
            case CbrFilter.AllOf allOf -> {
                for (CbrFilter inner : allOf.filters()) {
                    if (!matchesSingleFilter(storedValue, inner, field)) {yield false;}
                }
                yield true;
            }
        };
    }


    private boolean matchesHasMatch(Object storedValue, CbrFilter.HasMatch hm, FeatureField field) {
        if (field instanceof FeatureField.ObjectList) {
            if (!(storedValue instanceof List<?> list)) return false;
            return list.stream().anyMatch(elem ->
                elem instanceof Map<?,?> map && allSubFieldsMatch(map, hm.subFields()));
        } else {
            if (!(storedValue instanceof Map<?,?> map)) return false;
            return allSubFieldsMatch(map, hm.subFields());
        }
    }

    private boolean allSubFieldsMatch(Map<?,?> stored, Map<String, Object> subFields) {
        for (var sub : subFields.entrySet()) {
            Object storedVal = stored.get(sub.getKey());
            if (storedVal == null) return false;
            Object queryVal = sub.getValue();
            if (queryVal instanceof NumericRange range) {
                if (!(storedVal instanceof Number num)) return false;
                double d = num.doubleValue();
                if (d < range.min() || d > range.max()) return false;
            } else if (queryVal instanceof Number qn) {
                if (!(storedVal instanceof Number sn)) return false;
                if (Double.compare(qn.doubleValue(), sn.doubleValue()) != 0) return false;
            } else {
                if (!queryVal.equals(storedVal)) return false;
            }
        }
        return true;
    }
}
