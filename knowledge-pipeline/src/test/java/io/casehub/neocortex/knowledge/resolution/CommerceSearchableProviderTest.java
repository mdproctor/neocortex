package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.commerce.model.Money;
import io.casehub.connectors.commerce.model.Product;
import io.casehub.connectors.commerce.spi.CommercePlatform;
import io.casehub.connectors.location.model.Coordinates;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.CacheFilter;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceSearchableProviderTest {

    @Test
    void domainIsCommerce() {
        var provider = new CommerceSearchableProvider(stubPlatform(), Duration.ofHours(1));
        assertThat(provider.domain()).isEqualTo("commerce");
    }

    @Test
    void idDelegatesToPlatform() {
        var provider = new CommerceSearchableProvider(stubPlatform(), Duration.ofHours(1));
        assertThat(provider.id()).isEqualTo("stub-commerce");
    }

    @Test
    void supportsTextSearch() {
        var provider = new CommerceSearchableProvider(stubPlatform(), Duration.ofHours(1));
        assertThat(provider.supports(new KnowledgeQuery.TextSearch("test", "commerce"))).isTrue();
    }

    @Test
    void doesNotSupportNearbySearch() {
        var provider = new CommerceSearchableProvider(stubPlatform(), Duration.ofHours(1));
        assertThat(provider.supports(
            new KnowledgeQuery.NearbySearch(new Coordinates(0, 0), 100, null))).isFalse();
    }

    @Test
    void searchConvertsProductToCachedEntity() {
        var provider = new CommerceSearchableProvider(stubPlatform(), Duration.ofHours(1));
        var result = provider.search(
            new KnowledgeQuery.TextSearch("headphones", "commerce"),
            new PipelinePageRequest(null, 10));
        assertThat(result.items()).hasSize(1);
        CachedEntity entity = result.items().get(0);
        assertThat(entity.name()).isEqualTo("Wireless Headphones");
        assertThat(entity.domain()).isEqualTo("commerce");
        assertThat(entity.coordinates()).isNull();
        assertThat(entity.category()).isEqualTo("electronics");
        assertThat(entity.properties().get("price")).isEqualTo("29.99 USD");
        assertThat(entity.properties().get("brand")).isEqualTo("Sony");
        assertThat(entity.source()).isEqualTo("stub-commerce");
        assertThat(entity.externalId()).isEqualTo("p1");
    }

    @Test
    void handlesNullOptionalFields() {
        var platform = new StubCommercePlatform(List.of(
            new Product("p2", "Basic Item", null, null, null, null, null, false, null)
        ));
        var provider = new CommerceSearchableProvider(platform, Duration.ofHours(1));
        var result = provider.search(
            new KnowledgeQuery.TextSearch("basic", "commerce"),
            new PipelinePageRequest(null, 10));
        CachedEntity entity = result.items().get(0);
        assertThat(entity.name()).isEqualTo("Basic Item");
        assertThat(entity.properties()).doesNotContainKey("brand");
        assertThat(entity.properties()).doesNotContainKey("price");
    }

    private CommercePlatform stubPlatform() {
        return new StubCommercePlatform(List.of(
            new Product("p1", "Wireless Headphones", "Sony", "electronics",
                new Money(new BigDecimal("29.99"), "USD"),
                4.5, 100, true, null)
        ));
    }

    static class StubCommercePlatform implements CommercePlatform {
        private final List<Product> products;

        StubCommercePlatform(List<Product> products) { this.products = products; }

        @Override public String id() { return "stub-commerce"; }
        @Override public boolean supports(Class<?> c) { return c == ProductSearch.class; }
        @Override public ProductSearch productSearch(String userId) {
            return new ProductSearch() {
                @Override public Page<Product> search(String query, PageRequest p) {
                    return new Page<>(products, null, false);
                }
                @Override public Page<Product> searchByCategory(String cat, PageRequest p) {
                    return new Page<>(List.of(), null, false);
                }
                @Override public Page<Product> searchByBrand(String brand, PageRequest p) {
                    return new Page<>(List.of(), null, false);
                }
            };
        }
        @Override public ProductDetails productDetails(String u) { return null; }
        @Override public Cart cart(String u) { return null; }
        @Override public Checkout checkout(String u) { return null; }
        @Override public OrderTracking orderTracking(String u) { return null; }
    }
}
