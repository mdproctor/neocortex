package io.casehub.neocortex.memory.cbr.crossencoder;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.ReactiveCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Decorator
@Priority(75)
@Unremovable
@IfBuildProperty(name = "casehub.cbr.reranking.enabled", stringValue = "true")
public class ReactiveRerankingCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private final ReactiveCbrCaseMemoryStore delegate;
    private final CrossEncoderReranker reranker;
    private final CbrRerankingConfig config;

    @Inject
    ReactiveRerankingCbrCaseMemoryStore(@Delegate @Any ReactiveCbrCaseMemoryStore delegate,
                                         Instance<CrossEncoderReranker> rerankerInstance,
                                         CbrRerankingConfig config) {
        this.delegate = delegate;
        this.reranker = rerankerInstance.isResolvable() ? rerankerInstance.get() : null;
        this.config = config;
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
        if (shouldSkip(query)) {
            return delegate.retrieveSimilar(query, caseClass);
        }

        int fetchSize = Math.max(query.topK(), config.rerankPoolSize());
        CbrQuery overfetchQuery = new CbrQuery(
                query.tenantId(), query.domain(), query.caseType(),
                query.features(), query.filters(), query.weights(), fetchSize,
                query.minSimilarity(), query.notBefore(), query.problem(),
                query.vectorWeight(), query.retrievalMode(), query.fusionStrategy());

        return delegate.retrieveSimilar(overfetchQuery, caseClass)
                       .onItem().transformToUni(candidates -> {
                    if (candidates.isEmpty()) {
                        return Uni.createFrom().item(candidates);
                    }
                    if (candidates.stream().allMatch(ScoredCbrCase::reranked)) {
                        int limit = Math.min(candidates.size(), query.topK());
                        return Uni.createFrom().item(
                                Collections.unmodifiableList(new ArrayList<>(candidates.subList(0, limit))));
                    }
                    return Uni.createFrom().item(() ->
                                                         rerankBlocking(query, candidates))
                              .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                });}

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


    private <C extends CbrCase> List<ScoredCbrCase<C>> rerankBlocking(
            CbrQuery query, List<ScoredCbrCase<C>> candidates) {
        List<String> problemTexts = candidates.stream()
            .map(c -> c.cbrCase().problem() != null ? c.cbrCase().problem() : "")
            .toList();

        List<RankedResult> ranked = reranker.rerank(query.problem(), problemTexts);

        List<ScoredCbrCase<C>> results = new ArrayList<>(
            Math.min(ranked.size(), query.topK()));
        for (int i = 0; i < Math.min(ranked.size(), query.topK()); i++) {
            RankedResult r = ranked.get(i);
            ScoredCbrCase<C> original = candidates.get(r.originalIndex());
            double sigmoidScore = 1.0 / (1.0 + Math.exp(-r.score()));
            results.add(new ScoredCbrCase<C>(original.cbrCase(), sigmoidScore).withReranked());
        }
        return Collections.unmodifiableList(results);
    }

    private boolean shouldSkip(CbrQuery query) {
        if (reranker == null) return true;
        if (query.retrievalMode() == RetrievalMode.FEATURE_ONLY) return true;
        return query.problem() == null;
    }
}
