package io.casehub.neocortex.knowledge.promotion;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.PromotionRequest;
import io.casehub.neocortex.knowledge.PromotionResult;
import io.casehub.neocortex.knowledge.dedup.DedupIndexStore;
import io.casehub.neocortex.mindmap.MindMapStore;

public enum NoOpPromotionStrategy implements PromotionStrategy {
    INSTANCE;

    @Override
    public PromotionResult promote(CachedEntity entity, PromotionRequest request,
                                    MindMapStore mindMapStore, DedupIndexStore dedupStore) {
        throw new UnsupportedOperationException(
            "No promotion strategy registered for entity: " + entity.name());
    }
}
