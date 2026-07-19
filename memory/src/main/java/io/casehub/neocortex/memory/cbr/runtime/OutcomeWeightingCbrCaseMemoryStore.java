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
import io.casehub.neocortex.memory.cbr.OutcomeWeightingFunction;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Decorator
@Priority(65)
@IfBuildProperty(name = "casehub.cbr.outcome-weighting.enabled", stringValue = "true")
public class OutcomeWeightingCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final CbrCaseMemoryStore delegate;
    private final OutcomeWeightingFunction weightingFunction;

    @Inject
    OutcomeWeightingCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate,
                                       OutcomeWeightingFunction weightingFunction) {
        this.delegate = delegate;
        this.weightingFunction = weightingFunction;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        delegate.registerSchema(schema);
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId, scope);
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseClass);
        if (results.isEmpty()) {
            return results;
        }
        List<ScoredCbrCase<C>> weighted = new ArrayList<>(results.size());
        for (ScoredCbrCase<C> scored : results) {
            double confidence = scored.cbrCase().confidence() != null
                                ? scored.cbrCase().confidence() : 1.0;
            double newScore = weightingFunction.apply(scored.score(), confidence);
            weighted.add(scored.withScore(newScore));
        }
        weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
        return Collections.unmodifiableList(weighted);}

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
