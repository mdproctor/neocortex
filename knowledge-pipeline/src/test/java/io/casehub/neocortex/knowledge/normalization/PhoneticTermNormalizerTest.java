package io.casehub.neocortex.knowledge.normalization;

import io.casehub.neocortex.knowledge.ExpandedTerm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneticTermNormalizerTest {

    private final PhoneticTermNormalizer normalizer = new PhoneticTermNormalizer();

    @Test
    void preservesOriginalAsCanonical() {
        ExpandedTerm result = normalizer.normalize("Smith", "contacts");
        assertThat(result.canonical()).isEqualTo("smith");
    }

    @Test
    void addsPhoneticCodeAsVariant() {
        ExpandedTerm result = normalizer.normalize("Smith", "contacts");
        assertThat(result.variants()).hasSize(2);
        assertThat(result.variants()).contains("smith");
        assertThat(result.variants().stream().filter(v -> v.startsWith("phonetic:")).count()).isEqualTo(1);
    }

    @Test
    void samePhoneticCodeForSimilarNames() {
        ExpandedTerm smith = normalizer.normalize("Smith", "contacts");
        ExpandedTerm smyth = normalizer.normalize("Smyth", "contacts");
        String smithPhonetic = smith.variants().stream()
            .filter(v -> v.startsWith("phonetic:")).findFirst().orElse("");
        String smythPhonetic = smyth.variants().stream()
            .filter(v -> v.startsWith("phonetic:")).findFirst().orElse("");
        assertThat(smithPhonetic).isEqualTo(smythPhonetic);
    }

    @Test
    void handlesNullTerm() {
        ExpandedTerm result = normalizer.normalize(null, "contacts");
        assertThat(result.canonical()).isEmpty();
    }

    @Test
    void handlesBlankTerm() {
        ExpandedTerm result = normalizer.normalize("  ", "contacts");
        assertThat(result.canonical()).isEqualTo("  ");
        assertThat(result.variants()).containsExactly("  ");
    }
}
