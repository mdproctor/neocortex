package io.casehub.neocortex.knowledge;

import java.util.List;
import java.util.Optional;

public enum TextSubsumptionRule implements SubsumptionRule {
    INSTANCE;

    @Override
    public Optional<List<CachedEntity>> subsume(NormalizedQuery query,
            List<CachedEntity> broaderResults, NormalizedQuery broaderQuery) {
        if (broaderQuery.cacheKey().contains(query.cacheKey())) {
            return Optional.of(broaderResults);
        }
        return Optional.empty();
    }
}
