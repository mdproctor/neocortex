package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DriveDescriptionGenerationPhaseTest {

    private InMemoryMindMapStore store;
    private DriveDescriptionGenerationPhase phase;
    private String cognitiveSubgraphId;
    private List<String> capturedUserPrompts;
    private List<AgentSessionInit> capturedInits;

    private static final String TENANT = "t1";
    private static final String AGENT = "peter-perfect";

    @BeforeEach
    void setUp() {
        store               = new InMemoryMindMapStore();
        capturedUserPrompts = new CopyOnWriteArrayList<>();
        capturedInits       = new CopyOnWriteArrayList<>();

        phase = new DriveDescriptionGenerationPhase(store);
        phase.setSessionPipeline(StubPipelines.create(init -> {
            capturedInits.add(init);
            boolean isDiscrimination = init.systemPrompt().contains("discrimination");
            return new AgentSession() {
                @Override
                public Multi<AgentEvent> query(String userPrompt) {
                    capturedUserPrompts.add(userPrompt);
                    if (isDiscrimination) {
                        return Multi.createFrom().items(
                                new AgentEvent.TextDelta("{\"discrimination\": \"How they differ: One pull is about what your hands say. "),
                                new AgentEvent.TextDelta("The other is about what your hands can do. Notice which one moved your hands.\"}"));
                    }
                    boolean hasRelationships = userPrompt.contains("Relationships");
                    if (hasRelationships) {
                        return Multi.createFrom().items(
                                new AgentEvent.TextDelta("{\"description\": \"A warmth rises through your chest, "),
                                new AgentEvent.TextDelta("spreading into your shoulders.\", \"targeted\": {\"beloved\": \""),
                                new AgentEvent.TextDelta("Something warm and fierce rises when she is near.\"}}"));
                    }
                    return Multi.createFrom().items(
                            new AgentEvent.TextDelta("{\"description\": \"A warmth rises through your chest, "),
                            new AgentEvent.TextDelta("spreading into your shoulders.\"}"));
                }

                @Override
                public io.smallrye.mutiny.Uni<Void> interrupt() {
                    return io.smallrye.mutiny.Uni.createFrom().voidItem();
                }

                @Override
                public void close(java.time.Duration timeout) {}
            };
        }));

        cognitiveSubgraphId = store.createSubgraph(
                new SubgraphInput("cognitive-" + AGENT, SubgraphTypes.COGNITIVE, null), TENANT);
    }

    @Test
    void phaseNameIsDriveDescriptionGeneration() {
        assertThat(phase.name()).isEqualTo("drive-description-generation");
    }

    @Test
    void skipsWhenNoSessionPipeline() {
        var noLlmPhase = new DriveDescriptionGenerationPhase(store);
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        noLlmPhase.run(TENANT, List.of());

        assertThat(findDescriptionNodes()).isEmpty();
    }

    @Test
    void skipsWhenNoDriveNodes() {
        phase.run(TENANT, List.of());
        assertThat(findDescriptionNodes()).isEmpty();
    }

    @Test
    void skipsWhenNoFormationMemories() {
        seedDriveNode("gallantry", 0.9);

        phase.run(TENANT, List.of());

        assertThat(findDescriptionNodes()).isEmpty();
    }

    @Test
    void generatesDescriptionForDriveWithMemories() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        var descriptions = findDescriptionNodes();
        assertThat(descriptions).hasSize(1);
        assertThat(descriptions.getFirst().property("drive-type")).hasValue("gallantry");
        assertThat(descriptions.getFirst().property("agent-id")).hasValue(AGENT);
        assertThat(descriptions.getFirst().property("generated-description")).isPresent();
        assertThat(descriptions.getFirst().property("generated-description").orElse(""))
            .contains("warmth rises through your chest");
    }

    @Test
    void promptIncludesFormationMemoriesAndDriveContext() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        assertThat(capturedUserPrompts).hasSize(1);
        var userPrompt = capturedUserPrompts.getFirst();
        assertThat(userPrompt).contains("gallantry");
        assertThat(userPrompt).contains("0.9");
        assertThat(userPrompt).contains("knelt to help the stranger");
    }

    @Test
    void generatesDescriptionPerDrive() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedFormationMemory("You practised until you earned the grade", "proving-worth");

        phase.run(TENANT, List.of());

        var descriptions = findDescriptionNodes();
        assertThat(descriptions).hasSize(2);
        var driveTypes = descriptions.stream()
            .map(n -> n.property("drive-type").orElse(""))
            .toList();
        assertThat(driveTypes).containsExactlyInAnyOrder("gallantry", "proving-worth");
    }

    @Test
    void skipsExistingDescriptionsOnRerun() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());
        assertThat(findDescriptionNodes()).hasSize(1);
        int callsAfterFirstRun = capturedUserPrompts.size();

        phase.run(TENANT, List.of());
        assertThat(findDescriptionNodes()).hasSize(1);
        assertThat(capturedUserPrompts).as("no LLM calls on rerun — descriptions already exist")
            .hasSize(callsAfterFirstRun);
    }

    @Test
    void descriptionsPreservedAcrossReruns() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedFormationMemory("You practised until you earned the grade", "proving-worth");

        phase.run(TENANT, List.of());
        var firstRunDescriptions = findDescriptionNodes();
        assertThat(firstRunDescriptions).hasSize(2);

        phase.run(TENANT, List.of());
        var secondRunDescriptions = findDescriptionNodes();
        assertThat(secondRunDescriptions).hasSize(2);
        assertThat(secondRunDescriptions.stream().map(MindMapNode::id).toList())
            .as("same node IDs — originals preserved, not replaced")
            .containsExactlyInAnyOrderElementsOf(
                firstRunDescriptions.stream().map(MindMapNode::id).toList());
    }

    @Test
    void partialCoverageGeneratesOnlyMissingDrives() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedDriveNode("protection", 0.8);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedFormationMemory("You practised until you earned the grade", "proving-worth");
        seedFormationMemory("You noticed the loose railing before anyone else", "protection");

        phase.run(TENANT, List.of());
        assertThat(findDescriptionNodes()).hasSize(3);
        int callsForFullRun = capturedUserPrompts.size();

        // Simulate partial coverage: remove one description (as if interrupted)
        var descriptions = findDescriptionNodes();
        var protectionDesc = descriptions.stream()
                                         .filter(n -> "protection".equals(n.properties().get("drive-type")))
                                         .findFirst().orElseThrow();
        store.eraseNode(protectionDesc.id(), TENANT);
        assertThat(findDescriptionNodes()).hasSize(2);

        phase.run(TENANT, List.of());
        assertThat(findDescriptionNodes())
                .as("partial rerun should generate only the missing drive")
                .hasSize(3);
        assertThat(capturedUserPrompts)
                .as("only 1 new LLM call for the missing drive, not 3")
                .hasSize(callsForFullRun + 1);
    }


    @Test
    void includesIntensityInStoredArtifact() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        var desc = findDescriptionNodes().getFirst();
        assertThat(desc.property("drive-intensity")).hasValue("0.9");
    }

    @Test
    void generatesGenericDescriptionForDrivesWithNoServingMemories() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("scheming", 0.6);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        var descriptions = findDescriptionNodes();
        assertThat(descriptions).hasSize(2);
        var driveTypes = descriptions.stream()
            .map(n -> n.property("drive-type").orElse(""))
            .toList();
        assertThat(driveTypes).containsExactlyInAnyOrder("gallantry", "scheming");
    }

    @Test
    void updatesCompetitionWithSomaticDiscrimination() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedFormationMemory("You practised until you earned the grade", "proving-worth");

        store.addNode(
            NodeInput.of("competition-gallantry-proving-worth", cognitiveSubgraphId)
                .withTraits(java.util.Set.of(DriveProfileDerivationPhase.COMPETITION_TRAIT))
                .withProperties(Map.of(
                    "agent-id", AGENT,
                    "drive-a", "gallantry", "drive-b", "proving-worth",
                    "differentiation-text", "Original narrative text",
                    "anchor-memories-a", "father knelt to help",
                    "anchor-memories-b", "practised until dark")),
            TENANT);

        phase.run(TENANT, List.of());

        var competitions = store.search(MindMapQuery.of(TENANT, 200).withType(SubgraphTypes.COGNITIVE))
            .stream()
            .filter(n -> n.traits().contains(DriveProfileDerivationPhase.COMPETITION_TRAIT))
            .toList();
        assertThat(competitions).hasSize(1);
        String diffText = competitions.getFirst().property("differentiation-text").orElse("");
        assertThat(diffText).isNotEqualTo("Original narrative text");
        assertThat(diffText).isNotBlank();
    }

    @Test
    void skipsDiscriminationWhenNoCompetitionArtifacts() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        assertThat(findDescriptionNodes()).hasSize(1);
    }

    @Test
    void crossPhasePipeline_derivationToDiscrimination() {
        var derivationPhase = new DriveProfileDerivationPhase(store,
                                                              (io.casehub.neocortex.caps.SituationClassifier) null,
                                                              (io.casehub.neocortex.caps.CapsTopology) null);

        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedFormationMemoryWithCatalogue("Your father knelt to help the stranger",
                                         "gallantry", "other_safety");
        seedFormationMemoryWithCatalogue("She struggled and you helped her up",
                                         "proving-worth", "other_safety");

        derivationPhase.run(TENANT, List.of());

        var competitionsBefore = store.search(MindMapQuery.of(TENANT, 200).withType(SubgraphTypes.COGNITIVE))
                                      .stream()
                                      .filter(n -> n.traits().contains(DriveProfileDerivationPhase.COMPETITION_TRAIT))
                                      .toList();
        assertThat(competitionsBefore).hasSize(1);
        String narrativeText = competitionsBefore.getFirst().property("differentiation-text").orElse("");
        assertThat(narrativeText).contains("How they differ");

        phase.run(TENANT, List.of());

        var competitionsAfter = store.search(MindMapQuery.of(TENANT, 200).withType(SubgraphTypes.COGNITIVE))
                                     .stream()
                                     .filter(n -> n.traits().contains(DriveProfileDerivationPhase.COMPETITION_TRAIT))
                                     .toList();
        assertThat(competitionsAfter).hasSize(1);
        String updatedText = competitionsAfter.getFirst().property("differentiation-text").orElse("");
        assertThat(updatedText).isNotEqualTo(narrativeText);
        assertThat(updatedText).contains("hands");
    }

    @Test
    void descriptionPromptRequestsMultiChannelExperience() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");

        phase.run(TENANT, List.of());

        assertThat(capturedInits).isNotEmpty();
        var systemPrompt = capturedInits.stream()
                                        .filter(i -> !i.systemPrompt().contains("discrimination"))
                                        .findFirst().orElseThrow()
                                        .systemPrompt();
        assertThat(systemPrompt).as("must request attentional channel")
                                .contains("Attentional");
        assertThat(systemPrompt).as("must request temporal channel")
                                .contains("Temporal");
        assertThat(systemPrompt).as("must request action tendency channel")
                                .contains("Action tendency");
        assertThat(systemPrompt).as("must not restrict to somatic-only")
                                .doesNotContain("somatic only");
    }

    @Test
    void discriminationPromptRequestsMultiChannelCriteria() {
        seedDriveNode("gallantry", 0.9);
        seedDriveNode("proving-worth", 0.7);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedFormationMemory("You practised until you earned the grade", "proving-worth");

        store.addNode(
                NodeInput.of("competition-gallantry-proving-worth", cognitiveSubgraphId)
                         .withTraits(java.util.Set.of(DriveProfileDerivationPhase.COMPETITION_TRAIT))
                         .withProperties(Map.of(
                                 "agent-id", AGENT,
                                 "drive-a", "gallantry", "drive-b", "proving-worth",
                                 "differentiation-text", "narrative",
                                 "anchor-memories-a", "father knelt to help",
                                 "anchor-memories-b", "practised until dark")),
                TENANT);

        phase.run(TENANT, List.of());

        var discriminationInits = capturedInits.stream()
                                               .filter(i -> i.systemPrompt().contains("discrimination"))
                                               .toList();
        assertThat(discriminationInits).isNotEmpty();

        var systemPrompt = discriminationInits.getFirst().systemPrompt();
        assertThat(systemPrompt).as("must request attentional channel")
                                .contains("Attentional");
        assertThat(systemPrompt).as("must request action tendency channel")
                                .contains("Action tendency");
        assertThat(systemPrompt).as("must not restrict to somatic-only")
                                .doesNotContain("somatic only");
    }


    // --- helpers ---


    @Test
    void generatesTargetedDescriptionWhenRelationalSchemasPresent() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedRelationalSchema(AGENT, "penelope-pitstop", "beloved",
                             "She means everything — when she smiles, something in you settles");

        phase.run(TENANT, List.of());

        var targetedDescriptions = findDescriptionNodes().stream()
                                                         .filter(n -> "beloved".equals(n.properties().get("role")))
                                                         .toList();
        assertThat(targetedDescriptions).hasSize(1);
        assertThat(targetedDescriptions.getFirst().property("drive-type")).hasValue("gallantry");
        assertThat(targetedDescriptions.getFirst().property("role")).hasValue("beloved");
        assertThat(targetedDescriptions.getFirst().property("generated-description")).isPresent();
    }

    @Test
    void untargetedDescriptionStillGeneratedAlongsideTargeted() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedRelationalSchema(AGENT, "penelope-pitstop", "beloved",
                             "She means everything");

        phase.run(TENANT, List.of());

        var untargeted = findDescriptionNodes().stream()
                                               .filter(n -> n.properties().get("role") == null)
                                               .filter(n -> "gallantry".equals(n.properties().get("drive-type")))
                                               .toList();
        assertThat(untargeted).as("untargeted description must still be generated").hasSize(1);

        var targeted = findDescriptionNodes().stream()
                                             .filter(n -> "beloved".equals(n.properties().get("role")))
                                             .toList();
        assertThat(targeted).as("targeted description also generated").hasSize(1);
    }

    @Test
    void promptIncludesRelationalSchemaContext() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedRelationalSchema(AGENT, "penelope-pitstop", "beloved",
                             "She means everything");

        phase.run(TENANT, List.of());

        var descPrompts = capturedUserPrompts.stream()
                                             .filter(p -> p.contains("gallantry"))
                                             .toList();
        assertThat(descPrompts).isNotEmpty();
        assertThat(descPrompts.getFirst()).contains("beloved");
        assertThat(descPrompts.getFirst()).contains("She means everything");
    }

    @Test
    void skipsTargetedDescriptionsOnIdempotentRerun() {
        seedDriveNode("gallantry", 0.9);
        seedFormationMemory("Your father knelt to help the stranger", "gallantry");
        seedRelationalSchema(AGENT, "penelope-pitstop", "beloved",
                             "She means everything");

        phase.run(TENANT, List.of());
        int firstRunCalls = capturedUserPrompts.size();
        assertThat(findDescriptionNodes().stream()
                                         .filter(n -> "beloved".equals(n.properties().get("role")))
                                         .toList()).hasSize(1);

        phase.run(TENANT, List.of());
        assertThat(capturedUserPrompts).as("no new LLM calls on rerun")
                                       .hasSize(firstRunCalls);
    }


    private void seedDriveNode(String driveType, double intensity) {
        store.addNode(
            NodeInput.of(driveType, cognitiveSubgraphId)
                .withProperties(Map.of(
                    "cognitiveKind", "drive-intensity",
                    "agent-id", AGENT,
                    "drive-type", driveType,
                    "intensity", String.valueOf(intensity),
                    "description", "Drive: " + driveType)),
            TENANT);
    }

    private void seedFormationMemory(String episodeText, String driveType) {
        store.addNode(
            NodeInput.of(episodeText, cognitiveSubgraphId)
                .withProperties(Map.of(
                    "source-memory-id", "mem-" + episodeText.hashCode(),
                    "agent-id", AGENT,
                    "cognitiveKind", "formative-experience",
                    "situation-types", "formation,childhood," + driveType)),
            TENANT);
    }

    private void seedFormationMemoryWithCatalogue(String episodeText, String driveType,
                                                  String catalogueEntry) {
        store.addNode(
                NodeInput.of(episodeText, cognitiveSubgraphId)
                         .withProperties(Map.of(
                                 "source-memory-id", "mem-" + episodeText.hashCode(),
                                 "agent-id", AGENT,
                                 "cognitiveKind", "formative-experience",
                                 "situation-types", "formation,childhood," + driveType,
                                 "catalogue-entry-id", catalogueEntry)),
                TENANT);
    }


    private void seedRelationalSchema(String agentId, String targetId, String role, String description) {
        store.addNode(
                NodeInput.of("rel-" + agentId + "-" + targetId, cognitiveSubgraphId)
                         .withProperties(Map.of(
                                 "cognitiveKind", "relational-schema",
                                 "agent-id", agentId,
                                 "target-id", targetId,
                                 "rel.role", role,
                                 "rel.description", description)),
                TENANT);
    }

    private List<MindMapNode> findDescriptionNodes() {
        return store.search(MindMapQuery.of(TENANT, 200).withType(SubgraphTypes.COGNITIVE))
            .stream()
            .filter(n -> n.traits().contains(DriveDescriptionGenerationPhase.DESCRIPTION_TRAIT))
            .toList();
    }
}
