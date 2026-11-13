package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.caps.AgentCapsState;
import io.casehub.neocortex.caps.CapsAttractor;
import io.casehub.neocortex.caps.CapsConnection;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ConnectionWeight;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.WeightProvenance;
import io.casehub.neocortex.caps.WeightUpdateContext;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrRecordStore;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class BehavioralSynthesisPhaseTest {

    private InMemoryMindMapStore store;
    private StubCapsEngine capsEngine;
    private BehavioralSynthesisPhase phase;
    private String cognitiveSubgraphId;
    private static final String TENANT = "t1";
    private static final String AGENT = "agent1";

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        capsEngine = new StubCapsEngine();
        SituationClassifier classifier = (desc, meta, ctx) ->
            new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));

        phase = new BehavioralSynthesisPhase(store, capsEngine, classifier, 20);

        cognitiveSubgraphId = store.createSubgraph(
            new SubgraphInput("Cognitive", SubgraphTypes.COGNITIVE, null), TENANT);

        capsEngine.initializeAgent(TENANT, AGENT,
            new DispositionAxes("cooperative", "moderate", "calculated",
                "moderate", "analytical"));
    }

    @Test
    void phaseNameIsBehavioralSynthesis() {
        assertThat(phase.name()).isEqualTo("behavioral-synthesis");
    }

    @Test
    void skipsWhenNoCapsEngine() {
        var noEngine = new BehavioralSynthesisPhase(
            store, null, (d, m, c) -> new ClassificationResult(List.of()), 20);
        noEngine.run(TENANT, List.of());
    }

    @Test
    void skipsWhenNoGraduatedNodes() {
        phase.run(TENANT, List.of());
        List<MindMapNode> behavioral = store.search(
            MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral).isEmpty();
    }

    @Test
    void createsAttractorFromGraduatedExperience() {
        store.addNode(NodeInput.of("Was rewarded for helping", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.7")),
            TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral = store.search(
            MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral).isNotEmpty();
        assertThat(behavioral).anySatisfy(n -> {
            assertThat(n.traits()).contains("CapsGenerated");
            assertThat(n.property("agent-id")).hasValue(AGENT);
            assertThat(n.property("category")).isPresent();
        });
    }

    @Test
    void strengthensExistingAttractor() {
        store.addNode(NodeInput.of("Praised for hard work", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.6")),
            TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral1 = store.search(
            MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral1).hasSize(1);
        double firstStrength = Double.parseDouble(
            behavioral1.getFirst().property("strength").orElse("0"));

        store.addNode(NodeInput.of("Got a bonus for performance", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem2",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.8")),
            TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral2 = store.search(
            MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral2).hasSize(1);
        assertThat(behavioral2.getFirst().property("generation")).isPresent();
    }

    @Test
    void updatesWeightsAfterSettling() {
        store.addNode(NodeInput.of("Rewarded consistently", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.9")),
            TENANT);

        long genBefore = capsEngine.loadState(TENANT, AGENT).generation();
        phase.run(TENANT, List.of());
        long genAfter = capsEngine.loadState(TENANT, AGENT).generation();

        assertThat(genAfter).isGreaterThan(genBefore);
    }


    @Test
    void emotionBearingNodesUseTargetedReinforcement() {
        var emotionEngine = new EmotionAwareStubCapsEngine();
        emotionEngine.initializeAgent(TENANT, AGENT,
                                      new io.casehub.neocortex.cognitive.index.DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"));
        SituationClassifier classifier = (desc, meta, ctx) ->
                                                 new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        var emotionPhase = new BehavioralSynthesisPhase(store, emotionEngine, classifier, 20);

        store.addNode(NodeInput.of("OCC emotion: FEAR", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-e1",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "emotion-type", "FEAR",
                                       "emotion-intensity", "0.7"))
                               .withPad(-0.64, 0.60, -0.43),
                      TENANT);

        emotionPhase.run(TENANT, List.of());

        assertThat(emotionEngine.lastEmotionUpdate).isNotNull();
        assertThat(emotionEngine.lastEmotionUpdate.valence).isEqualTo(-1.0);
        assertThat(emotionEngine.lastEmotionUpdate.intensity).isEqualTo(0.7);
        assertThat(emotionEngine.lastEmotionUpdate.salienceMultiplier).isGreaterThan(1.0);
        assertThat(emotionEngine.lastEmotionUpdate.activations).containsKey("punishment");
    }

    @Test
    void nodesWithoutEmotionUsePadFallback() {
        var emotionEngine = new EmotionAwareStubCapsEngine();
        emotionEngine.initializeAgent(TENANT, AGENT,
                                      new io.casehub.neocortex.cognitive.index.DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical"));
        SituationClassifier classifier = (desc, meta, ctx) ->
                                                 new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        var emotionPhase = new BehavioralSynthesisPhase(store, emotionEngine, classifier, 20);

        store.addNode(NodeInput.of("Generic experience", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-r1",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.5"))
                               .withPad(0.3, 0.2, 0.1),
                      TENANT);

        emotionPhase.run(TENANT, List.of());

        assertThat(emotionEngine.lastEmotionUpdate).isNull();
        assertThat(emotionEngine.lastRegularUpdate).isNotNull();
        assertThat(emotionEngine.lastRegularUpdate.salienceMultiplier).isEqualTo(1.0);
    }

    @Test
    void newAttractorHasSourceCountAndSourceNames() {
        store.addNode(NodeInput.of("Rewarded for helping others", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem1",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.7")),
                      TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral).hasSize(1);
        var node = behavioral.getFirst();
        assertThat(node.property("source-count")).hasValue("1");
        assertThat(node.property("source-names")).isPresent();
        assertThat(node.property("source-names").get()).contains("Rewarded for helping others");
    }

    @Test
    void updatedAttractorTracksPreviousStrengthAndIncrementsSources() {
        store.addNode(NodeInput.of("First experience", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem1",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.6")),
                      TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral1 = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral1).hasSize(1);
        double firstStrength = Double.parseDouble(
                behavioral1.getFirst().property("strength").orElse("0"));
        assertThat(behavioral1.getFirst().property("previous-strength")).isNotPresent();

        store.addNode(NodeInput.of("Second experience", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem2",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.8")),
                      TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral2 = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral2).hasSize(1);
        var updated = behavioral2.getFirst();
        assertThat(updated.property("previous-strength"))
                .hasValue(String.valueOf(firstStrength));
        assertThat(updated.property("source-count")).hasValue("2");
        assertThat(updated.property("source-names").get()).contains("Second experience");
    }

    @Test
    void sourceNamesCappedAtFive() {
        for (int i = 1; i <= 7; i++) {
            store.addNode(NodeInput.of("Experience " + i, cognitiveSubgraphId)
                                   .withProperties(Map.of(
                                           "source-memory-id", "mem" + i,
                                           "agent-id", AGENT,
                                           "event-type", "observation",
                                           "graduation-score", "0.7")),
                          TENANT);
            phase.run(TENANT, List.of());
        }

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral).hasSize(1);
        String names = behavioral.getFirst().property("source-names").orElse("");
        long   count = names.chars().filter(c -> c == ',').count() + 1;
        assertThat(count).isLessThanOrEqualTo(5);
        assertThat(names).contains("Experience 7");
        assertThat(names).doesNotContain("Experience 1");
    }

    @Test
    void uninitializedAgent_autoInitializedAndProcessed() {
        var freshEngine = new StubCapsEngine();
        SituationClassifier classifier = (desc, meta, ctx) ->
                                                 new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        var freshPhase = new BehavioralSynthesisPhase(store, freshEngine, classifier, 20);

        store.addNode(NodeInput.of("Formative childhood experience", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-f1",
                                       "agent-id", AGENT,
                                       "event-type", "formative",
                                       "graduation-score", "1.0")),
                      TENANT);

        freshPhase.run(TENANT, List.of());

        assertThat(freshEngine.loadState(TENANT, AGENT))
                .as("Agent should be auto-initialized when loadState returns null")
                .isNotNull();

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral)
                .as("Behavioral attractors should be created for auto-initialized agent")
                .isNotEmpty();
        assertThat(behavioral).anySatisfy(n -> {
            assertThat(n.traits()).contains("CapsGenerated");
            assertThat(n.property("agent-id")).hasValue(AGENT);
        });
    }

    @Test
    void findUnprocessedGraduated_notMissedBySearchLimit() {
        for (int sg = 0; sg < 20; sg++) {
            String sgId = store.createSubgraph(
                    new SubgraphInput("extra-" + sg, SubgraphTypes.COGNITIVE, null), TENANT);
            for (int n = 0; n < 10; n++) {
                store.addNode(NodeInput.of("filler-" + sg + "-" + n, sgId)
                                       .withProperties(Map.of("agent-id", "filler-agent")), TENANT);
            }
        }

        store.addNode(NodeInput.of("graduated memory", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-target",
                                       "agent-id", AGENT,
                                       "event-type", "formative",
                                       "graduation-score", "1.0")),
                      TENANT);

        phase.run(TENANT, List.of());

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral)
                .as("Graduated node must be found despite 200+ other cognitive nodes")
                .isNotEmpty();
    }

    @Test
    void driveIntensitiesInfluenceCapsActivations() {
        var capturingEngine = new CapturingCapsEngine();
        capturingEngine.initializeAgent(TENANT, AGENT,
                                        new DispositionAxes("cooperative", "moderate", "calculated",
                                                            "moderate", "analytical"));

        SituationClassifier classifier = (desc, meta, ctx) ->
                                                 new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));

        var drivePhase = new BehavioralSynthesisPhase(store, capturingEngine, classifier, 20);

        store.addNode(NodeInput.of("scheming", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "cognitiveKind", "drive-intensity",
                                       "drive-type", "scheming",
                                       "intensity", "0.75",
                                       "description", "Compelled to hatch elaborate plans",
                                       "agent-id", AGENT)),
                      TENANT);

        store.addNode(NodeInput.of("Rewarded for work", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem1",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.7")),
                      TENANT);

        drivePhase.run(TENANT, List.of());

        assertThat(capturingEngine.lastInputActivations).isNotNull();
        assertThat(capturingEngine.lastInputActivations).containsKey("reward");
        assertThat(capturingEngine.lastInputActivations)
                .as("D1 mapping: scheming → dominance, distrust, self_direct at drive intensity")
                .containsKey("dominance")
                .containsKey("distrust")
                .containsKey("self_direct");
        assertThat(capturingEngine.lastInputActivations.get("dominance"))
                .as("dominance activation should equal drive intensity 0.75")
                .isEqualTo(0.75);
    }

    @Test
    void repeatedReinforcementPushesStrengthAboveEmerging() {
        for (int i = 1; i <= 10; i++) {
            store.addNode(NodeInput.of("Reinforcing experience " + i, cognitiveSubgraphId)
                                   .withProperties(Map.of(
                                           "source-memory-id", "mem-r" + i,
                                           "agent-id", AGENT,
                                           "event-type", "observation",
                                           "graduation-score", "0.8")),
                          TENANT);
            phase.run(TENANT, List.of());
        }

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral).hasSize(1);
        double strength = Double.parseDouble(
                behavioral.getFirst().property("strength").orElse("0"));
        assertThat(strength)
                .as("After 10 reinforcements, a well-established pattern should exceed 'deep-rooted' threshold 0.7")
                .isGreaterThan(0.7);
    }

    @Test
    void differentDominantInputsCreateSeparateAttractors() {
        SituationClassifier inputAwareClassifier = (desc, meta, ctx) -> {
            if (desc.contains("relationship")) {
                return new ClassificationResult(List.of(new SituationActivation("secure_attachment", 0.9)));
            } else if (desc.contains("achievement")) {
                return new ClassificationResult(List.of(new SituationActivation("mastery", 0.9)));
            }
            return new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        };

        var diffPhase = new BehavioralSynthesisPhase(store, capsEngine, inputAwareClassifier, 20);

        store.addNode(NodeInput.of("Strong relationship bond", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-rel",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.7")),
                      TENANT);
        diffPhase.run(TENANT, List.of());

        store.addNode(NodeInput.of("Great achievement at work", cognitiveSubgraphId)
                               .withProperties(Map.of(
                                       "source-memory-id", "mem-ach",
                                       "agent-id", AGENT,
                                       "event-type", "observation",
                                       "graduation-score", "0.7")),
                      TENANT);
        diffPhase.run(TENANT, List.of());

        List<MindMapNode> behavioral = store.search(
                MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.BEHAVIORAL));
        assertThat(behavioral)
                .as("Two experiences with different dominant inputs should create separate attractors")
                .hasSizeGreaterThanOrEqualTo(2);

        var dominantInputs = behavioral.stream()
                                       .map(n -> n.property("dominant-input").orElse(""))
                                       .filter(s -> !s.isEmpty())
                                       .collect(java.util.stream.Collectors.toSet());
        assertThat(dominantInputs)
                .as("Each attractor should track its dominant input")
                .hasSizeGreaterThanOrEqualTo(2);
    }


    @Test
    void recordsOutcomeForCbrCachedClassifications() {
        var recordingStore = new RecordingCbrStore();
        SituationClassifier classifierWithCaseId = (desc, meta, ctx) ->
            new ClassificationResult(
                List.of(new SituationActivation("reward", 0.8)),
                "case-abc123");

        var outcomePhase = new BehavioralSynthesisPhase(
            store, capsEngine, classifierWithCaseId, 20, recordingStore);

        store.addNode(NodeInput.of("Rewarded for helping", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.7")),
            TENANT);

        outcomePhase.run(TENANT, List.of());

        assertThat(recordingStore.recordedOutcomes).hasSize(1);
        assertThat(recordingStore.recordedOutcomes.getFirst().caseId).isEqualTo("case-abc123");
        assertThat(recordingStore.recordedOutcomes.getFirst().tenantId).isEqualTo(TENANT);
        assertThat(recordingStore.recordedOutcomes.getFirst().outcome.successRate()).isGreaterThan(0.0);
    }

    @Test
    void noOutcomeRecordedWhenNoCaseIds() {
        var recordingStore = new RecordingCbrStore();
        SituationClassifier classifierNoCaseId = (desc, meta, ctx) ->
            new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));

        var outcomePhase = new BehavioralSynthesisPhase(
            store, capsEngine, classifierNoCaseId, 20, recordingStore);

        store.addNode(NodeInput.of("Rewarded for helping", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.7")),
            TENANT);

        outcomePhase.run(TENANT, List.of());

        assertThat(recordingStore.recordedOutcomes).isEmpty();
    }

    record RecordedOutcome(String caseId, CbrOutcome outcome, String tenantId) {}

    static class RecordingCbrStore extends InMemoryCbrRecordStore {
        final List<RecordedOutcome> recordedOutcomes = new ArrayList<>();

        @Override
        public void recordOutcome(String caseId, CbrOutcome outcome, String tenantId) {
            recordedOutcomes.add(new RecordedOutcome(caseId, outcome, tenantId));
        }

        @Override
        public void recordOutcome(String caseId, CbrOutcome outcome, String tenantId, Double learningRateOverride) {
            recordedOutcomes.add(new RecordedOutcome(caseId, outcome, tenantId));
        }
    }

    static class StubCapsEngine implements CapsEngine {
        private final Map<String, AgentCapsState> states = new ConcurrentHashMap<>();

        @Override
        public CapsTopology topology() { return null; }

        @Override
        public AgentCapsState loadState(String tenantId, String agentId) {
            return states.get(tenantId + "/" + agentId);
        }

        @Override
        public void saveState(AgentCapsState state) {
            states.put(state.tenantId() + "/" + state.agentId(), state);
        }

        @Override
        public AgentCapsState initializeAgent(String tenantId, String agentId,
                                               DispositionAxes disposition) {
            Map<String, ConnectionWeight> weights = new HashMap<>();
            weights.put("reward__BAS_activation",
                new ConnectionWeight(0.5, 0.0, 1.0, 1.0));
            AgentCapsState state = new AgentCapsState(agentId, tenantId, 0,
                weights, Map.of());
            saveState(state);
            return state;
        }

        @Override
        public SettlingResult settle(AgentCapsState state,
                                     Map<String, Double> inputActivations) {
            return new SettlingResult(
                inputActivations, 5, ConvergenceType.CONVERGED, 0.1,
                List.of(new CapsAttractor(
                    "approach_avoidance", "approach", 0.6,
                    false, state.generation())));
        }

        @Override
        public AgentCapsState updateWeights(AgentCapsState state, WeightUpdateContext context) {
            return new AgentCapsState(state.agentId(), state.tenantId(),
                state.generation() + 1, state.weights(), state.nodeStates());
        }
    }

    record WeightUpdate(Map<String, Double> activations, double intensity, double valence, double salienceMultiplier) {}

    static class EmotionAwareStubCapsEngine extends StubCapsEngine {
        WeightUpdate lastEmotionUpdate;
        WeightUpdate lastRegularUpdate;

        @Override
        public CapsTopology topology() {
            return new CapsTopology(1, Map.of(), List.of(
                    new CapsConnection("c1", "punishment", "FFFS_activation", 0.5,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("threat")),
                    new CapsConnection("c2", "arousal", "BIS_activation", 0.5,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("bis")),
                    new CapsConnection("c3", "reward", "BAS_activation", 0.5,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("bas")),
                    new CapsConnection("c4", "powerlessness", "freeze", 0.3,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("compliance")),
                    new CapsConnection("c5", "powerlessness", "fawn", 0.3,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("fawn_accommodate")),
                    new CapsConnection("c6", "FFFS_activation", "fight", 0.4,
                                       WeightProvenance.EMPIRICAL, "caps-topology", List.of("fight_assert"))
                                                        ), Map.of(), List.of(), null);
        }

        @Override
        public AgentCapsState updateWeights(AgentCapsState state, WeightUpdateContext context) {
            var update = new WeightUpdate(context.inputActivations(), context.outcomeIntensity(),
                                          context.outcomeValence(), context.salienceMultiplier());
            if (context.salienceMultiplier() != 1.0) {
                lastEmotionUpdate = update;
            } else {
                lastRegularUpdate = update;
            }
            return super.updateWeights(state, context);
        }
    }

    static class CapturingCapsEngine extends StubCapsEngine {
        Map<String, Double> lastInputActivations;

        @Override
        public SettlingResult settle(AgentCapsState state,
                                     Map<String, Double> inputActivations) {
            lastInputActivations = new HashMap<>(inputActivations);
            return super.settle(state, inputActivations);
        }
    }

    @Test
    void classify_passesDispositionMetadata() {
        var capturedMetadata = new ArrayList<Map<String, String>>();
        SituationClassifier capturingClassifier = (desc, meta, ctx) -> {
            capturedMetadata.add(new HashMap<>(meta));
            return new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        };

        var axes = new DispositionAxes("competitive", "flexible", "bold", "high", "assertive");
        var descriptor = new DescriptorView(AGENT, axes, List.of(), List.of(), null, List.of());
        var defaults = new CognitiveDefaults(AGENT, null, null, null, null, null,
            null, null, null, null, null, Map.of(), null, null, descriptor,
            null, null, null, null, null, null, null);
        var registry = new CognitiveDefaultsRegistry();
        registry.register(defaults);

        var dispPhase = new BehavioralSynthesisPhase(
            store, capsEngine, capturingClassifier, 20, null, registry);

        store.addNode(NodeInput.of("Won a competition", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem1",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.7")),
            TENANT);

        dispPhase.run(TENANT, List.of());

        assertThat(capturedMetadata).isNotEmpty();
        var meta = capturedMetadata.getFirst();
        assertThat(meta).containsEntry("disposition.socialOrient", "competitive");
        assertThat(meta).containsEntry("disposition.ruleFollowing", "flexible");
        assertThat(meta).containsEntry("disposition.riskAppetite", "bold");
        assertThat(meta).containsEntry("disposition.autonomy", "high");
        assertThat(meta).containsEntry("disposition.conflictMode", "assertive");
    }

    @Test
    void classify_noRegistryOmitsDispositionMetadata() {
        var capturedMetadata = new ArrayList<Map<String, String>>();
        SituationClassifier capturingClassifier = (desc, meta, ctx) -> {
            capturedMetadata.add(new HashMap<>(meta));
            return new ClassificationResult(List.of(new SituationActivation("reward", 0.8)));
        };

        var noRegPhase = new BehavioralSynthesisPhase(
            store, capsEngine, capturingClassifier, 20);

        store.addNode(NodeInput.of("Got rewarded", cognitiveSubgraphId)
            .withProperties(Map.of(
                "source-memory-id", "mem2",
                "agent-id", AGENT,
                "event-type", "observation",
                "graduation-score", "0.7")),
            TENANT);

        noRegPhase.run(TENANT, List.of());

        assertThat(capturedMetadata).isNotEmpty();
        var meta = capturedMetadata.getFirst();
        assertThat(meta).doesNotContainKey("disposition.socialOrient");
    }

}
