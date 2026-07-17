package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Decorator
@Priority(80)
public class TemporalDecayCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final CbrCaseMemoryStore delegate;

    @Inject
    public TemporalDecayCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseType) {
        List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseType);
        if (query.temporalDecay() == null) {
            return results;
        }
        Instant now = Instant.now();
        TemporalDecay decay = query.temporalDecay();
        List<ScoredCbrCase<C>> decayed = new ArrayList<>(results.size());
        for (var scored : results) {
            double factor = (scored.storedAt() != null)
                    ? decay.factor(scored.storedAt(), now) : 1.0;
            double adjustedScore = scored.score() * factor;
            if (adjustedScore >= query.minSimilarity()) {
                decayed.add(scored.withScore(adjustedScore));
            }
        }
        decayed.sort((a, b) -> Double.compare(b.score(), a.score()));
        return Collections.unmodifiableList(decayed);
    }

    @Override public void registerSchema(CbrFeatureSchema schema) { delegate.registerSchema(schema); }
    @Override public String store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, io.casehub.platform.api.path.Path scope) { return delegate.store(c, ct, e, d, t, ci, scope); }
    @Override public Integer erase(EraseRequest r) { return delegate.erase(r); }
    @Override public Integer eraseEntity(String e, String t) { return delegate.eraseEntity(e, t); }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {return delegate.eraseByScope(scope, tenantId);}

    @Override public void recordOutcome(String ci, String t, CbrOutcome o) { delegate.recordOutcome(ci, t, o); }
    @Override public Integer purge(CbrRetentionPolicy p) { return delegate.purge(p); }
    @Override public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) { delegate.supersede(caseId, tenantId, supersedingCaseId, reason); }
    @Override public void reinstate(String caseId, String tenantId) { delegate.reinstate(caseId, tenantId); }
}
