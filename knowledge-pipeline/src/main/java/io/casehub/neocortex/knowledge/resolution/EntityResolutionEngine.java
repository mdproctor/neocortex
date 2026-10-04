package io.casehub.neocortex.knowledge.resolution;

import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.EntityMatcher;
import io.casehub.neocortex.knowledge.MatchResult;
import io.casehub.neocortex.knowledge.MatchTier;
import io.casehub.neocortex.knowledge.SpatialCacheStore;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.SignalCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EntityResolutionEngine {

    private final EntityMatcher<CachedEntity> matcher;
    private final int blockingRadiusMeters;
    private final double autoMergeThreshold;
    private final double signalThreshold;

    public EntityResolutionEngine(EntityMatcher<CachedEntity> matcher,
                                   int blockingRadiusMeters,
                                   double autoMergeThreshold,
                                   double signalThreshold) {
        this.matcher = matcher;
        this.blockingRadiusMeters = blockingRadiusMeters;
        this.autoMergeThreshold = autoMergeThreshold;
        this.signalThreshold = signalThreshold;
    }

    public EntityResolutionEngine(EntityMatcher<CachedEntity> matcher) {
        this(matcher, 200, 0.8, 0.6);
    }

    public record ResolutionResult(
        List<CachedEntity> resolved,
        List<AttentionSignal> signals
    ) {}

    public ResolutionResult resolve(List<CachedEntity> newEntities,
                                     SpatialCacheStore cacheStore,
                                     DedupIndexStore dedupStore,
                                     String tenantId) {
        List<CachedEntity> resolved = new ArrayList<>();
        List<AttentionSignal> signals = new ArrayList<>();

        for (CachedEntity entity : newEntities) {
            var dedupEntry = dedupStore.lookup(entity.source(), entity.externalId());
            if (dedupEntry.isPresent()) {
                resolved.add(entity);
                continue;
            }

            if (entity.coordinates() == null) {
                resolved.add(entity);
                continue;
            }

            List<CachedEntity> candidates = cacheStore.nearby(
                entity.coordinates(), blockingRadiusMeters,
                CacheFilter.none(), tenantId);

            CachedEntity bestMatch = null;
            MatchResult bestResult = null;

            for (CachedEntity candidate : candidates) {
                if (candidate.id().equals(entity.id())) continue;
                MatchResult result = matcher.match(entity, candidate);
                if (bestResult == null || result.confidence() > bestResult.confidence()) {
                    bestResult = result;
                    bestMatch = candidate;
                }
            }

            if (bestResult != null && bestResult.confidence() >= autoMergeThreshold) {
                resolved.add(mergeEntities(entity, bestMatch));
            } else if (bestResult != null && bestResult.confidence() >= signalThreshold) {
                resolved.add(entity);
                signals.add(new AttentionSignal(
                    null, tenantId, SignalCategory.MERGE_CANDIDATE,
                    entity.id(), entity.name(),
                    bestResult.confidence(),
                    String.join("; ", bestResult.matchedSignals())));
            } else {
                resolved.add(entity);
            }
        }

        return new ResolutionResult(resolved, signals);
    }

    private CachedEntity mergeEntities(CachedEntity primary, CachedEntity existing) {
        var mergedProps = new java.util.HashMap<>(existing.properties());
        mergedProps.putAll(primary.properties());
        return new CachedEntity(
            primary.id(), primary.name(), primary.coordinates(),
            primary.category(), primary.source(), primary.externalId(),
            mergedProps, primary.fetchedAt(), primary.detailFetchedAt(), primary.expiresAt(),
            primary.sessionIds(), primary.hasDetail() || existing.hasDetail());
    }
}
