package io.casehub.neocortex.knowledge.testing;

import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.EntityMatcher;
import io.casehub.neocortex.knowledge.MatchTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class EntityMatcherContractTest {

    protected abstract EntityMatcher<CachedEntity> createMatcher();

    protected CachedEntity entity(String id, String name, double lat, double lng,
                                   String source, String extId, Map<String, String> props) {
        return new CachedEntity(id, name, new Coordinates(lat, lng), "restaurant",
            source, extId, props, Instant.now(),
            null, Instant.now().plusSeconds(86400), Set.of(), false);
    }

    @Test
    void sameExternalIdIsDefinitive() {
        var matcher = createMatcher();
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "ChIJ123", Map.of());
        var b = entity("2", "Ondine Seafood", 51.5001, -0.1001, "google", "ChIJ123", Map.of());
        var result = matcher.match(a, b);
        assertThat(result.tier()).isEqualTo(MatchTier.DEFINITIVE);
    }

    @Test
    void closeProximityAndSimilarNameMatches() {
        var matcher = createMatcher();
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "g1", Map.of());
        var b = entity("2", "Ondine Restaurant", 51.50003, -0.10003, "tripadvisor", "t1", Map.of());
        var result = matcher.match(a, b);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.65);
    }

    @Test
    void distantEntitiesAreNoMatch() {
        var matcher = createMatcher();
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "g1", Map.of());
        var b = entity("2", "Ondine", 55.9, -3.1, "tripadvisor", "t1", Map.of());
        var result = matcher.match(a, b);
        assertThat(result.tier()).isEqualTo(MatchTier.LOW);
    }

    @Test
    void noMatchingSignalsReturnsLow() {
        var matcher = createMatcher();
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "g1", Map.of());
        var b = entity("2", "Costa Coffee", 51.5005, -0.1005, "tripadvisor", "t1", Map.of());
        var result = matcher.match(a, b);
        assertThat(result.tier()).isEqualTo(MatchTier.LOW);
    }
}
