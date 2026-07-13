package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DefaultExplanationRendererTest {

    private final DefaultExplanationRenderer renderer = new DefaultExplanationRenderer();

    @Test void rendersTopMatchWithBreakdown() {
        var trace = new CbrRetrievalTrace("t1",
                CbrQuery.of("tenant", new MemoryDomain("cbr"), "adverse-event", Map.of(), 5)
                        .withRetrievalMode(RetrievalMode.HYBRID),
                List.of(new CbrRetrievalTrace.TracedCase("ae-001", 0.92, false,
                        Map.of("grade", 1.0, "eventType", 0.95), 0.85)),
                Instant.now());
        String result = renderer.render(trace);
        assertThat(result).contains("Retrieved 1 case");
        assertThat(result).contains("caseId=ae-001");
        assertThat(result).contains("score=0.92");
        assertThat(result).contains("confidence=0.85");
        assertThat(result).contains("grade=1.00");
    }

    @Test void emptyResults() {
        var trace = new CbrRetrievalTrace("t1",
                CbrQuery.of("tenant", new MemoryDomain("cbr"), "default", Map.of(), 5),
                List.of(), Instant.now());
        String result = renderer.render(trace);
        assertThat(result).contains("Retrieved 0 cases");
    }

    @Test void nullConfidence() {
        var trace = new CbrRetrievalTrace("t1",
                CbrQuery.of("tenant", new MemoryDomain("cbr"), "default", Map.of(), 5),
                List.of(new CbrRetrievalTrace.TracedCase("c1", 0.75, false, Map.of(), null)),
                Instant.now());
        String result = renderer.render(trace);
        assertThat(result).doesNotContain("null");
    }

    @Test void nullCaseId() {
        var trace = new CbrRetrievalTrace("t1",
                CbrQuery.of("tenant", new MemoryDomain("cbr"), "default", Map.of(), 5),
                List.of(new CbrRetrievalTrace.TracedCase(null, 0.75, false, Map.of(), 0.9)),
                Instant.now());
        String result = renderer.render(trace);
        assertThat(result).doesNotContain("null");
    }

    @Test void multipleResults() {
        var trace = new CbrRetrievalTrace("t1",
                CbrQuery.of("tenant", new MemoryDomain("cbr"), "default", Map.of(), 5),
                List.of(
                    new CbrRetrievalTrace.TracedCase("c1", 0.92, false, Map.of(), 0.9),
                    new CbrRetrievalTrace.TracedCase("c2", 0.75, false, Map.of(), 0.8)),
                Instant.now());
        String result = renderer.render(trace);
        assertThat(result).contains("Retrieved 2 cases");
        assertThat(result).contains("caseId=c1");
    }
}
