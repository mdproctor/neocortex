package io.casehub.neocortex.memory.seeding.biography;

import io.casehub.neocortex.caps.testing.InMemoryCapsEngine;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.memory.relationship.RelationshipQuery;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityEnrichmentHandlerTest {

    private static final String TENANT = "t1";

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override
        public String actorId()             {return "agent-1";}

        @Override
        public Set<String> groups()         {return Set.of();}

        @Override
        public String tenancyId()           {return TENANT;}

        @Override
        public boolean isCrossTenantAdmin() {return true;}
    };

    @Test
    void storesDriveBaselinesOnEntityNode() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Frida Kahlo", subgraph).withProvenance("test"), TENANT);

        var baselines = Map.of(
                "curiosity", new EntityEnrichmentEntry.DriveBaseline(0.85, "Strong exploration"),
                "competence", new EntityEnrichmentEntry.DriveBaseline(0.88, "High skill drive"));
        var entry = new EntityEnrichmentEntry("enrich-1", "Frida Kahlo", baselines, null, null, null);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        var node = store.resolveNode("Frida Kahlo", subgraph, TENANT);
        assertThat(node.properties()).containsEntry("drive-baseline-curiosity", "0.85");
        assertThat(node.properties()).containsEntry("drive-baseline-curiosity-justification", "Strong exploration");
        assertThat(node.properties()).containsEntry("drive-baseline-competence", "0.88");
    }

    @Test
    void storesCapsPatterns() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Test Entity", subgraph).withProvenance("test"), TENANT);

        var patterns = List.of(
                new EntityEnrichmentEntry.CapsPattern("threat -> creative", 0.8, null, null));
        var entry = new EntityEnrichmentEntry("enrich-2", "Test Entity", null, patterns, null, null);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        var node = store.resolveNode("Test Entity", subgraph, TENANT);
        assertThat(node.properties()).containsEntry("caps-pattern-count", "1");
        assertThat(node.properties()).containsEntry("caps-pattern-0-pattern", "threat -> creative");
        assertThat(node.properties()).containsEntry("caps-pattern-0-frequency", "0.8");
    }

    @Test
    void skipsUnresolvedEntity() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var entry = new EntityEnrichmentEntry("enrich-3", "Unknown Entity",
                                              Map.of("curiosity", new EntityEnrichmentEntry.DriveBaseline(0.5, null)),
                                              null, null, null);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);
    }

    @Test
    void storesRelationshipStagesAsMemories() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Frida Kahlo", subgraph).withProvenance("test"), TENANT);

        var transitions = List.of(
                new EntityEnrichmentEntry.Transition("admiration", "passionate", "1928-1929",
                                                     "Diego championed her art"),
                new EntityEnrichmentEntry.Transition("passionate", "turbulent", "1934-1935", null));
        var stages = List.of(new EntityEnrichmentEntry.RelationshipStage("Diego Rivera", transitions));
        var entry  = new EntityEnrichmentEntry("enrich-4", "Frida Kahlo", null, null, null, stages);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        var memories = memoryStore.query(RelationshipQuery.forAgent("agent-1", TENANT));
        assertThat(memories).hasSize(2);

        var first = memories.stream()
                            .filter(m -> m.text().contains("admiration"))
                            .findFirst().orElseThrow();
        assertThat(first.text()).contains("Diego Rivera");
        assertThat(first.text()).contains("admiration");
        assertThat(first.text()).contains("passionate");
        assertThat(first.attributes().get("other-agent")).isEqualTo("Diego Rivera");
        assertThat(first.attributes().get("source-event-type")).isEqualTo("stage-transition");
        assertThat(first.attributes().get("stage-from")).isEqualTo("admiration");
        assertThat(first.attributes().get("stage-to")).isEqualTo("passionate");
        assertThat(first.attributes().get("period")).isEqualTo("1928-1929");
        assertThat(first.attributes().get("trigger")).isEqualTo("Diego championed her art");
        assertThat(first.attributes().get("provenance")).isEqualTo("biographical-import");

        var second = memories.stream()
                             .filter(m -> m.text().contains("turbulent"))
                             .findFirst().orElseThrow();
        assertThat(second.attributes().get("stage-from")).isEqualTo("passionate");
        assertThat(second.attributes().get("stage-to")).isEqualTo("turbulent");
        assertThat(second.attributes()).doesNotContainKey("trigger");
    }

    @Test
    void skipsNullRelationshipStages() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Test Entity", subgraph).withProvenance("test"), TENANT);

        var entry = new EntityEnrichmentEntry("enrich-5", "Test Entity", null, null, null, null);
        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        var memories = memoryStore.query(RelationshipQuery.forAgent("agent-1", TENANT));
        assertThat(memories).isEmpty();
    }

    @Test
    void seedsCapsWeightsFromInputActivations() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var capsEngine  = new InMemoryCapsEngine();
        var handler     = new EntityEnrichmentHandler(store, memoryStore, capsEngine, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Frida Kahlo", subgraph).withProvenance("test"), TENANT);

        var patterns = List.of(
                new EntityEnrichmentEntry.CapsPattern(
                        "betrayal → trust_violation → AUTONOMY_ASSERTION", 3.0, 0.87,
                        Map.of("betrayal", 0.9, "abandonment", 0.6)));
        var entry = new EntityEnrichmentEntry("enrich-caps", "Frida Kahlo", null, patterns, null, null);

        var disposition = new DispositionProfileEntry(
                "disp-1", "Frida Kahlo", "cooperative", null, "bold", "high", "confrontational", null);

        var profile = new BiographyProfile("agent-1", TENANT,
                                           List.of(), List.of(), List.of(), List.of(), List.of(),
                                           List.of(), List.of(), List.of(), List.of(), List.of(),
                                           List.of(entry), List.of(disposition), List.of());

        handler.handle(profile, "agent-1", TENANT);

        var state = capsEngine.loadState(TENANT, "agent-1");
        assertThat(state).isNotNull();
        assertThat(state.generation()).isEqualTo(0);
        assertThat(state.weights()).isNotEmpty();
    }

    @Test
    void skipsCapsSeedingWhenNoInputActivations() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var capsEngine  = new InMemoryCapsEngine();
        var handler     = new EntityEnrichmentHandler(store, memoryStore, capsEngine, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Test Entity", subgraph).withProvenance("test"), TENANT);

        var patterns = List.of(
                new EntityEnrichmentEntry.CapsPattern("threat -> creative", 0.8, null, null));
        var entry = new EntityEnrichmentEntry("enrich-no-caps", "Test Entity", null, patterns, null, null);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        assertThat(capsEngine.loadState(TENANT, "agent-1")).isNull();
    }


    @Test
    void storesBehaviouralAttractorsAsBehavioralSubgraphNodes() {
        var store       = new InMemoryMindMapStore();
        var memoryStore = new InMemoryMemoryStore(principal);
        var handler     = new EntityEnrichmentHandler(store, memoryStore, true);

        var subgraph = BiographyUtils.ensureSubgraph(store, SubgraphTypes.PERSON, TENANT);
        store.addNode(NodeInput.of("Frida Kahlo", subgraph).withProvenance("test"), TENANT);

        var attractors = List.of(
                new EntityEnrichmentEntry.BehaviouralAttractor(
                        "defiance-through-artistic-authenticity",
                        "Responded to pain by painting trauma as legitimate subject matter",
                        "Physical suffering, emotional devastation",
                        "Paint the precise experience of pain/trauma in visceral detail",
                        0.9),
                new EntityEnrichmentEntry.BehaviouralAttractor(
                        "autonomy-seeking-through-intellectual-equality",
                        "Pursued recognition as an intellectual and artistic equal",
                        "Mentorship dynamics, art-world hierarchies",
                        "Assert intellectual authority, demand recognition",
                        0.85));
        var entry = new EntityEnrichmentEntry("enrich-ba", "Frida Kahlo", null, null, attractors, null);

        handler.handle(profileWith(List.of(entry)), "agent-1", TENANT);

        var behavioralSubgraph = store.listSubgraphs(TENANT).stream()
                                      .filter(s -> SubgraphTypes.BEHAVIORAL.equals(s.type()))
                                      .findFirst();
        assertThat(behavioralSubgraph).isPresent();

        var nodes = store.nodesIn(behavioralSubgraph.get().id(), TENANT);
        assertThat(nodes).hasSize(2);

        var defiance = nodes.stream()
                            .filter(n -> "defiance-through-artistic-authenticity".equals(n.properties().get("category")))
                            .findFirst().orElseThrow();
        assertThat(defiance.traits()).contains("CapsGenerated");
        assertThat(defiance.properties()).containsEntry("strength", "0.9");
        assertThat(defiance.properties()).containsEntry("agent-id", "agent-1");
        assertThat(defiance.properties()).containsEntry("provenance", "biographical-import");
        assertThat(defiance.properties()).containsEntry("description",
                                                        "Responded to pain by painting trauma as legitimate subject matter");
        assertThat(defiance.properties()).containsEntry("trigger",
                                                        "Physical suffering, emotional devastation");
        assertThat(defiance.properties()).containsEntry("response",
                                                        "Paint the precise experience of pain/trauma in visceral detail");

        var autonomy = nodes.stream()
                            .filter(n -> "autonomy-seeking-through-intellectual-equality".equals(n.properties().get("category")))
                            .findFirst().orElseThrow();
        assertThat(autonomy.properties()).containsEntry("strength", "0.85");

        var entityNode = store.resolveNode("Frida Kahlo", subgraph, TENANT);
        assertThat(entityNode.properties()).doesNotContainKey("behavioural-attractor-count");
    }

    private BiographyProfile profileWith(List<EntityEnrichmentEntry> enrichments) {
        return new BiographyProfile("a1", "t1",
                                    List.of(), List.of(), List.of(), List.of(), List.of(),
                                    List.of(), List.of(), List.of(), List.of(), List.of(),
                                    enrichments, List.of(), List.of());
    }
}
