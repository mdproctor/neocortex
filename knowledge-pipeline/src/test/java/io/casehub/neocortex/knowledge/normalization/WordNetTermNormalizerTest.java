package io.casehub.neocortex.knowledge.normalization;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class WordNetTermNormalizerTest {

    private static WordNetTermNormalizer normalizer;

    @BeforeAll
    static void init() throws Exception {
        normalizer = new WordNetTermNormalizer();
        normalizer.init();
    }

    @Test
    void knownSynonymSetReturnsCanonicalAndVariants() {
        var result = normalizer.normalize("eatery", "location");
        assertThat(result.canonical()).isNotNull();
        assertThat(result.variants()).contains("eatery");
        assertThat(result.variants().size()).isGreaterThan(1);
    }

    @Test
    void unknownTermReturnsPassthrough() {
        var result = normalizer.normalize("xyznotaword", "commerce");
        assertThat(result.canonical()).isEqualTo("xyznotaword");
        assertThat(result.variants()).containsExactly("xyznotaword");
    }

    @Test
    void nullDomainReturnsPassthrough() {
        var result = normalizer.normalize("bank", null);
        assertThat(result.canonical()).isEqualTo("bank");
        assertThat(result.variants()).containsExactly("bank");
    }

    @Test
    void blankTermReturnsPassthrough() {
        var result = normalizer.normalize("  ", "location");
        assertThat(result.canonical()).isEqualTo("  ");
        assertThat(result.variants()).containsExactly("  ");
    }

    @Test
    void multiWordCompoundLookup() {
        var result = normalizer.normalize("coffee shop", "location");
        assertThat(result.canonical()).isNotNull();
        assertThat(result.variants().size()).isGreaterThan(1);
    }

    @Test
    void commerceDomainNormalizesArtifacts() {
        var result = normalizer.normalize("headphones", "commerce");
        assertThat(result.canonical()).isNotNull();
        assertThat(result.variants()).contains("earphone");
        assertThat(result.variants().size()).isGreaterThan(1);
    }

    @Test
    void generalDomainNormalizesCommonNouns() {
        var result = normalizer.normalize("automobile", "general");
        assertThat(result.canonical()).isNotNull();
        assertThat(result.variants().size()).isGreaterThan(1);
    }

    @Test
    void locationDomainWorksWithNewKey() {
        var result = normalizer.normalize("restaurant", "location");
        assertThat(result.canonical()).isNotNull();
        assertThat(result.variants().size()).isGreaterThan(1);
    }

    @Test
    void unknownDomainPassesThrough() {
        var result = normalizer.normalize("test", "unknown_domain");
        assertThat(result.canonical()).isEqualTo("test");
        assertThat(result.variants()).containsExactly("test");
    }


    @Test
    void threadSafety() throws Exception {
        var futures = new ArrayList<Future<?>>();
        var executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                var r = normalizer.normalize("restaurant", "location");
                assertThat(r.canonical()).isNotNull();
            }));
        }
        for (var f : futures) f.get();
        executor.shutdown();
    }
}
