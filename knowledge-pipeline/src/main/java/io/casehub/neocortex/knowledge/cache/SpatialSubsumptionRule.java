package io.casehub.neocortex.knowledge.cache;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.NormalizedQuery;
import io.casehub.neocortex.knowledge.SubsumptionRule;
import io.casehub.neocortex.knowledge.resolution.Haversine;

import java.util.List;
import java.util.Optional;

public class SpatialSubsumptionRule implements SubsumptionRule {

    @Override
    public Optional<List<CachedEntity>> subsume(NormalizedQuery query,
                                                 List<CachedEntity> broaderResults,
                                                 NormalizedQuery broaderQuery) {
        return switch (query.query()) {
            case KnowledgeQuery.CategorySearch narrow ->
                tryCategorySubsumption(narrow, broaderResults, broaderQuery);
            case KnowledgeQuery.NearbySearch narrow ->
                tryNearbySubsumption(narrow, broaderResults, broaderQuery);
            case KnowledgeQuery.TextSearch ignored -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<List<CachedEntity>> tryCategorySubsumption(
            KnowledgeQuery.CategorySearch narrow,
            List<CachedEntity> broaderResults,
            NormalizedQuery broaderQuery) {
        if (!(broaderQuery.query() instanceof KnowledgeQuery.CategorySearch broader)) {
            if (!(broaderQuery.query() instanceof KnowledgeQuery.NearbySearch broaderNearby)) {
                return Optional.empty();
            }
            if (!isContainedCircle(narrow.center().lat(), narrow.center().lng(),
                    narrow.radiusMeters(), broaderNearby.center().lat(),
                    broaderNearby.center().lng(), broaderNearby.radiusMeters())) {
                return Optional.empty();
            }
            return Optional.of(filterByCategory(broaderResults, narrow.category()));
        }
        if (!broader.category().equalsIgnoreCase(narrow.category())) {
            return Optional.empty();
        }
        if (!isContainedCircle(narrow.center().lat(), narrow.center().lng(),
                narrow.radiusMeters(), broader.center().lat(),
                broader.center().lng(), broader.radiusMeters())) {
            return Optional.empty();
        }
        return Optional.of(filterByRadius(broaderResults, narrow));
    }

    private Optional<List<CachedEntity>> tryNearbySubsumption(
            KnowledgeQuery.NearbySearch narrow,
            List<CachedEntity> broaderResults,
            NormalizedQuery broaderQuery) {
        if (!(broaderQuery.query() instanceof KnowledgeQuery.NearbySearch broader)) {
            return Optional.empty();
        }
        if (!isContainedCircle(narrow.center().lat(), narrow.center().lng(),
                narrow.radiusMeters(), broader.center().lat(),
                broader.center().lng(), broader.radiusMeters())) {
            return Optional.empty();
        }
        return Optional.of(filterByRadius(broaderResults, narrow));
    }

    private boolean isContainedCircle(double narrowLat, double narrowLng, int narrowRadius,
                                       double broaderLat, double broaderLng, int broaderRadius) {
        double centerDist = Haversine.distanceMeters(
            new io.casehub.connectors.location.model.Coordinates(narrowLat, narrowLng),
            new io.casehub.connectors.location.model.Coordinates(broaderLat, broaderLng));
        return centerDist + narrowRadius <= broaderRadius;
    }

    private List<CachedEntity> filterByRadius(List<CachedEntity> results,
                                                KnowledgeQuery.NearbySearch narrow) {
        var center = narrow.center();
        return results.stream()
            .filter(e -> e.coordinates() != null)
            .filter(e -> Haversine.distanceMeters(center, e.coordinates()) <= narrow.radiusMeters())
            .toList();
    }

    private List<CachedEntity> filterByRadius(List<CachedEntity> results,
                                                KnowledgeQuery.CategorySearch narrow) {
        var center = narrow.center();
        return results.stream()
            .filter(e -> e.coordinates() != null)
            .filter(e -> Haversine.distanceMeters(center, e.coordinates()) <= narrow.radiusMeters())
            .toList();
    }

    private List<CachedEntity> filterByCategory(List<CachedEntity> results,
                                                  String category) {
        return results.stream()
            .filter(e -> category.equalsIgnoreCase(e.category()))
            .toList();
    }
}
