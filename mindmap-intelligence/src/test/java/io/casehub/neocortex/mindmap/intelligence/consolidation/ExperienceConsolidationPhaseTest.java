package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceAttributeKeys;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.experience.GraduationScorer;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceConsolidationPhaseTest {

    private static final String TENANT = "test-tenant";

    private InMemoryMemoryStore memoryStore;
    private InMemoryMindMapStore mindMapStore;
    private ExperienceConsolidationPhase phase;
    private DefaultGraduationScorer scorer;
    private DefaultGraduationClassifier classifier;

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "actor"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return TENANT; }
        @Override public boolean isCrossTenantAdmin() { return true; }
    };

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryMemoryStore(principal);
        mindMapStore = new InMemoryMindMapStore();
        scorer = new DefaultGraduationScorer(1);
        classifier = new DefaultGraduationClassifier();
        phase = new ExperienceConsolidationPhase(
            memoryStore, mindMapStore, scorer, classifier, 0.5, 20, 1);
    }

    private String storeExperience(String agentId, String eventType,
                                    String description, Map<String, String> extraAttrs,
                                    Double confidence) {
        var attrs = new HashMap<>(extraAttrs);
        attrs.put(ExperienceAttributeKeys.EVENT_TYPE, eventType);
        return memoryStore.store(new MemoryInput(
            Subject.of("agent", agentId),
            ExperienceEvents.DOMAIN,
            TENANT, null, description, attrs,
            confidence != null ? Confidence.unknown(confidence) : null,
            null, null, null, null, null));
    }

    private String storeFormativeMemory(String agentId, String description,
                                        Double confidence, double salience,
                                        Double pleasure, Double arousal, Double dominance) {
        var attrs = new HashMap<String, String>();
        attrs.put(ExperienceAttributeKeys.EVENT_TYPE, "formative");
        attrs.put(io.casehub.neocortex.memory.experience.FormativeAttributeKeys.SALIENCE_MULTIPLIER, String.valueOf(salience));
        attrs.put(io.casehub.neocortex.memory.experience.FormativeAttributeKeys.CATALOGUE_ENTRY_ID, "test-entry");
        attrs.put(io.casehub.neocortex.memory.experience.FormativeAttributeKeys.SITUATION_TYPES, "formation");
        attrs.put(io.casehub.neocortex.memory.experience.FormativeAttributeKeys.DEVELOPMENTAL_PERIOD, "childhood");
        return memoryStore.store(new MemoryInput(
                Subject.of("agent", agentId),
                ExperienceEvents.DOMAIN,
                TENANT, null, description, attrs,
                confidence != null ? Confidence.unknown(confidence) : null,
                pleasure, arousal, dominance, null, null));
    }


    private List<MindMapNode> cognitiveNodes() {
        return mindMapStore.search(
            MindMapQuery.of(TENANT, 1000).withType(SubgraphTypes.COGNITIVE));
    }

    @Test
    void graduatedEvent_producesCognitiveNode() {
        storeExperience("a1", "observation", "Bob looks worried",
            Map.of("subject", "Bob"), 0.8);

        phase.run(TENANT, List.of());

        var nodes = cognitiveNodes();
        assertEquals(1, nodes.size());
        MindMapNode node = nodes.get(0);
        assertEquals("belief", node.property("cognitiveKind").orElse(""));
        assertTrue(node.property("source-memory-id").isPresent());
        assertEquals("experience-consolidation", node.provenance());
    }

    @Test
    void scoreBelowThreshold_notGraduated() {
        storeExperience("a1", "observation", "trivial event",
            Map.of("subject", "X"), 0.1);

        phase.run(TENANT, List.of());

        assertEquals(0, cognitiveNodes().size());
    }

    @Test
    void maxPerPass_capsProcessing() {
        for (int i = 0; i < 5; i++) {
            storeExperience("a1", "observation", "event " + i,
                Map.of("subject", "S"), 0.8);
        }

        var cappedPhase = new ExperienceConsolidationPhase(
            memoryStore, mindMapStore, scorer, classifier, 0.5, 3);
        cappedPhase.run(TENANT, List.of());

        assertEquals(3, cognitiveNodes().size());

        cappedPhase.run(TENANT, List.of());
        assertEquals(5, cognitiveNodes().size());
    }

    @Test
    void cursorPersistence_secondRunSkipsProcessed() {
        storeExperience("a1", "observation", "first event",
            Map.of("subject", "A"), 0.8);
        phase.run(TENANT, List.of());
        assertEquals(1, cognitiveNodes().size());

        storeExperience("a1", "observation", "second event",
            Map.of("subject", "B"), 0.8);
        phase.run(TENANT, List.of());
        assertEquals(2, cognitiveNodes().size());
    }

    @Test
    void dedupGuard_reprocessedMemoryNotDuplicated() {
        storeExperience("a1", "observation", "same event",
            Map.of("subject", "A"), 0.8);
        phase.run(TENANT, List.of());
        assertEquals(1, cognitiveNodes().size());

        var freshPhase = new ExperienceConsolidationPhase(
            memoryStore, mindMapStore, scorer, classifier, 0.5, 20);
        freshPhase.run(TENANT, List.of());
        assertEquals(1, cognitiveNodes().size());
    }

    @Test
    void errorIsolation_failedMemoryDoesNotBlockOthers() {
        String id1 = storeExperience("a1", "observation", "good event",
            Map.of("subject", "A"), 0.8);
        storeExperience("a1", "observation", "another good",
            Map.of("subject", "B"), 0.8);

        GraduationScorer failOnFirst = (m, ctx) -> {
            if (m.text().equals("good event")) throw new RuntimeException("scorer failed");
            return 0.8;
        };
        var phaseWithFailingScorer = new ExperienceConsolidationPhase(
            memoryStore, mindMapStore, failOnFirst, classifier, 0.5, 20);
        phaseWithFailingScorer.run(TENANT, List.of());

        assertEquals(1, cognitiveNodes().size());
    }

    @Test
    void nodeProperties_allFieldsSet() {
        storeExperience("a1", "observation", "Bob is worried",
            Map.of("subject", "Bob"), 0.8);
        phase.run(TENANT, List.of());

        MindMapNode node = cognitiveNodes().get(0);
        assertTrue(node.property("source-memory-id").isPresent());
        assertEquals("observation", node.property("event-type").orElse(""));
        assertEquals("a1", node.property("agent-id").orElse(""));
        assertEquals("belief", node.property("cognitiveKind").orElse(""));
        assertTrue(node.property("graduation-score").isPresent());
        assertEquals("experience-consolidation", node.provenance());
        assertEquals("Bob", node.property("subject").orElse(""));
        assertEquals("active", node.property("status").orElse(""));
    }

    @Test
    void emptyExperienceSet_noNodesCreated() {
        phase.run(TENANT, List.of());
        assertEquals(0, cognitiveNodes().size());
    }

    @Test
    void multiTenant_independentProcessing() {
        var tenant2Principal = new CurrentPrincipal() {
            @Override public String actorId() { return "actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return "tenant-2"; }
            @Override public boolean isCrossTenantAdmin() { return true; }
        };
        var tenant2Store = new InMemoryMemoryStore(tenant2Principal);

        storeExperience("a1", "observation", "t1 event",
            Map.of("subject", "A"), 0.8);
        tenant2Store.store(new MemoryInput(
            Subject.of("agent", "a1"), ExperienceEvents.DOMAIN,
            "tenant-2", null, "t2 event",
            Map.of("event-type", "observation", "subject", "B"),
            Confidence.unknown(0.8), null, null, null, null, null));

        phase.run(TENANT, List.of());
        assertEquals(1, cognitiveNodes().size());
        assertEquals(0, mindMapStore.search(
            MindMapQuery.of("tenant-2", 100).withType(SubgraphTypes.COGNITIVE)).size());

        var phase2 = new ExperienceConsolidationPhase(
            tenant2Store, mindMapStore, scorer, classifier, 0.5, 20);
        phase2.run("tenant-2", List.of());
        assertEquals(1, mindMapStore.search(
            MindMapQuery.of("tenant-2", 100).withType(SubgraphTypes.COGNITIVE)).size());
    }

    @Test
    void actionEvent_producesIntentionNode() {
        storeExperience("a1", "action", "used persuade",
            Map.of("capability", "persuade"), 0.8);
        phase.run(TENANT, List.of());

        MindMapNode node = cognitiveNodes().get(0);
        assertEquals("intention", node.property("cognitiveKind").orElse(""));
        assertEquals("persuade", node.property("goal").orElse(""));
    }

    @Test
    void outcomeEvent_producesJudgmentNode() {
        storeExperience("a1", "outcome", "it worked",
            Map.of("result", "success"), 0.8);
        phase.run(TENANT, List.of());

        MindMapNode node = cognitiveNodes().get(0);
        assertEquals("judgment", node.property("cognitiveKind").orElse(""));
        assertEquals("success", node.property("target").orElse(""));
    }

    @Test
    void nodeName_truncatedTo100Chars() {
        String longDesc = "A".repeat(150);
        storeExperience("a1", "observation", longDesc,
            Map.of("subject", "X"), 0.8);
        phase.run(TENANT, List.of());

        MindMapNode node = cognitiveNodes().get(0);
        assertTrue(node.name().length() <= 104); // 100 + "..."
        assertTrue(node.name().endsWith("..."));
    }

    @Test
    void singleObservation_notGraduated_whenCorroborationRequired() {
        storeExperience("a1", "observation", "Bob looks worried",
                        Map.of("subject", "Bob"), 0.8);

        var corroboratingPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, new DefaultGraduationScorer(3),
                classifier, 0.5, 20, 3);
        corroboratingPhase.run(TENANT, List.of());

        assertEquals(0, cognitiveNodes().size());
    }

    @Test
    void threeConvergingObservations_graduates() {
        storeExperience("a1", "observation", "Bob looks worried",
                        Map.of("subject", "Bob"), 0.8);
        storeExperience("a1", "observation", "Bob seems stressed",
                        Map.of("subject", "Bob"), 0.7);
        storeExperience("a1", "observation", "Bob is anxious",
                        Map.of("subject", "Bob"), 0.9);

        var corroboratingPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, new DefaultGraduationScorer(3),
                classifier, 0.5, 20, 3);
        corroboratingPhase.run(TENANT, List.of());

        assertEquals(3, cognitiveNodes().size());
    }

    @Test
    void differentSubjects_doNotCorroborate() {
        storeExperience("a1", "observation", "Bob looks worried",
                        Map.of("subject", "Bob"), 0.8);
        storeExperience("a1", "observation", "Alice is happy",
                        Map.of("subject", "Alice"), 0.8);
        storeExperience("a1", "observation", "Charlie is tired",
                        Map.of("subject", "Charlie"), 0.8);

        var corroboratingPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, new DefaultGraduationScorer(3),
                classifier, 0.5, 20, 3);
        corroboratingPhase.run(TENANT, List.of());

        assertEquals(0, cognitiveNodes().size());
    }

    @Test
    void retroactiveCorroboration_graduatesOnSubsequentPass() {
        storeExperience("a1", "observation", "Bob event 1",
                        Map.of("subject", "Bob"), 0.8);
        storeExperience("a1", "observation", "Bob event 2",
                        Map.of("subject", "Bob"), 0.8);

        var corroboratingPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, new DefaultGraduationScorer(3),
                classifier, 0.5, 20, 3);
        corroboratingPhase.run(TENANT, List.of());
        assertEquals(0, cognitiveNodes().size());

        storeExperience("a1", "observation", "Bob event 3",
                        Map.of("subject", "Bob"), 0.8);

        var freshPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, new DefaultGraduationScorer(3),
                classifier, 0.5, 20, 3);
        freshPhase.run(TENANT, List.of());
        assertEquals(3, cognitiveNodes().size());
    }

    @Test
    void formativeMemory_graduatesAsCognitiveNode() {
        storeFormativeMemory("a1", "childhood experience shaped distrust",
                             0.9, 1.5, null, null, null);

        phase.run(TENANT, List.of());

        var nodes = cognitiveNodes();
        assertEquals(1, nodes.size());
        MindMapNode node = nodes.get(0);
        assertTrue(node.property("source-memory-id").isPresent());
        assertEquals("formative", node.property("event-type").orElse(""));
        assertEquals("a1", node.property("agent-id").orElse(""));
        assertEquals("formative-experience", node.property("cognitiveKind").orElse(""));
        assertEquals("experience-consolidation", node.provenance());
    }

    @Test
    void formativeMemory_graduatesWithoutCorroboration() {
        storeFormativeMemory("a1", "early loss experience",
                             0.9, 1.5, null, null, null);

        var strictPhase = new ExperienceConsolidationPhase(
                memoryStore, mindMapStore, scorer, classifier, 0.5, 20, 3);
        strictPhase.run(TENANT, List.of());

        assertEquals(1, cognitiveNodes().size(),
                     "Formative memories have no subject — corroboration is irrelevant");
    }

    @Test
    void formativeMemory_padTransfersToNode() {
        storeFormativeMemory("a1", "trauma with emotional valence",
                             0.9, 1.5, -0.5, 0.3, -0.2);

        phase.run(TENANT, List.of());

        MindMapNode node = cognitiveNodes().get(0);
        assertEquals(-0.5, node.pleasure(), 0.01);
        assertEquals(0.3, node.arousal(), 0.01);
        assertEquals(-0.2, node.dominance(), 0.01);
    }

    @Test
    void multipleFormativeMemories_allGraduateIndependently() {
        storeFormativeMemory("a1", "memory one", 0.8, 1.5, null, null, null);
        storeFormativeMemory("a1", "memory two", 0.9, 1.5, null, null, null);
        storeFormativeMemory("a2", "memory three", 0.7, 1.5, null, null, null);

        phase.run(TENANT, List.of());

        assertEquals(3, cognitiveNodes().size());
    }

    @Test
    void formativeMemory_scoreDerivedFromConfidenceTimesSalience() {
        storeFormativeMemory("a1", "high salience memory",
                             0.8, 1.5, null, null, null);

        phase.run(TENANT, List.of());

        MindMapNode node  = cognitiveNodes().get(0);
        double      score = Double.parseDouble(node.property("graduation-score").orElse("0"));
        assertEquals(1.0, score, 0.01, "min(1.0, 0.8 * 1.5) = 1.0");
    }

}
