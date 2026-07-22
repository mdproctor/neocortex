package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class StepConsensusTest {

    @Test void validConsensus() {
        var sc = new StepConsensus("bind", "cap", 3, 5,
                Map.of("W1", 2, "W2", 1), Map.of("COMPLETED", 3),
                Map.of(1, 2, 5, 1), List.of("c1", "c2", "c3"),
                StepAgreement.CONSENSUS);
        assertThat(sc.bindingName()).isEqualTo("bind");
        assertThat(sc.capabilityName()).isEqualTo("cap");
        assertThat(sc.occurrenceCount()).isEqualTo(3);
        assertThat(sc.totalPlans()).isEqualTo(5);
        assertThat(sc.workerDistribution()).containsEntry("W1", 2);
        assertThat(sc.outcomeDistribution()).containsEntry("COMPLETED", 3);
        assertThat(sc.priorityDistribution()).containsEntry(1, 2);
        assertThat(sc.contributingCaseIds()).containsExactly("c1", "c2", "c3");
        assertThat(sc.agreement()).isEqualTo(StepAgreement.CONSENSUS);
    }

    @Test void nullBindingNameRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new StepConsensus(null, "cap", 1, 1, null, null, null, null,
                        StepAgreement.UNANIMOUS));
    }

    @Test void nullCapabilityNameAllowed() {
        var sc = new StepConsensus("bind", null, 1, 1, null, null, null, null,
                StepAgreement.UNANIMOUS);
        assertThat(sc.capabilityName()).isNull();
    }

    @Test void occurrenceCountZeroRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new StepConsensus("bind", "cap", 0, 1, null, null, null, null,
                        StepAgreement.UNIQUE));
    }

    @Test void occurrenceCountNegativeRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new StepConsensus("bind", "cap", -1, 1, null, null, null, null,
                        StepAgreement.UNIQUE));
    }

    @Test void totalPlansZeroRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new StepConsensus("bind", "cap", 1, 0, null, null, null, null,
                        StepAgreement.UNIQUE));
    }

    @Test void nullAgreementRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new StepConsensus("bind", "cap", 1, 1, null, null, null, null, null));
    }

    @Test void nullDistributionsDefaultToEmpty() {
        var sc = new StepConsensus("bind", "cap", 1, 1, null, null, null, null,
                StepAgreement.UNANIMOUS);
        assertThat(sc.workerDistribution()).isEmpty();
        assertThat(sc.outcomeDistribution()).isEmpty();
        assertThat(sc.priorityDistribution()).isEmpty();
        assertThat(sc.contributingCaseIds()).isEmpty();
    }

    @Test void workerDistributionDefensivelyCopied() {
        var map = new HashMap<String, Integer>();
        map.put("W1", 1);
        var sc = new StepConsensus("bind", "cap", 1, 1, map, null, null, null,
                StepAgreement.UNANIMOUS);
        map.put("W2", 2);
        assertThat(sc.workerDistribution()).doesNotContainKey("W2");
    }

    @Test void outcomeDistributionDefensivelyCopied() {
        var map = new HashMap<String, Integer>();
        map.put("COMPLETED", 1);
        var sc = new StepConsensus("bind", "cap", 1, 1, null, map, null, null,
                StepAgreement.UNANIMOUS);
        map.put("FAULTED", 1);
        assertThat(sc.outcomeDistribution()).doesNotContainKey("FAULTED");
    }

    @Test void priorityDistributionDefensivelyCopied() {
        var map = new HashMap<Integer, Integer>();
        map.put(1, 1);
        var sc = new StepConsensus("bind", "cap", 1, 1, null, null, map, null,
                StepAgreement.UNANIMOUS);
        map.put(5, 1);
        assertThat(sc.priorityDistribution()).doesNotContainKey(5);
    }

    @Test void contributingCaseIdsDefensivelyCopied() {
        var list = new ArrayList<>(List.of("c1"));
        var sc = new StepConsensus("bind", "cap", 1, 1, null, null, null, list,
                StepAgreement.UNANIMOUS);
        list.add("c2");
        assertThat(sc.contributingCaseIds()).hasSize(1);
    }

    @Test void stepAgreementCoverage() {
        assertThat(StepAgreement.values()).containsExactly(
                StepAgreement.UNANIMOUS, StepAgreement.CONSENSUS,
                StepAgreement.CONTESTED, StepAgreement.MINORITY,
                StepAgreement.UNIQUE);
    }
}
