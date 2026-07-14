package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.OutcomeWeightingFunction;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
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
public class ReactiveOutcomeWeightingCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private final ReactiveCbrCaseMemoryStore delegate;
    private final OutcomeWeightingFunction weightingFunction;

    @Inject
    ReactiveOutcomeWeightingCbrCaseMemoryStore(@Delegate @Any ReactiveCbrCaseMemoryStore delegate,
                                               OutcomeWeightingFunction weightingFunction) {
        this.delegate = delegate;
        this.weightingFunction = weightingFunction;
    }

    @Override
    public Uni<Void> registerSchema(CbrFeatureSchema schema) {
        return delegate.registerSchema(schema);
    }

    @Override
    public Uni<String> store(CbrCase cbrCase, String caseType, String entityId,
                             MemoryDomain domain, String tenantId, String caseId) {
        return delegate.store(cbrCase, caseType, entityId, domain, tenantId, caseId);
    }

    @Override
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        return delegate.retrieveSimilar(query, caseClass)
                .map(results -> {
                    if (results.isEmpty()) {
                        return results;
                    }
                    List<ScoredCbrCase<C>> weighted = new ArrayList<>(results.size());
                    for (ScoredCbrCase<C> scored : results) {
                        double confidence = scored.cbrCase().confidence() != null
                                ? scored.cbrCase().confidence() : 1.0;
                        double newScore = weightingFunction.apply(scored.score(), confidence);
                        weighted.add(new ScoredCbrCase<>(scored.cbrCase(), scored.caseId(),
                                newScore, scored.reranked(), scored.featureSimilarities()));
                    }
                    weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
                    return Collections.unmodifiableList(weighted);
                });
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
    public Uni<Void> recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        return delegate.recordOutcome(caseId, tenantId, outcome);
    }

    @Override
    public Uni<Integer> purge(CbrRetentionPolicy policy) {
        return delegate.purge(policy);
    }

}
