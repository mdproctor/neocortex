package io.casehub.neocortex.knowledge;

import io.casehub.connectors.location.model.Coordinates;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CachedEntity(
    String id,
    String name,
    Coordinates coordinates,
    String category,
    String source,
    String externalId,
    Map<String, String> properties,
    Instant fetchedAt,
    Instant detailFetchedAt,
    Instant expiresAt,
    Set<String> sessionIds,
    boolean hasDetail
) {

    public CachedEntity {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        Objects.requireNonNull(externalId);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        sessionIds = sessionIds == null ? Set.of() : Set.copyOf(sessionIds);
    }
}
