package io.casehub.neocortex.knowledge.promotion;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.PromotionRequest;
import io.casehub.neocortex.knowledge.PromotionResult;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.mindmap.MindMapStore;

@FunctionalInterface
public interface PromotionStrategy {
    PromotionResult promote(CachedEntity entity, PromotionRequest request,
                             MindMapStore mindMapStore, DedupIndexStore dedupStore);
}
