package io.casehub.neocortex.memory.cbr.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(3)
@ApplicationScoped
public class JpaCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(JpaCbrCaseMemoryStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>> PLAN_TRACE_TYPE = new TypeReference<>() {};

    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
    }

    @Override
    @Transactional
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }

        CbrCaseEntity entity = new CbrCaseEntity();
        entity.id = UUID.randomUUID().toString();
        entity.tenantId = tenantId;
        entity.domain = domain.name();
        entity.caseType = caseType;
        entity.cbrType = cbrCase.cbrType();
        entity.entityId = entityId;
        entity.caseId = caseId;
        entity.problem = cbrCase.problem();
        entity.solution = cbrCase.solution();
        entity.outcome = cbrCase.outcome();
        entity.confidence = cbrCase.confidence();
        entity.features = serializeJson(cbrCase.features());
        entity.storedAt = Instant.now();

        if (cbrCase instanceof PlanCbrCase plan && !plan.planTrace().isEmpty()) {
            entity.planTraces = serializeJson(plan.planTrace());
        }

        em.persist(entity);
        return entity.id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        if (query.retrievalMode() == RetrievalMode.SEMANTIC_ONLY) {
            return List.of();
        }
        if (query.retrievalMode() == RetrievalMode.HYBRID && query.problem() != null) {
            LOG.info("HYBRID mode degraded to FEATURE_ONLY — no EmbeddingModel available");
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

        String jpql = "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct"
                + (query.notBefore() != null ? " AND e.storedAt >= :nb" : "");

        var jpaQuery = em.createQuery(jpql, CbrCaseEntity.class)
                .setParameter("t", query.tenantId())
                .setParameter("d", query.domain().name())
                .setParameter("ct", query.caseType());
        if (query.notBefore() != null) {
            jpaQuery.setParameter("nb", query.notBefore());
        }

        List<CbrCaseEntity> entities = jpaQuery.getResultList();
        List<ScoredCbrCase<C>> candidates = new ArrayList<>();

        for (CbrCaseEntity entity : entities) {
            CbrCase reconstructed = reconstruct(entity);
            if (!caseClass.isInstance(reconstructed)) continue;

            if (!matchesFilters(reconstructed, query.filters(), schema)) continue;

            CbrSimilarityScorer.SimilarityBreakdown breakdown = CbrSimilarityScorer.scoreDetailed(
                    query.features(), reconstructed.features(), query.weights(), schema, Map.of());

            if (breakdown.score() >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>((C) reconstructed, breakdown.score(), false,
                        breakdown.featureSimilarities()));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<ScoredCbrCase<C>> results = candidates.size() <= query.topK()
                ? candidates
                : candidates.subList(0, query.topK());
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    @Override
    @Transactional
    public Integer erase(EraseRequest request) {
        String jpql = "DELETE FROM CbrCaseEntity e WHERE e.entityId = :eid AND e.domain = :d AND e.tenantId = :t";
        if (request.caseId() != null) {
            jpql += " AND e.caseId = :cid";
        }
        var q = em.createQuery(jpql)
                .setParameter("eid", request.entityId())
                .setParameter("d", request.domain().name())
                .setParameter("t", request.tenantId());
        if (request.caseId() != null) {
            q.setParameter("cid", request.caseId());
        }
        return q.executeUpdate();
    }

    @Override
    @Transactional
    public Integer eraseEntity(String entityId, String tenantId) {
        return em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.entityId = :eid AND e.tenantId = :t")
                .setParameter("eid", entityId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    private CbrCase reconstruct(CbrCaseEntity entity) {
        Map<String, Object> features = deserializeMap(entity.features);
        return switch (entity.cbrType) {
            case "plan" -> new PlanCbrCase(
                    entity.problem, entity.solution, entity.outcome, entity.confidence,
                    features, deserializePlanTraces(entity.planTraces));
            case "feature-vector" -> new FeatureVectorCbrCase(
                    entity.problem, entity.solution, entity.outcome, entity.confidence, features);
            case "textual" -> new TextualCbrCase(
                    entity.problem, entity.solution, entity.outcome, entity.confidence);
            default -> new FeatureVectorCbrCase(
                    entity.problem, entity.solution, entity.outcome, entity.confidence, features);
        };
    }

    @SuppressWarnings("unchecked")
    private boolean matchesFilters(CbrCase storedCase, Map<String, CbrFilter> filters,
                                   CbrFeatureSchema schema) {
        if (filters.isEmpty()) return true;
        for (var entry : filters.entrySet()) {
            String fieldName = entry.getKey();
            CbrFilter filter = entry.getValue();
            Object storedValue = storedCase.features().get(fieldName);
            if (storedValue == null) return false;

            FeatureField field = CbrFeatureValidator.findField(schema, fieldName);
            if (!matchesSingleFilter(storedValue, filter, field)) return false;
        }
        return true;
    }

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
                    if (!matchesSingleFilter(storedValue, inner, field)) yield false;
                }
                yield true;
            }
        };
    }

    private boolean matchesHasMatch(Object storedValue, CbrFilter.HasMatch hm, FeatureField field) {
        if (field instanceof FeatureField.ObjectList) {
            if (!(storedValue instanceof List<?> list)) return false;
            return list.stream().anyMatch(elem ->
                    elem instanceof Map<?, ?> map && allSubFieldsMatch(map, hm.subFields()));
        } else {
            if (!(storedValue instanceof Map<?, ?> map)) return false;
            return allSubFieldsMatch(map, hm.subFields());
        }
    }

    private boolean allSubFieldsMatch(Map<?, ?> stored, Map<String, Object> subFields) {
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

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize to JSON", e);
        }
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize features JSON", e);
        }
    }

    private List<PlanTrace> deserializePlanTraces(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, PLAN_TRACE_TYPE);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to deserialize plan traces: %s", e.getMessage());
            return List.of();
        }
    }
}
