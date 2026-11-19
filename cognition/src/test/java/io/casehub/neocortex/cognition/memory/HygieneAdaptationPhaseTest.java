package io.casehub.neocortex.cognition.memory;

import io.casehub.neocortex.memory.KnowledgeGapSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HygieneAdaptationPhaseTest {

    private AdaptiveRetentionConfig adaptiveConfig;
    private StubHygieneOrchestrator orchestrator;
    private List<HygieneAdaptationEvent> firedEvents;
    private HygieneAdaptationPhase phase;

    @BeforeEach
    void setUp() {
        adaptiveConfig = new AdaptiveRetentionConfig();
        orchestrator = new StubHygieneOrchestrator();
        firedEvents = new ArrayList<>();
        phase = new HygieneAdaptationPhase(
                orchestrator, adaptiveConfig,
                new TestConfig(),
                firedEvents::add);
    }

    @Test
    void firstTickStoresBaselineWithoutAdapting() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(30, 2, 100));
        phase.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1")).isEqualTo(RetentionConfig.DEFAULT);
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void highEvictionPressureLowersThreshold() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isLessThan(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.getFirst().direction())
                .isEqualTo(HygieneAdaptationEvent.AdaptationDirection.LOWERED);
    }

    @Test
    void lowEvictionPressureRaisesThreshold() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(2, 1, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(2, 1, 100));
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isGreaterThan(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.getFirst().direction())
                .isEqualTo(HygieneAdaptationEvent.AdaptationDirection.RAISED);
    }

    @Test
    void pressureInTargetRangeHolds() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(15, 1, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(15, 1, 100));
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1")).isEqualTo(RetentionConfig.DEFAULT);
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void thresholdClampsAtMinimum() {
        adaptiveConfig.update(new RetentionConfig(0.02, 1.0, 1.0, 0.5, 0.5));
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(50, 2, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(50, 2, 100));
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isGreaterThanOrEqualTo(0.01);
    }

    @Test
    void improvingTrendHoldsRatherThanOscillating() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());
        double afterFirstAdaptation = adaptiveConfig.current("t1").retentionThreshold();

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(20, 2, 100));
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isEqualTo(afterFirstAdaptation);
    }

    @Test
    void zeroTotalScoredIsHandledSafely() {
        orchestrator.setTenantSummary("t1", KnowledgeGapSummary.empty());
        phase.run("t1", List.of());
        phase.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1")).isEqualTo(RetentionConfig.DEFAULT);
    }

    @Test
    void nameReturnsHygieneAdaptation() {
        assertThat(phase.name()).isEqualTo("hygiene-adaptation");
    }

    @Test
    void eventContainsPreviousAndNewConfig() {
        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());

        orchestrator.setTenantSummary("t1",
                new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());

        assertThat(firedEvents).hasSize(1);
        var event = firedEvents.getFirst();
        assertThat(event.previousConfig()).isEqualTo(RetentionConfig.DEFAULT);
        assertThat(event.newConfig()).isEqualTo(adaptiveConfig.current("t1"));
        assertThat(event.tenantId()).isEqualTo("t1");
        assertThat(event.evictionPressure()).isEqualTo(0.4);
    }

    @Test
    void differentTenantsGetIndependentThresholds() {
        orchestrator.setTenantSummary("t1",
                                      new KnowledgeGapSummary(40, 2, 100));
        orchestrator.setTenantSummary("t2",
                                      new KnowledgeGapSummary(2, 1, 100));

        phase.run("t1", List.of());
        phase.run("t2", List.of());

        phase.run("t1", List.of());
        phase.run("t2", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isLessThan(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(adaptiveConfig.current("t2").retentionThreshold())
                .isGreaterThan(RetentionConfig.DEFAULT.retentionThreshold());
    }

    @Test
    void tenantAdaptationDoesNotAffectGlobalConfig() {
        orchestrator.setTenantSummary("t1",
                                      new KnowledgeGapSummary(40, 2, 100));
        phase.run("t1", List.of());
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isLessThan(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(adaptiveConfig.current()).isEqualTo(RetentionConfig.DEFAULT);
    }

    @Test
    void unknownTenantFallsBackToGlobalDefault() {
        assertThat(adaptiveConfig.current("never-seen"))
                .isEqualTo(RetentionConfig.DEFAULT);
    }


    // --- Test doubles ---

    static class StubHygieneOrchestrator implements MemoryHygieneOrchestrator {
        private final Map<String, KnowledgeGapSummary> tenantSummaries = new HashMap<>();

        void setTenantSummary(String tenantId, KnowledgeGapSummary summary) {
            tenantSummaries.put(tenantId, summary);
        }

        @Override
        public void tick(String agentId, String tenantId) {}

        @Override
        public KnowledgeGapSummary knowledgeGaps(String agentId, String tenantId) {
            return KnowledgeGapSummary.empty();
        }

        @Override
        public KnowledgeGapSummary tenantGapSummary(String tenantId) {
            return tenantSummaries.getOrDefault(tenantId, KnowledgeGapSummary.empty());
        }
    }

    static class TestConfig implements HygieneAdaptationConfig {
        @Override public boolean enabled() { return true; }
        @Override public double evictionPressureUpperThreshold() { return 0.3; }
        @Override public double evictionPressureLowerThreshold() { return 0.05; }
        @Override public double stepSize() { return 0.02; }
        @Override public double minRetentionThreshold() { return 0.01; }
        @Override public double maxRetentionThreshold() { return 0.5; }
    }
}
