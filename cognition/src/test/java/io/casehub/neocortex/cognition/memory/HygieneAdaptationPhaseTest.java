package io.casehub.neocortex.cognition.memory;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.KnowledgeGapSummary;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordSchema;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;

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

    @Test
    void beginTickEvictsTenantsNotSeenInPreviousCycle() {
        orchestrator.setTenantSummary("t1",
                                      new KnowledgeGapSummary(40, 2, 100));
        orchestrator.setTenantSummary("t2",
                                      new KnowledgeGapSummary(2, 1, 100));
        phase.run("t1", List.of());
        phase.run("t2", List.of());
        phase.run("t1", List.of());
        phase.run("t2", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isNotEqualTo(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(adaptiveConfig.current("t2").retentionThreshold())
                .isNotEqualTo(RetentionConfig.DEFAULT.retentionThreshold());

        phase.beginTick();
        phase.run("t1", List.of());

        phase.beginTick();
        phase.run("t1", List.of());

        assertThat(adaptiveConfig.current("t1").retentionThreshold())
                .isNotEqualTo(RetentionConfig.DEFAULT.retentionThreshold());
        assertThat(adaptiveConfig.current("t2"))
                .isEqualTo(RetentionConfig.DEFAULT);
    }


    // --- CBR integration tests ---

    @Test
    void registersSchemaWhenCbrStoreAvailable() {
        var cbrStore = new StubCbrRecordStore();
        new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        assertThat(cbrStore.registeredSchemas).hasSize(1);
        assertThat(cbrStore.registeredSchemas.getFirst().caseType()).isEqualTo("retention-config");
        assertThat(cbrStore.registeredSchemas.getFirst().fields()).hasSize(3);
    }

    @Test
    void storesCbrCaseOnStabilityThreshold() {
        var cbrStore = new StubCbrRecordStore();
        var phaseWithCbr = new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t1", List.of());
        for (int i = 0; i < 3; i++) {
            orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
            phaseWithCbr.run("t1", List.of());
        }
        assertThat(cbrStore.storedCases).hasSize(1);
        var stored = cbrStore.storedCases.getFirst();
        assertThat(stored.caseType()).isEqualTo("retention-config");
        assertThat(stored.tenantId()).isEqualTo("t1");
        assertThat(stored.record().features()).containsKeys("evictionPressure", "totalScored", "consolidationGroups");
    }

    @Test
    void supersedesPreviousCaseOnNewStableConfig() {
        var cbrStore = new StubCbrRecordStore();
        var phaseWithCbr = new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t1", List.of());
        for (int i = 0; i < 3; i++) {
            orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
            phaseWithCbr.run("t1", List.of());
        }
        assertThat(cbrStore.storedCases).hasSize(1);
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(40, 2, 100));
        phaseWithCbr.run("t1", List.of());
        for (int i = 0; i < 3; i++) {
            orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
            phaseWithCbr.run("t1", List.of());
        }
        assertThat(cbrStore.storedCases).hasSize(2);
        assertThat(cbrStore.supersededCaseIds).hasSize(1);
        assertThat(cbrStore.supersededCaseIds.getFirst()).isEqualTo("case-0");
    }

    @Test
    void retrievesPriorConfigOnFirstTick() {
        var cbrStore = new StubCbrRecordStore();
        var phaseWithCbr = new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        var priorRecord = new CbrFeatureRecord(
                "retention threshold 0.07", "retentionThreshold=0.07", null,
                new Confidence(ConfidenceOrigin.INFERRED, 1.0, null),
                Map.of("evictionPressure", FeatureValue.number(0.15),
                       "totalScored", FeatureValue.number(200),
                       "consolidationGroups", FeatureValue.number(5)),
                null, null);
        cbrStore.setRetrievalResult("t1", List.of(new CbrMatch<>(priorRecord, "prior-case-1", "retention-config", 0.8)));
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1").retentionThreshold()).isEqualTo(0.07);
    }

    @Test
    void noMatchKeepsDefault() {
        var cbrStore = new StubCbrRecordStore();
        var phaseWithCbr = new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1")).isEqualTo(RetentionConfig.DEFAULT);
    }

    @Test
    void noCbrStoreSkipsRetrievalGracefully() {
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phase.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1")).isEqualTo(RetentionConfig.DEFAULT);
    }

    @Test
    void retrievalIsIsolatedPerTenant() {
        var cbrStore = new StubCbrRecordStore();
        var phaseWithCbr = new HygieneAdaptationPhase(orchestrator, adaptiveConfig, new TestConfig(), firedEvents::add, cbrStore);
        var priorRecord = new CbrFeatureRecord(
                "retention threshold 0.05", "retentionThreshold=0.05", null,
                new Confidence(ConfidenceOrigin.INFERRED, 1.0, null),
                Map.of("evictionPressure", FeatureValue.number(0.15),
                       "totalScored", FeatureValue.number(200),
                       "consolidationGroups", FeatureValue.number(5)),
                null, null);
        cbrStore.setRetrievalResult("t1", List.of(new CbrMatch<>(priorRecord, "case-t1", "retention-config", 0.8)));
        orchestrator.setTenantSummary("t1", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t1", List.of());
        assertThat(adaptiveConfig.current("t1").retentionThreshold()).isEqualTo(0.05);
        orchestrator.setTenantSummary("t2", new KnowledgeGapSummary(15, 1, 100));
        phaseWithCbr.run("t2", List.of());
        assertThat(adaptiveConfig.current("t2")).isEqualTo(RetentionConfig.DEFAULT);
    }

    // --- Test doubles ---

    static class StubCbrRecordStore implements CbrRecordStore {
        final List<CbrRecordSchema> registeredSchemas = new ArrayList<>();
        final List<StoredCase> storedCases = new ArrayList<>();
        final List<String> supersededCaseIds = new ArrayList<>();
        private final Map<String, List<CbrMatch<CbrRecord>>> retrievalResults = new HashMap<>();
        record StoredCase(CbrRecord record, String caseType, String entityId, MemoryDomain domain, String tenantId, String caseId) {}
        @Override public void registerSchema(CbrRecordSchema schema) { registeredSchemas.add(schema); }
        @Override public String store(CbrRecord cbrRecord, String caseType, String entityId, MemoryDomain domain, String tenantId, String caseId, Path scope) {
            String id = "case-" + storedCases.size();
            storedCases.add(new StoredCase(cbrRecord, caseType, entityId, domain, tenantId, caseId));
            return id;
        }
        @Override @SuppressWarnings("unchecked")
        public <C extends CbrRecord> List<CbrMatch<C>> retrieveSimilar(CbrQuery query, Class<C> caseType) {
            return (List<CbrMatch<C>>) (List<?>) retrievalResults.getOrDefault(query.tenantId(), List.of());
        }
        void setRetrievalResult(String tenantId, List<CbrMatch<CbrRecord>> results) { retrievalResults.put(tenantId, results); }
        @Override public List<String> findCaseIds(MemoryDomain domain, String caseType, Map<String, CbrFilter> filters, String tenantId) { return List.of(); }
        @Override public int erase(EraseRequest request) { return 0; }
        @Override public int eraseEntity(String entityId, String tenantId) { return 0; }
        @Override public int eraseByScope(Path scope, String tenantId) { return 0; }
        @Override public boolean supersede(String caseId, String supersedingCaseId, String reason, String tenantId) { supersededCaseIds.add(caseId); return true; }
        @Override public boolean reinstate(String caseId, String tenantId) { return false; }
        @Override public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return SupersessionStatus.NOT_SUPERSEDED; }
        @Override public List<SupersessionStatus> findSupersededCases(MemoryDomain domain, String tenantId) { return List.of(); }
        @Override public void recordOutcome(String caseId, CbrOutcome outcome, String tenantId) {}
        @Override public int supersedeMatching(MemoryDomain domain, String caseType, Map<String, CbrFilter> filters, String reason, String tenantId) { return 0; }
        @Override public int supersedeAll(Collection<String> caseIds, String reason, String tenantId) { return 0; }
        @Override public int reinstateMatching(MemoryDomain domain, String caseType, Map<String, CbrFilter> filters, String tenantId) { return 0; }
        @Override public int reinstateAll(Collection<String> caseIds, String tenantId) { return 0; }
        @Override public int purge(CbrRetentionPolicy policy) { return 0; }
    }

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
        @Override public int stabilityThreshold() { return 3; }
        @Override public double cbrMinSimilarity() { return 0.3; }
    }
}
