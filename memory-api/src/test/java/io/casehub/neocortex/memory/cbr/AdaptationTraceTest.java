package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AdaptationTraceTest {

    private final AdaptedStep step = new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
            Map.of(), AdaptationAction.RETAINED, null);

    @Test
    void validTrace() {
        var trace = new AdaptationTrace("t1", "rt1", "typeA", "c1", 0.85,
                                        List.of(step), Map.of("f", FeatureValue.string("v")), Instant.now());
        assertThat(trace.traceId()).isEqualTo("t1");
        assertThat(trace.retrievalTraceId()).isEqualTo("rt1");
        assertThat(trace.caseType()).isEqualTo("typeA");
        assertThat(trace.sourceCaseId()).isEqualTo("c1");
        assertThat(trace.sourceScore()).isEqualTo(0.85);
        assertThat(trace.steps()).hasSize(1);
        assertThat(trace.currentFeatures()).containsKey("f");
    }

    @Test
    void nullTraceIdRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new AdaptationTrace(null, null, "typeA", "c1", 0.85,
                                                                                List.of(step), Map.of(), Instant.now()));
    }

    @Test
    void nullCaseTypeRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new AdaptationTrace("t1", null, null, "c1", 0.85,
                                                                                List.of(step), Map.of(), Instant.now()));
    }


    @Test
    void nullStepsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                                                                null, Map.of(), Instant.now()));
    }

    @Test
    void nullFeaturesRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                                                                List.of(step), null, Instant.now()));
    }

    @Test
    void nullTimestampRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                                                                List.of(step), Map.of(), null));
    }

    @Test
    void nullSourceCaseIdAllowed() {
        var trace = new AdaptationTrace("t1", null, "typeA", null, 0.85,
                                        List.of(step), Map.of(), Instant.now());
        assertThat(trace.sourceCaseId()).isNull();
    }

    @Test
    void nullRetrievalTraceIdAllowed() {
        var trace = new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                        List.of(step), Map.of(), Instant.now());
        assertThat(trace.retrievalTraceId()).isNull();
    }

    @Test
    void stepsDefensivelyCopied() {
        var list = new ArrayList<>(List.of(step));
        var trace = new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                        list, Map.of(), Instant.now());
        list.clear();
        assertThat(trace.steps()).hasSize(1);
    }

    @Test
    void featuresDefensivelyCopied() {
        var map = new HashMap<String, FeatureValue>();
        map.put("f", FeatureValue.string("v"));
        var trace = new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                        List.of(step), map, Instant.now());
        map.put("f2", FeatureValue.number(1.0));
        assertThat(trace.currentFeatures()).doesNotContainKey("f2");
    }

    @Test void cbrAdaptationRecordedRejectsNullTrace() {
        assertThatNullPointerException().isThrownBy(() ->
                new CbrAdaptationRecorded(null));
    }

    @Test
    void cbrAdaptationRecordedValid() {
        var trace = new AdaptationTrace("t1", null, "typeA", "c1", 0.85,
                                        List.of(step), Map.of(), Instant.now());
        var event = new CbrAdaptationRecorded(trace);
        assertThat(event.trace()).isSameAs(trace);
    }
}
