package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EnsembleTraceTest {

    private final AdaptedStep step = new AdaptedStep("b", "cap", "w", "SUCCESS", 0,
            Map.of(), AdaptationAction.RETAINED, null);
    private final StepConsensus consensus = new StepConsensus("b", "cap", 1, 1,
            Map.of("w", 1), Map.of("SUCCESS", 1), Map.of(0, 1),
            List.of("c1"), StepAgreement.UNANIMOUS);

    @Test void validTrace() {
        var trace = new EnsembleTrace("t1", "rt1", "typeA",
                List.of("c1"), List.of(consensus), List.of(step),
                3, 0.85, Map.of("f", FeatureValue.string("v")), Instant.now());
        assertThat(trace.traceId()).isEqualTo("t1");
        assertThat(trace.retrievalTraceId()).isEqualTo("rt1");
        assertThat(trace.caseType()).isEqualTo("typeA");
        assertThat(trace.sourceCaseIds()).containsExactly("c1");
        assertThat(trace.stepAnalysis()).hasSize(1);
        assertThat(trace.synthesizedSteps()).hasSize(1);
        assertThat(trace.inputPlanCount()).isEqualTo(3);
        assertThat(trace.ensembleConfidence()).isEqualTo(0.85);
        assertThat(trace.currentFeatures()).containsKey("f");
    }

    @Test void nullTraceIdRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace(null, null, "typeA", List.of(), List.of(),
                        List.of(), 0, 0.0, Map.of(), Instant.now()));
    }

    @Test void nullCaseTypeRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, null, List.of(), List.of(),
                        List.of(), 0, 0.0, Map.of(), Instant.now()));
    }

    @Test void nullSourceCaseIdsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, "typeA", null, List.of(),
                        List.of(), 0, 0.0, Map.of(), Instant.now()));
    }

    @Test void nullStepAnalysisRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, "typeA", List.of(), null,
                        List.of(), 0, 0.0, Map.of(), Instant.now()));
    }

    @Test void nullSynthesizedStepsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                        null, 0, 0.0, Map.of(), Instant.now()));
    }

    @Test void nullFeaturesRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                        List.of(), 0, 0.0, null, Instant.now()));
    }

    @Test void nullTimestampRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                        List.of(), 0, 0.0, Map.of(), null));
    }

    @Test void nullRetrievalTraceIdAllowed() {
        var trace = new EnsembleTrace("t1", null, "typeA",
                List.of(), List.of(), List.of(),
                0, 0.0, Map.of(), Instant.now());
        assertThat(trace.retrievalTraceId()).isNull();
    }

    @Test void sourceCaseIdsDefensivelyCopied() {
        var list = new ArrayList<>(List.of("c1"));
        var trace = new EnsembleTrace("t1", null, "typeA", list, List.of(),
                List.of(), 0, 0.0, Map.of(), Instant.now());
        list.add("c2");
        assertThat(trace.sourceCaseIds()).hasSize(1);
    }

    @Test void stepAnalysisDefensivelyCopied() {
        var list = new ArrayList<>(List.of(consensus));
        var trace = new EnsembleTrace("t1", null, "typeA", List.of(), list,
                List.of(), 0, 0.0, Map.of(), Instant.now());
        list.clear();
        assertThat(trace.stepAnalysis()).hasSize(1);
    }

    @Test void synthesizedStepsDefensivelyCopied() {
        var list = new ArrayList<>(List.of(step));
        var trace = new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                list, 0, 0.0, Map.of(), Instant.now());
        list.clear();
        assertThat(trace.synthesizedSteps()).hasSize(1);
    }

    @Test void featuresDefensivelyCopied() {
        var map = new HashMap<String, FeatureValue>();
        map.put("f", FeatureValue.string("v"));
        var trace = new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                List.of(), 0, 0.0, map, Instant.now());
        map.put("f2", FeatureValue.number(1.0));
        assertThat(trace.currentFeatures()).doesNotContainKey("f2");
    }

    @Test void cbrEnsembleRecordedRejectsNullTrace() {
        assertThatNullPointerException().isThrownBy(() ->
                new CbrEnsembleRecorded(null));
    }

    @Test void cbrEnsembleRecordedValid() {
        var trace = new EnsembleTrace("t1", null, "typeA", List.of(), List.of(),
                List.of(), 0, 0.0, Map.of(), Instant.now());
        var event = new CbrEnsembleRecorded(trace);
        assertThat(event.trace()).isSameAs(trace);
    }
}
