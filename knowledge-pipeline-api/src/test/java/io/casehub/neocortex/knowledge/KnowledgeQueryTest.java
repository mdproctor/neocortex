package io.casehub.neocortex.knowledge;

import io.casehub.connectors.location.model.Coordinates;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeQueryTest {

    @Test
    void textSearchRequiresQuery() {
        assertThatThrownBy(() -> new KnowledgeQuery.TextSearch(null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nearbySearchRequiresPositiveRadius() {
        assertThatThrownBy(() -> new KnowledgeQuery.NearbySearch(
                new Coordinates(51.5, -0.1), 0, CacheFilter.none()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categorySearchCreatesValidQuery() {
        var q = new KnowledgeQuery.CategorySearch("italian",
            new Coordinates(51.5, -0.1), 1000);
        assertThat(q.category()).isEqualTo("italian");
        assertThat(q.radiusMeters()).isEqualTo(1000);
    }

    @Test
    void sealedHierarchyPermitsPatternMatch() {
        KnowledgeQuery q = new KnowledgeQuery.TextSearch("pizza", null);
        String result = switch (q) {
            case KnowledgeQuery.TextSearch t -> "text:" + t.query();
            case KnowledgeQuery.NearbySearch n -> "nearby";
            case KnowledgeQuery.CategorySearch c -> "cat:" + c.category();
            default -> "unknown";
        };
        assertThat(result).isEqualTo("text:pizza");
    }

    @Test
    void textSearchAcceptsDomain() {
        var q = new KnowledgeQuery.TextSearch("dolly", "commerce");
        assertThat(q.query()).isEqualTo("dolly");
        assertThat(q.domain()).isEqualTo("commerce");
    }

    @Test
    void textSearchAcceptsNullDomain() {
        var q = new KnowledgeQuery.TextSearch("pizza", null);
        assertThat(q.query()).isEqualTo("pizza");
        assertThat(q.domain()).isNull();
    }

}
