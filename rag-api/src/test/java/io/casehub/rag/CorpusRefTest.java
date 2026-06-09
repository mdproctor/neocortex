package io.casehub.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusRefTest {

    @Test
    void validConstruction() {
        var ref = new CorpusRef("tenant-123", "legal-docs");
        assertThat(ref.tenantId()).isEqualTo("tenant-123");
        assertThat(ref.corpusName()).isEqualTo("legal-docs");
    }

    @Test
    void nullTenantIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusRef(null, "corpus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId must not be null or blank");
    }

    @Test
    void blankTenantIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusRef("", "corpus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId must not be null or blank");

        assertThatThrownBy(() -> new CorpusRef("  ", "corpus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId must not be null or blank");
    }

    @Test
    void nullCorpusNameThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusRef("tenant", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corpusName must not be null or blank");
    }

    @Test
    void blankCorpusNameThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CorpusRef("tenant", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corpusName must not be null or blank");

        assertThatThrownBy(() -> new CorpusRef("tenant", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corpusName must not be null or blank");
    }

    @Test
    void valueBasedEquality() {
        var ref1 = new CorpusRef("tenant-1", "corpus-a");
        var ref2 = new CorpusRef("tenant-1", "corpus-a");
        var ref3 = new CorpusRef("tenant-2", "corpus-a");

        assertThat(ref1).isEqualTo(ref2);
        assertThat(ref1).hasSameHashCodeAs(ref2);
        assertThat(ref1).isNotEqualTo(ref3);
    }
}
