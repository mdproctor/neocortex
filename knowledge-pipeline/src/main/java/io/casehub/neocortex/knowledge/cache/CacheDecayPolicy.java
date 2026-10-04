package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.CachedEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class CacheDecayPolicy {
    public enum StaleFieldGroup {BASIC, DETAIL}


    private final Duration coordinatesTtl;
    private final Duration ratingTtl;
    private final Duration contactTtl;
    private final Duration hoursTtl;
    private final Duration reviewsTtl;
    private final Duration imagesTtl;
    private final Duration searchResultsTtl;

    public CacheDecayPolicy() {
        this(Duration.ofDays(30), Duration.ofDays(3), Duration.ofDays(7),
             Duration.ofDays(7), Duration.ofDays(3), Duration.ofDays(14),
             Duration.ofDays(1));
    }

    public CacheDecayPolicy(Duration coordinatesTtl, Duration ratingTtl,
                             Duration contactTtl, Duration hoursTtl,
                             Duration reviewsTtl, Duration imagesTtl,
                             Duration searchResultsTtl) {
        this.coordinatesTtl = coordinatesTtl;
        this.ratingTtl = ratingTtl;
        this.contactTtl = contactTtl;
        this.hoursTtl = hoursTtl;
        this.reviewsTtl = reviewsTtl;
        this.imagesTtl = imagesTtl;
        this.searchResultsTtl = searchResultsTtl;
    }

    public Duration ttlFor(CachedEntity entity) {
        if (entity.hasDetail()) {
            return coordinatesTtl;
        }
        return coordinatesTtl;
    }

    public Duration searchResultsTtl() {
        return searchResultsTtl;
    }

    public Duration coordinatesTtl() { return coordinatesTtl; }
    public Duration ratingTtl() { return ratingTtl; }
    public Duration contactTtl() { return contactTtl; }
    public Duration hoursTtl() { return hoursTtl; }
    public Duration reviewsTtl() { return reviewsTtl; }
    public Duration imagesTtl() { return imagesTtl; }

    public Set<StaleFieldGroup> staleGroups(CachedEntity entity, Instant now) {
        Set<StaleFieldGroup> stale = java.util.EnumSet.noneOf(StaleFieldGroup.class);
        if (entity.fetchedAt() != null && entity.fetchedAt().plus(coordinatesTtl).isBefore(now)) {
            stale.add(StaleFieldGroup.BASIC);
        }
        Duration detailTtl = minDuration(ratingTtl, contactTtl, hoursTtl, reviewsTtl);
        if (entity.detailFetchedAt() == null || entity.detailFetchedAt().plus(detailTtl).isBefore(now)) {
            stale.add(StaleFieldGroup.DETAIL);
        }
        return stale;
    }

    private static Duration minDuration(Duration... durations) {
        Duration min = durations[0];
        for (int i = 1; i < durations.length; i++) {
            if (durations[i].compareTo(min) < 0) {min = durations[i];}
        }
        return min;
    }

}
