package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.ScopeDecay;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
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
@Priority(85)
public class ReactiveScopeDecayCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private final ReactiveCbrCaseMemoryStore delegate;

    @Inject
    public ReactiveScopeDecayCbrCaseMemoryStore(@Delegate @Any ReactiveCbrCaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseType) {
        return delegate.retrieveSimilar(query, caseType)
                .map(results -> {
                    if (query.scopeDecay() == null) {
                        return results;
                    }
                    ScopeDecay decay = query.scopeDecay();
                    int queryDepth = query.scope().depth();
                    List<ScoredCbrCase<C>> decayed = new ArrayList<>(results.size());
                    for (var scored : results) {
                        int depthDistance = queryDepth - scored.scope().depth();
                        double factor = decay.factor(depthDistance);
                        double adjustedScore = scored.score() * factor;
                        if (adjustedScore >= query.minSimilarity()) {
                            decayed.add(scored.withScore(adjustedScore));
                        }
                    }
                    decayed.sort((a, b) -> Double.compare(b.score(), a.score()));
                    return Collections.unmodifiableList(decayed);
                });
    }

    @Override public Uni<Void> registerSchema(CbrFeatureSchema schema) { return delegate.registerSchema(schema); }
    @Override public Uni<String> store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, Path scope) { return delegate.store(c, ct, e, d, t, ci, scope); }
    @Override public Uni<Integer> erase(EraseRequest r) { return delegate.erase(r); }
    @Override public Uni<Integer> eraseEntity(String e, String t) { return delegate.eraseEntity(e, t); }

    @Override
    public Uni<Integer> eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}

    @Override public Uni<Void> recordOutcome(String ci, String t, CbrOutcome o) { return delegate.recordOutcome(ci, t, o); }
    @Override public Uni<Integer> purge(CbrRetentionPolicy p) { return delegate.purge(p); }
    @Override public Uni<Void> supersede(String caseId, String tenantId, String supersedingCaseId, String reason) { return delegate.supersede(caseId, tenantId, supersedingCaseId, reason); }
    @Override public Uni<Void> reinstate(String caseId, String tenantId) { return delegate.reinstate(caseId, tenantId); }

    @Override
    public io.smallrye.mutiny.Uni<SupersessionStatus> getSupersessionStatus(String caseId, String tenantId) {
        return delegate.getSupersessionStatus(caseId, tenantId);
    }

    @Override
    public io.smallrye.mutiny.Uni<java.util.List<SupersessionStatus>> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
        return delegate.findSupersededCases(tenantId, domain);
    }

}
