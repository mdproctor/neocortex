package io.casehub.neocortex.knowledge.resolution;

import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.EntityMatcher;
import io.casehub.neocortex.knowledge.MatchTier;
import io.casehub.neocortex.knowledge.testing.EntityMatcherContractTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceMatcherTest extends EntityMatcherContractTest {

    @Override
    protected EntityMatcher<CachedEntity> createMatcher() {
        return new PlaceMatcher();
    }

    @Test
    void phoneMatchIsHigh() {
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "g1",
            Map.of("phone", "+44 131 226 1888"));
        var b = entity("2", "Ondine Seafood", 51.50003, -0.10003, "tripadvisor", "t1",
            Map.of("phone", "0131 226 1888"));
        var result = createMatcher().match(a, b);
        assertThat(result.tier()).isIn(MatchTier.DEFINITIVE, MatchTier.HIGH);
        assertThat(result.matchedSignals()).anyMatch(s -> s.contains("phone"));
    }

    @Test
    void differentSourcesSameExternalIdIsNotDefinitive() {
        var a = entity("1", "Ondine", 51.5, -0.1, "google", "123", Map.of());
        var b = entity("2", "Ondine", 51.5, -0.1, "tripadvisor", "123", Map.of());
        var result = createMatcher().match(a, b);
        assertThat(result.tier()).isNotEqualTo(MatchTier.DEFINITIVE);
    }
}
