package io.casehub.rag.runtime;

import io.casehub.rag.CorpusRef;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenancyStrategyTest {

    private final CorpusRef corpus = new CorpusRef("tenant-abc", "legal");

    @Test
    void separateCollectionName() {
        assertThat(TenancyStrategy.SEPARATE_COLLECTIONS.collectionName(corpus))
            .isEqualTo("tenant-abc_legal");
    }

    @Test
    void separateCollectionHasNoFilter() {
        assertThat(TenancyStrategy.SEPARATE_COLLECTIONS.tenantFilter(corpus)).isEmpty();
    }

    @Test
    void sharedCollectionName() {
        assertThat(TenancyStrategy.SHARED_COLLECTION.collectionName(corpus))
            .isEqualTo("legal");
    }

    @Test
    void sharedCollectionHasFilter() {
        assertThat(TenancyStrategy.SHARED_COLLECTION.tenantFilter(corpus)).isPresent();
    }
}
