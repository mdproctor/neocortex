package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CbrRetentionPolicyTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    @Test void validPolicy_ageBased() {
        var policy = new CbrRetentionPolicy("t1", CBR, null, 30, null);
        assertThat(policy.maxAgeDays()).isEqualTo(30);
        assertThat(policy.maxCasesPerType()).isNull();
    }

    @Test void validPolicy_countBased() {
        var policy = new CbrRetentionPolicy("t1", CBR, null, null, 100);
        assertThat(policy.maxAgeDays()).isNull();
        assertThat(policy.maxCasesPerType()).isEqualTo(100);
    }

    @Test void validPolicy_combined() {
        var policy = new CbrRetentionPolicy("t1", CBR, "diagnosis", 30, 100);
        assertThat(policy.caseType()).isEqualTo("diagnosis");
    }

    @Test void rejectsNullTenantId() {
        assertThatThrownBy(() -> new CbrRetentionPolicy(null, CBR, null, 30, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsNullDomain() {
        assertThatThrownBy(() -> new CbrRetentionPolicy("t1", null, null, 30, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsBothNull() {
        assertThatThrownBy(() -> new CbrRetentionPolicy("t1", CBR, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one");
    }

    @Test void rejectsNonPositiveMaxAgeDays() {
        assertThatThrownBy(() -> new CbrRetentionPolicy("t1", CBR, null, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test void rejectsNegativeMaxCasesPerType() {
        assertThatThrownBy(() -> new CbrRetentionPolicy("t1", CBR, null, null, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }
}
