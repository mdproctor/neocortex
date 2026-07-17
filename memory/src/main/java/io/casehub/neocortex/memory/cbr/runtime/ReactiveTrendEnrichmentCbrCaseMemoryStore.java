package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TrendAnalyzer;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Decorator
@Priority(90)
public class ReactiveTrendEnrichmentCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private final ReactiveCbrCaseMemoryStore delegate;
    private final ConcurrentHashMap<String, CbrFeatureSchema> expandedSchemas = new ConcurrentHashMap<>();

    @Inject
    ReactiveTrendEnrichmentCbrCaseMemoryStore(@Delegate @Any ReactiveCbrCaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Uni<Void> registerSchema(CbrFeatureSchema schema) {
        CbrFeatureSchema expanded = TrendAnalyzer.expandSchema(schema);
        expandedSchemas.put(schema.caseType(), expanded);
        return delegate.registerSchema(expanded);
    }

    @Override
    public Uni<String> store(CbrCase cbrCase, String caseType, String entityId,
                             MemoryDomain domain, String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        CbrFeatureSchema schema = expandedSchemas.get(caseType);
        if (schema != null) {
            Map<String, FeatureValue> enriched = TrendAnalyzer.enrichFeatures(cbrCase.features(), schema);
            if (enriched != cbrCase.features()) {
                cbrCase = cbrCase.withFeatures(enriched);
            }
        }
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope);
    }

    @Override
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        CbrFeatureSchema schema = expandedSchemas.get(query.caseType());
        if (schema != null) {
            Map<String, FeatureValue> enriched = TrendAnalyzer.enrichFeatures(query.features(), schema);
            if (enriched != query.features()) {
                query = query.withFeatures(enriched);
            }
        }
        return delegate.retrieveSimilar(query, caseClass);
    }

    @Override
    public Uni<Integer> erase(EraseRequest request) {
        return delegate.erase(request);
    }

    @Override
    public Uni<Integer> eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId);
    }

    @Override
    public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}


    @Override
    public Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        return delegate.recordOutcome(caseId, tenantId, outcome);
    }

    @Override
    public Uni<Integer> purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy);
    }

    @Override
    public Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        return delegate.supersede(caseId, tenantId, supersedingCaseId, reason);
    }

    @Override
    public Uni<Void> reinstate(String caseId, String tenantId) {
        return delegate.reinstate(caseId, tenantId);
    }
}
