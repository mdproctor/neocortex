package io.casehub.neocortex.knowledge.normalization;

import io.casehub.neocortex.knowledge.ExpandedTerm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StemmingTermNormalizerTest {

    private static StemmingTermNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new StemmingTermNormalizer();
        normalizer.init();
    }

    @Test
    void stemsRunning() {
        ExpandedTerm result = normalizer.normalize("running", "documents");
        assertThat(result.canonical()).isEqualTo("run");
        assertThat(result.variants()).contains("running", "run");
    }

    @Test
    void stemsStudies() {
        ExpandedTerm result = normalizer.normalize("studies", "projects");
        assertThat(result.canonical()).isEqualTo("study");
        assertThat(result.variants()).contains("studies", "study");
    }

    @Test
    void passesThroughBaseForm() {
        ExpandedTerm result = normalizer.normalize("run", "documents");
        assertThat(result.canonical()).isEqualTo("run");
        assertThat(result.variants()).containsExactly("run");
    }

    @Test
    void passesThroughUnknownWord() {
        ExpandedTerm result = normalizer.normalize("xyzzy", "documents");
        assertThat(result.canonical()).isEqualTo("xyzzy");
        assertThat(result.variants()).containsExactly("xyzzy");
    }

    @Test
    void handlesNullTerm() {
        ExpandedTerm result = normalizer.normalize(null, "documents");
        assertThat(result.canonical()).isEmpty();
    }

    @Test
    void handlesBlankTerm() {
        ExpandedTerm result = normalizer.normalize("  ", "documents");
        assertThat(result.canonical()).isEqualTo("  ");
        assertThat(result.variants()).containsExactly("  ");
    }
}
