package io.casehub.neocortex.caps.llm.eval;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.engine.CapsTopologyLoader;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.consolidation.DriveProfileDerivationPhase;
import io.casehub.platform.agent.session.SessionPipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
class DriveProfilePersonalityEvalTest {

    private static final String TENANT = "personality-eval";

    @Test
    void personalityAwareVsAgnosticComparison() {
        var characters = EvalCharacterLoader.loadGeneric();

        SessionPipeline sessionPipeline = EvalTestSupport.sessionPipeline();
        CapsTopology topology = new CapsTopologyLoader().loadFromClasspath("caps-topology.yaml");
        SituationClassifier classifier = new LlmSituationClassifier(sessionPipeline, topology);

        var registry = buildRegistry();

        var baselineProfiles = runDerivation(characters, classifier, topology, null);
        var personalityProfiles = runDerivation(characters, classifier, topology, registry);

        System.out.println("=== Personality-Aware vs Agnostic Drive Profile Comparison ===\n");

        int totalDrives = 0;
        int differing = 0;

        for (var agentEntry : personalityProfiles.entrySet()) {
            String agentId = agentEntry.getKey();
            var baselineAgent = baselineProfiles.getOrDefault(agentId, Map.of());

            for (var driveEntry : agentEntry.getValue().entrySet()) {
                String driveType = driveEntry.getKey();
                String personalityInputs = driveEntry.getValue();
                String baselineInputs = baselineAgent.getOrDefault(driveType, "(no baseline)");

                totalDrives++;
                boolean same = personalityInputs.equals(baselineInputs);
                if (!same) differing++;

                System.out.println(agentId + "/" + driveType + ":");
                System.out.println("  baseline:    " + baselineInputs);
                System.out.println("  personality: " + personalityInputs);
                System.out.println("  " + (same ? "SAME" : "DIFFERENT"));
                System.out.println();
            }
        }

        System.out.println("=== Summary ===");
        System.out.println("Total drives: " + totalDrives);
        System.out.println("Differing: " + differing + " (" + (totalDrives > 0 ? differing * 100 / totalDrives : 0) + "%)");

        assertThat(totalDrives).as("Should derive at least one profile").isGreaterThan(0);
    }

    private Map<String, Map<String, String>> runDerivation(
            Map<String, EvalCharacterData> characters,
            SituationClassifier classifier, CapsTopology topology,
            CognitiveDefaultsRegistry registry) {

        var store = new InMemoryMindMapStore();
        var testedAgents = Set.of("peter-perfect", "hooded-claw", "penelope-pitstop");
        for (var agentId : testedAgents) {
            var data = characters.get(agentId);
            if (data != null) {
                EvalMindMapSeeder.seedCharacter(agentId, data, store, TENANT);
            }
        }

        var phase = registry != null
                ? new DriveProfileDerivationPhase(store, classifier, topology, registry)
                : new DriveProfileDerivationPhase(store, classifier, topology);

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                phase.run(TENANT, List.of());
                break;
            } catch (Exception e) {
                System.out.println("Derivation attempt " + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt == 2) throw e;
            }
        }

        var profiles = new HashMap<String, Map<String, String>>();
        var profileNodes = store.search(MindMapQuery.of(TENANT, 500).withType(SubgraphTypes.COGNITIVE))
                .stream()
                .filter(n -> n.traits().contains("DriveProfileInferred"))
                .toList();

        for (var node : profileNodes) {
            String agentId = node.properties().get("agent-id");
            String driveType = node.properties().get("drive-type");
            String inputs = node.properties().get("inferred-inputs");
            if (agentId != null && driveType != null) {
                profiles.computeIfAbsent(agentId, k -> new HashMap<>()).put(driveType, inputs);
            }
        }
        return profiles;
    }

    private static CognitiveDefaultsRegistry buildRegistry() {
        var pp = CognitiveDerivationEngine.derive(DescriptorView.of("peter-perfect",
                new DispositionAxes("cooperative", "strict", "conservative", "low", "protective"),
                List.of(), List.of()));
        var hc = CognitiveDerivationEngine.derive(DescriptorView.of("hooded-claw",
                new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                List.of(), List.of()));
        var pen = CognitiveDerivationEngine.derive(DescriptorView.of("penelope-pitstop",
                new DispositionAxes("cooperative", "flexible", "bold", "collaborative", "protective"),
                List.of(), List.of()));
        return CognitiveDefaultsRegistry.forTesting(pp, hc, pen);
    }
}
