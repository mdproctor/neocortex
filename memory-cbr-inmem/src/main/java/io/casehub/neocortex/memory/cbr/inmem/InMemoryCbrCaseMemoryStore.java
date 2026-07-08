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
        if (query.retrievalMode() == RetrievalMode.SEMANTIC_ONLY) {
            return List.of();
        }
        if (query.retrievalMode() == RetrievalMode.HYBRID && query.problem() != null) {
            java.util.logging.Logger.getLogger(getClass().getName())
                .warning("HYBRID mode degraded to FEATURE_ONLY — no EmbeddingModel available");
        }

        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            validateQueryFeatures(query.features(), schema);
        }

        List<ScoredCbrCase<C>> candidates = new ArrayList<>();
        for (StoredCase stored : cases) {
            // Identity filters — hard constraints
            if (!stored.tenantId().equals(query.tenantId())) continue;
            if (!stored.domain().equals(query.domain())) continue;
            if (!stored.caseType().equals(query.caseType())) continue;
            if (query.notBefore() != null && stored.storedAt().isBefore(query.notBefore())) continue;
            if (!caseClass.isInstance(stored.cbrCase())) continue;

            // Graded feature similarity scoring
            double featureScore = CbrSimilarityScorer.score(
                query.features(), stored.cbrCase().features(), query.weights(), schema, Map.of());

            if (featureScore >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>((C) stored.cbrCase(), featureScore));
            }
        }

        // Sort by score descending, take topK
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
