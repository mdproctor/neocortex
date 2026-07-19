package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TrendAnalyzer;
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
public class TrendEnrichmentCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final CbrCaseMemoryStore delegate;
    private final ConcurrentHashMap<String, CbrFeatureSchema> expandedSchemas = new ConcurrentHashMap<>();

    @Inject
    TrendEnrichmentCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        CbrFeatureSchema expanded = TrendAnalyzer.expandSchema(schema);
        expandedSchemas.put(schema.caseType(), expanded);
        delegate.registerSchema(expanded);
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
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
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
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
    public Integer erase(EraseRequest request) {
        return delegate.erase(request);
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        return delegate.eraseEntity(entityId, tenantId);
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}


    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        delegate.recordOutcome(caseId, tenantId, outcome);
    }

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy);
    }

    @Override
    public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        delegate.supersede(caseId, tenantId, supersedingCaseId, reason);
    }

    @Override
    public void reinstate(String caseId, String tenantId) {
        delegate.reinstate(caseId, tenantId);
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) {
        return delegate.getSupersessionStatus(caseId, tenantId);
    }

    @Override
    public java.util.List<SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
        return delegate.findSupersededCases(tenantId, domain);
    }

}
