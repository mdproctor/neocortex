package io.casehub.neocortex.knowledge;

import java.util.List;
import java.util.Objects;

public record DomainSupport(
    String domain,
    SearchableProvider provider,
    CacheKeyGenerator keyGenerator,
    SubsumptionRule subsumption,
    EntityMatcher<CachedEntity> matcher,
    BlockingStrategy blockingStrategy,
    List<TermNormalizer> normalizerChain
) {
    public DomainSupport {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(keyGenerator);
        Objects.requireNonNull(subsumption);
        Objects.requireNonNull(matcher);
        Objects.requireNonNull(blockingStrategy);
        normalizerChain = List.copyOf(normalizerChain);
    }

    public static DomainSupport minimal(String domain, SearchableProvider provider) {
        return new DomainSupport(domain, provider,
            TextCacheKeyGenerator.INSTANCE,
            TextSubsumptionRule.INSTANCE,
            NameEntityMatcher.INSTANCE,
            NoOpBlockingStrategy.INSTANCE,
            List.of());
    }
}
