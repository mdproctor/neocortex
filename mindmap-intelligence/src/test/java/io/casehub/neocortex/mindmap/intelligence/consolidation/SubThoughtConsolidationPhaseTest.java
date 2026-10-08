package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.experience.SubThoughtAttributeKeys;
import io.casehub.neocortex.memory.experience.SubThoughtTypes;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.SubThoughtRef;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubThoughtConsolidationPhaseTest {

    private static final String TENANT = "test-tenant";
    private static final Subject SUBJECT = Subject.of("agent", "a1");

    private InMemoryMemoryStore memoryStore;
    private InMemoryMindMapStore mindMapStore;
    private SubThoughtConsolidationPhase phase;

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "a1"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public String tenancyId() { return TENANT; }
        @Override public boolean isCrossTenantAdmin() { return true; }
    };

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryMemoryStore(principal);
        mindMapStore = new InMemoryMindMapStore();
        phase = new SubThoughtConsolidationPhase(memoryStore, mindMapStore, 3);
    }

    @Test
    void graduatesWhenThresholdCrossed() {
        for (int i = 0; i < 3; i++) {
            storeMemoryWithSubThought(SubThoughtTypes.CAUSAL_INFERENCE,
                "Inference " + i, "Sarah");
        }

        phase.run(TENANT, List.of(SubgraphTypes.COGNITIVE));

        var nodes = mindMapStore.search(
            MindMapQuery.of(TENANT, 100).withTraits(Set.of("graduated-sub-thought")));
        assertThat(nodes).hasSize(1);
        var node = nodes.getFirst();
        assertThat(node.properties()).containsEntry("cognitiveKind", "causal-inference");
        assertThat(node.properties()).containsEntry("source-count", "3");
        assertThat(node.refs()).hasSize(3);
        assertThat(node.refs()).allMatch(r -> SubThoughtRef.SCHEME.equals(r.scheme()));
    }

    @Test
    void doesNotGraduateBelowThreshold() {
        for (int i = 0; i < 2; i++) {
            storeMemoryWithSubThought(SubThoughtTypes.CAUSAL_INFERENCE,
                "Inference " + i, "Sarah");
        }

        phase.run(TENANT, List.of(SubgraphTypes.COGNITIVE));

        var nodes = mindMapStore.search(
            MindMapQuery.of(TENANT, 100).withTraits(Set.of("graduated-sub-thought")));
        assertThat(nodes).isEmpty();
    }

    @Test
    void skipsBiographicalImportMemories() {
        for (int i = 0; i < 3; i++) {
            var attrs = subThoughtAttrs(SubThoughtTypes.EVALUATIVE, "Eval " + i, "Place");
            attrs.put("provenance", "biographical-import");
            storeMemoryWithAttrs("Bio memory " + i, attrs);
        }

        phase.run(TENANT, List.of(SubgraphTypes.COGNITIVE));

        var nodes = mindMapStore.search(
            MindMapQuery.of(TENANT, 100).withTraits(Set.of("graduated-sub-thought")));
        assertThat(nodes).isEmpty();
    }

    @Test
    void marksGraduatedSubThoughts() {
        for (int i = 0; i < 3; i++) {
            storeMemoryWithSubThought(SubThoughtTypes.CONCERN,
                "Concern " + i, "Sarah");
        }

        phase.run(TENANT, List.of(SubgraphTypes.COGNITIVE));

        var memories = memoryStore.scan(new io.casehub.neocortex.memory.MemoryScanRequest(
            TENANT, ExperienceEvents.DOMAIN.name(), null, null, 50, null));
        long graduatedCount = memories.stream()
            .filter(m -> "true".equals(m.attributes().get(SubThoughtAttributeKeys.graduated(0))))
            .count();
        assertThat(graduatedCount).isEqualTo(3);
    }

    private void storeMemoryWithSubThought(String type, String text, String entity) {
        storeMemoryWithAttrs("Experience about " + entity, subThoughtAttrs(type, text, entity));
    }

    private HashMap<String, String> subThoughtAttrs(String type, String text, String entity) {
        var attrs = new HashMap<String, String>();
        attrs.put("event-type", "observation");
        attrs.put(SubThoughtAttributeKeys.COUNT, "1");
        attrs.put(SubThoughtAttributeKeys.type(0), type);
        attrs.put(SubThoughtAttributeKeys.text(0), text);
        if (entity != null) attrs.put(SubThoughtAttributeKeys.entity(0), entity);
        return attrs;
    }

    @Test
    void concernEscalation_emitsUrgencySpike() {
        for (int i = 0; i < 3; i++) {
            storeMemoryWithSubThought(SubThoughtTypes.CONCERN,
                "Worried about " + i, "Sneekly");
        }

        phase.beginTick();
        phase.run(TENANT, List.of());

        assertThat(phase.signals()).anyMatch(s ->
            s.category() == io.casehub.neocortex.mindmap.SignalCategory.URGENCY_SPIKE
            && "Sneekly".equals(s.sourceName()));
    }

    @Test
    void contradictorySubThoughts_emitsMergeCandidate() {
        storeMemoryWithSubThought(SubThoughtTypes.AFFECT_OBSERVATION,
            "Sneekly seemed helpful", "Sneekly");
        storeMemoryWithSubThought(SubThoughtTypes.AFFECT_OBSERVATION,
            "Sneekly seemed helpful again", "Sneekly");
        storeMemoryWithSubThought(SubThoughtTypes.CONCERN,
            "Something off about Sneekly", "Sneekly");

        phase.beginTick();
        phase.run(TENANT, List.of());

        assertThat(phase.signals()).anyMatch(s ->
            s.category() == io.casehub.neocortex.mindmap.SignalCategory.MERGE_CANDIDATE
            && "Sneekly".equals(s.sourceName()));
    }

    @Test
    void unresolvedIntentions_tracked() {
        storeMemoryWithSubThought(SubThoughtTypes.INTENTION,
            "Should check on Penelope", "Penelope");
        storeMemoryWithSubThought(SubThoughtTypes.INTENTION,
            "Need to talk to Penelope", "Penelope");

        phase.beginTick();
        phase.run(TENANT, List.of());

        assertThat(phase.unresolvedIntentions()).contains("Penelope");
    }

    private void storeMemoryWithAttrs(String desc, Map<String, String> attrs) {
        memoryStore.store(new MemoryInput(
            SUBJECT, ExperienceEvents.DOMAIN, TENANT,
            null, desc, attrs, null, null, null, null, null, null));
    }
}
