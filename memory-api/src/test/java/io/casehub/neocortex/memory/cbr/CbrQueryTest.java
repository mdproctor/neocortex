package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrQueryTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    @Test
    void of_createsValidQuery() {
        var q = CbrQuery.of("tenant1", CBR, "starcraft-game",
            Map.of("race", "Zerg"), 5);
        assertThat(q.tenantId()).isEqualTo("tenant1");
        assertThat(q.domain()).isEqualTo(CBR);
        assertThat(q.caseType()).isEqualTo("starcraft-game");
        assertThat(q.topK()).isEqualTo(5);
        assertThat(q.minSimilarity()).isEqualTo(0.0);
        assertThat(q.notBefore()).isNull();
    }

    @Test
    void nullTenantIdRejected() {
        assertThatThrownBy(() -> CbrQuery.of(null, CBR, "type", Map.of(), 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullDomainRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", null, "type", Map.of(), 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void topKLessThanOneRejected() {
        assertThatThrownBy(() -> CbrQuery.of("t", CBR, "type", Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minSimilarityOutOfRangeRejected() {
        assertThatThrownBy(() -> new CbrQuery("t", CBR, "type", Map.of(), 5, 1.5, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void featuresDefensivelyCopied() {
        var features = new java.util.HashMap<String, Object>();
        features.put("race", "Zerg");
        var q = CbrQuery.of("t", CBR, "type", features, 5);
        features.put("extra", "value");
        assertThat(q.features()).doesNotContainKey("extra");
    }
}
