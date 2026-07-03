package io.casehub.neocortex.memory.cbr.inmem;

import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.*;
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
        String id = UUID.randomUUID().toString();
        cases.add(new StoredCase(id, cbrCase, caseType, entityId, domain, tenantId, caseId, Instant.now()));
        return id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            validateQueryFeatures(query.features(), schema);
        }

        List<ScoredCbrCase<C>> results = new ArrayList<>();
        for (StoredCase stored : cases) {
            if (!stored.tenantId().equals(query.tenantId())) continue;
            if (!stored.domain().equals(query.domain())) continue;
            if (!stored.caseType().equals(query.caseType())) continue;
            if (query.notBefore() != null && stored.storedAt().isBefore(query.notBefore())) continue;
            if (!caseClass.isInstance(stored.cbrCase())) continue;
            if (schema != null && !matchesFeatures(stored.cbrCase(), query.features(), schema)) continue;

            results.add(new ScoredCbrCase<>((C) stored.cbrCase(), 1.0));
            if (results.size() >= query.topK()) break;
        }
        return Collections.unmodifiableList(results);
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

    private boolean matchesFeatures(CbrCase stored, Map<String, Object> queryFeatures,
                                     CbrFeatureSchema schema) {
        if (queryFeatures.isEmpty()) return true;
        Map<String, Object> storedFeatures = stored.features();
        if (storedFeatures.isEmpty()) return true;

        for (var entry : queryFeatures.entrySet()) {
            String name = entry.getKey();
            Object queryValue = entry.getValue();
            Object storedValue = storedFeatures.get(name);
            if (storedValue == null) continue;

            FeatureField field = schema != null
                ? schema.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null)
                : null;

            if (field instanceof FeatureField.Categorical) {
                if (!queryValue.equals(storedValue)) return false;
            } else if (field instanceof FeatureField.Numeric) {
                double storedNum = ((Number) storedValue).doubleValue();
                if (queryValue instanceof NumericRange range) {
                    if (!range.contains(storedNum)) return false;
                } else if (queryValue instanceof Number queryNum) {
                    if (Double.compare(queryNum.doubleValue(), storedNum) != 0) return false;
                }
            }
        }
        return true;
    }

    private void validateQueryFeatures(Map<String, Object> features, CbrFeatureSchema schema) {
        for (var entry : features.entrySet()) {
            FeatureField field = schema.fields().stream()
                .filter(f -> f.name().equals(entry.getKey()))
                .findFirst()
                .orElse(null);
            if (field == null) continue;

            if (field instanceof FeatureField.Categorical && !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                    "Categorical field '" + entry.getKey() + "' requires String, got: "
                    + entry.getValue().getClass().getSimpleName());
            }
            if (field instanceof FeatureField.Numeric
                    && !(entry.getValue() instanceof Number)
                    && !(entry.getValue() instanceof NumericRange)) {
                throw new IllegalArgumentException(
                    "Numeric field '" + entry.getKey() + "' requires Number or NumericRange, got: "
                    + entry.getValue().getClass().getSimpleName());
            }
            if (field instanceof FeatureField.Text && !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                    "Text field '" + entry.getKey() + "' requires String, got: "
                    + entry.getValue().getClass().getSimpleName());
            }
        }
    }
}
