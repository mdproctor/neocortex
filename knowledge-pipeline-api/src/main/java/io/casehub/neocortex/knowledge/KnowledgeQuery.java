package io.casehub.neocortex.knowledge;

import io.casehub.connectors.location.model.Coordinates;

import java.util.Objects;

public interface KnowledgeQuery {

    default String domain() { return "location"; }

    record TextSearch(String query, String domain) implements KnowledgeQuery {
        public TextSearch { Objects.requireNonNull(query); }
    }

    record NearbySearch(Coordinates center, int radiusMeters,
                        CacheFilter filters) implements KnowledgeQuery {
        public NearbySearch {
            Objects.requireNonNull(center);
            if (radiusMeters <= 0) throw new IllegalArgumentException("radiusMeters must be positive");
        }
    }

    record CategorySearch(String category, Coordinates center,
                          int radiusMeters) implements KnowledgeQuery {
        public CategorySearch {
            Objects.requireNonNull(category);
            Objects.requireNonNull(center);
            if (radiusMeters <= 0) throw new IllegalArgumentException("radiusMeters must be positive");
        }
    }
}
