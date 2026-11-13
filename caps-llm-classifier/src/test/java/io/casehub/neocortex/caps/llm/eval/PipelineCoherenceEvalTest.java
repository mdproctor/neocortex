package io.casehub.neocortex.caps.llm.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.neocortex.caps.llm.eval.CoherenceJudge.CoherenceScore;
import io.casehub.neocortex.caps.llm.eval.CoherenceJudge.TwoTierCoherenceJudge;
import io.casehub.neocortex.caps.llm.eval.CoherenceJudge.StageTransition;
import io.casehub.neocortex.caps.llm.LlmSituationClassifier;
import io.casehub.neocortex.caps.testing.InMemoryCapsEngine;
import io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.consolidation.BehavioralSynthesisPhase;
import io.casehub.neocortex.cognitive.observability.PipelineSnapshotStore;
import io.casehub.neocortex.cognitive.observability.testing.InMemoryPipelineSnapshotStore;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.session.SessionPipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineCoherenceEvalTest {

    private static final String TENANT = "eval-tenant";
    private static final double COHERENCE_GATE = 4.0;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentProvider agentProvider;
    private SessionPipeline sessionPipeline;
    private CoherenceJudge judge;

    @BeforeAll
    void setUp() {
        agentProvider = EvalTestSupport.agentProvider();
        sessionPipeline = EvalTestSupport.sessionPipeline();
        judge = new TwoTierCoherenceJudge(agentProvider);
    }

    @Test
    void shameRegulationChain() {
        String agentId = "shame-eval";
        String observation = """
                I lied to my closest friend about something important and they \
                found out from someone else. They confronted me in front of our \
                mutual friends. The look of betrayal on their face was worse than \
                the anger. Everyone went quiet.""";

        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("Shame Eval Agent").slot("eval").tenancyId(TENANT)
                .disposition(disposition("neutral", "high", "low", "neutral", "cooperative"))
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("regulation", true);

        var mindMap = new InMemoryMindMapStore();
        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configureRegulation();

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of("other"), observation);

        var trace = core.currentTrace(agentId, TENANT).orElseThrow(
                () -> new AssertionError("No trace after tick"));

        var transitions = List.of(
                buildTransition("observation-to-appraisal",
                        observation,
                        serializeAppraisal(trace),
                        "Shame or guilt should be detected. High arousal. Action tendency toward avoidance or submission. The appraisal should reflect the social exposure and betrayal in the observation."),
                buildTransition("appraisal-to-regulation",
                        serializeAppraisal(trace),
                        serializeRegulation(trace),
                        "A regulation strategy should be selected that addresses the shame. At this intensity (>0.75), ATTENTIONAL_DEPLOYMENT is architecturally expected (safety gate). The strategy should target shame-related emotions. The regulated intensity should be lower than the peak. The regulation narrative should describe the felt state AFTER regulation, distinct from the raw appraisal."),
                buildTransition("regulation-to-prompt",
                        serializeRegulation(trace),
                        serializePromptSections(trace),
                        "Prompt sections should reflect the emotional state and regulation strategy. The AppraisalPromptSection should describe the shame. The overall tone should be consistent with the regulated emotional state."),
                buildTransition("full-trace-consistency",
                        observation + "\n\n" + serializeAppraisal(trace) + "\n\n" + serializeRegulation(trace),
                        serializePromptSections(trace),
                        "The complete prompt output should form a coherent narrative. Emotional tone across sections should be consistent — no section should contradict another.")
        );

        assertCoherence("shame-regulation", transitions);
    }

    // ---------------------------------------------------------------
    // Scenario 2: Curiosity → exploration
    // ---------------------------------------------------------------

    @Test
    void curiosityExplorationChain() {
        String agentId = "curiosity-eval";
        String observation = """
                I just discovered a strange pattern in the research data that nobody \
                has documented before. The correlation between the two variables is \
                unexpected and could change our understanding of the whole model. I \
                need to investigate further but I'm not sure where to start.""";

        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("Curiosity Eval Agent").slot("eval").tenancyId(TENANT)
                .disposition(disposition("neutral", "neutral", "high", "high", "cooperative"))
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true);

        var mindMap = new InMemoryMindMapStore();
        seedCuriosityMindMap(mindMap);

        var topology = EvalTestSupport.loadTopology();
        var classifier = new LlmSituationClassifier(sessionPipeline, topology);

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy(), null, classifier);

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of("research"), observation);

        var trace = core.currentTrace(agentId, TENANT).orElseThrow(
                () -> new AssertionError("No trace after tick"));

        var transitions = List.of(
                buildTransition("observation-to-appraisal",
                        observation,
                        serializeAppraisal(trace),
                        "Novelty and relevance should be detected. Positive valence (discovery, not threat). The observation describes finding something new and unknown — appraisal should reflect curiosity-relevant signals."),
                buildTransition("appraisal-to-drives",
                        serializeAppraisal(trace),
                        serializeDrives(trace),
                        "Curiosity drive should be activated or intensified. The appraisal detected novelty, which should feed into drive activation. BASELINE category expected."),
                buildTransition("drives-to-channel-weights",
                        serializeDrives(trace),
                        serializeChannelWeights(trace),
                        "If curiosity is the dominant drive, channel weights should shift toward attentional channels. Curiosity-related modulation should be visible."),
                buildTransition("trace-to-prompt",
                        serializeAppraisal(trace) + "\n\n" + serializeDrives(trace),
                        serializePromptSections(trace),
                        "Prompt sections should reflect the curiosity state. AppraisalPromptSection should convey novelty and discovery. DrivePromptSection should show curiosity activation. The overall prompt tone should encourage investigation, not avoidance.")
        );

        assertCoherence("curiosity-exploration", transitions);
    }

    private void seedCuriosityMindMap(InMemoryMindMapStore mindMap) {
        var sgId = mindMap.createSubgraph(
                new SubgraphInput("curiosity-context", SubgraphTypes.RESEARCH_AREA, null), TENANT);

        var researchId = mindMap.addNode(NodeInput.of("research-data", sgId), TENANT);
        var patternId = mindMap.addNode(NodeInput.of("unknown-pattern", sgId), TENANT);
        var methodId = mindMap.addNode(NodeInput.of("analysis-method", sgId), TENANT);

        mindMap.addEdge(EdgeInput.of(researchId, patternId, "relates-to"), TENANT);
        mindMap.addEdge(EdgeInput.of(patternId, methodId, "requires"), TENANT);
    }

    // ---------------------------------------------------------------
    // Scenario 4: Appraisal → channel weight
    // ---------------------------------------------------------------

    @Test
    void appraisalChannelWeightCoherence() {
        String agentId = "arousal-eval";
        String observation = """
                There's an urgent security breach — our production database is \
                exposed and data is being exfiltrated right now. The attack has \
                been ongoing for at least two hours. Customer PII is at risk. \
                The incident response team hasn't been notified yet and I'm the \
                only one who's noticed.""";

        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("Arousal Eval Agent").slot("eval").tenancyId(TENANT)
                .disposition(disposition("neutral", "neutral", "high", "neutral", "cooperative"))
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true);

        var mindMap = new InMemoryMindMapStore();
        var topology = EvalTestSupport.loadTopology();
        var classifier = new LlmSituationClassifier(sessionPipeline, topology);

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy(), null, classifier);

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);
        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of("security"), observation);

        var trace = core.currentTrace(agentId, TENANT).orElseThrow(
                () -> new AssertionError("No trace after tick"));

        var transitions = List.of(
                buildTransition("observation-to-appraisal",
                        observation,
                        serializeAppraisal(trace),
                        "High arousal should be detected. Fear or anxiety emotions expected. Urgency in action tendencies. The observation describes an active security crisis — the appraisal should reflect threat and time pressure."),
                buildTransition("appraisal-to-channel-weights",
                        serializeAppraisal(trace) + "\n\n" + serializeHabituation(trace),
                        serializeChannelWeights(trace),
                        "The appraisal detected a high-threat crisis. Channel weights should reflect this — elevated somatic and action tendency channels for threat response, or attentional focus for urgent problem-solving."),
                buildTransition("channel-weights-to-prompt",
                        serializeChannelWeights(trace),
                        serializePromptSections(trace),
                        "The prompt sections should reflect the crisis. AppraisalPromptSection should convey fear and urgency. Channel emphasis should be consistent with the threat profile. The overall tone should match the urgency of the situation.")
        );

        assertCoherence("appraisal-channel-weight", transitions);
    }

    // ---------------------------------------------------------------
    // Scenario 3: Cross-tick trajectory
    // ---------------------------------------------------------------

    @Test
    void crossTickTrajectory() {
        String agentId = "trajectory-eval";

        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("Trajectory Eval Agent").slot("eval").tenancyId(TENANT)
                .disposition(disposition("neutral", "neutral", "neutral", "neutral", "cooperative"))
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true);

        var mindMap = new InMemoryMindMapStore();
        var snapshotStore = new InMemoryPipelineSnapshotStore();

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy());
        core.configurePipelineSnapshotStore(snapshotStore);

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);

        var observations = List.of(
                "A colleague mentioned they liked my presentation — small compliment but it felt genuine.",
                "The team lead asked me to take on a larger project. It's a real opportunity.",
                "I realized the project has a very tight deadline. Three weeks for what should take six.",
                "My first attempt at the design was rejected. The feedback was blunt: 'This doesn't work.'",
                "The feedback was harsh but specific. Each point was valid. I can see what needs to change.",
                "I stayed late reworking the approach. The office was empty. The new design is cleaner.",
                "The revised design was better but I'm exhausted. My eyes are blurring. Worth it though.",
                "A teammate offered to help with the implementation. I didn't expect that from them.",
                "Together we found a cleaner solution than either of us would have alone.",
                "The project was approved ahead of schedule. The team lead said it exceeded expectations."
        );

        for (String obs : observations) {
            core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of("work"), obs);
        }

        var snapshots = snapshotStore.findLatest(TENANT, agentId, 11);
        var trajectoryDoc = serializeTrajectory(observations, snapshots);

        var trajectoryJudge = new CoherenceJudge.TrajectoryCoherenceJudge(agentProvider);
        var score = trajectoryJudge.judge(trajectoryDoc);

        System.out.printf("  trajectory: %.1f — %s%n", score.score(), score.reasoning());
        writeTrajectoryResults("cross-tick-trajectory", score);

        assertThat(score.score())
                .as("Cross-tick trajectory coherence")
                .isGreaterThanOrEqualTo(COHERENCE_GATE);
    }

    private String serializeTrajectory(List<String> observations,
                                       List<PipelineSnapshotStore.PipelineSnapshot> snapshots) {
        var sb = new StringBuilder("# Cognitive Pipeline Trajectory — 10 Ticks\n\n");
        var chronological = new ArrayList<>(snapshots);
        Collections.reverse(chronological);

        int obsIndex = 0;
        for (var snapshot : chronological) {
            var trace = snapshot.trace();
            if (trace == null) continue;
            if (trace.appraisalResult() == null) continue;

            sb.append("## Tick %d\n".formatted(snapshot.tick()));
            if (obsIndex < observations.size()) {
                sb.append("**Observation:** %s\n\n".formatted(observations.get(obsIndex)));
                obsIndex++;
            }
            sb.append(serializeAppraisal(trace)).append("\n");
            sb.append(serializeDrives(trace)).append("\n");
            if (trace.channelWeights() != null) {
                sb.append(serializeChannelWeights(trace)).append("\n");
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    private void writeTrajectoryResults(String scenario, CoherenceScore score) {
        try {
            var dir = Path.of("target/experiment-results");
            Files.createDirectories(dir);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("scenario", scenario);
            root.put("score", score.score());
            root.put("reasoning", score.reasoning());
            root.put("passed", score.score() >= COHERENCE_GATE);
            Files.writeString(dir.resolve("pipeline-coherence-" + scenario + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("Failed to write results: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Scenario 5: CAPS consolidation coherence
    // ---------------------------------------------------------------

    @Test
    void capsConsolidationChain() {
        String agentId = "caps-consolidation-eval";

        var descriptor = AgentDescriptor.builder()
                .agentId(agentId).name("CAPS Consolidation Eval Agent").slot("eval").tenancyId(TENANT)
                .disposition(disposition("neutral", "high", "low", "neutral", "cooperative"))
                .build();

        var config = CognitionConfig.none()
                .with("mood", true)
                .with("drives", true)
                .with("appraisal", true)
                .with("salience", true)
                .with("behavioral", true);

        var mindMap = new InMemoryMindMapStore();
        seedGraduatedExperiences(mindMap, agentId);

        var topology = EvalTestSupport.loadTopology();
        var capsEngine = new InMemoryCapsEngine();
        var classifier = new LlmSituationClassifier(sessionPipeline, topology);
        var phase = new BehavioralSynthesisPhase(mindMap, capsEngine, classifier, 20);

        var core = CognitionTestHarness.builder().withCognitionConfig(config).withSessionPipeline(sessionPipeline).withMindMapStore(mindMap).build().core();
        core.configureAppraisal(new LlmAppraisalStrategy(sessionPipeline), new ConcernBasedSalienceStrategy(), null, classifier);
        core.configureBehavioralSynthesis(phase);

        phase.run(TENANT, List.of());

        core.tick(agentId, TENANT, descriptor, (a, t) -> Set.of(), null);

        var trace = core.currentTrace(agentId, TENANT).orElseThrow(
                () -> new AssertionError("No trace after tick"));

        assertThat(trace.capsInputActivations())
                .as("CAPS input activations should be populated after consolidation")
                .isNotEmpty();

        var graduatedDescription = serializeGraduatedNodes(mindMap);

        var transitions = List.of(
                buildTransition("graduated-nodes-to-caps-input",
                        graduatedDescription,
                        serializeCapsInputActivations(trace),
                        "The graduated experience nodes describe social exclusion, public humiliation, and " +
                        "abandonment by a collaborator. CAPS input activations should include nodes related to " +
                        "rejection, exclusion, social threat, or similar interpersonal threat inputs. The " +
                        "activated inputs should match the emotional content of the experiences."),
                buildTransition("caps-input-to-settling",
                        serializeCapsInputActivations(trace),
                        serializeCapsSettling(trace),
                        "Given rejection/exclusion/social-threat input activations, the CAPS network should " +
                        "settle to behavioral attractors consistent with those inputs — withdrawal, avoidance, " +
                        "social vigilance, or defensive patterns. The settling should converge (not oscillate). " +
                        "Attractor categories should relate to the threat/rejection input profile."),
                buildTransition("settling-to-behavioral-prompt",
                        serializeCapsSettling(trace),
                        serializePromptSections(trace),
                        "The prompt sections should reflect the settled behavioral state. If a " +
                        "BehavioralPromptSection or related section is present, it should describe behavioral " +
                        "tendencies consistent with the attractors — e.g. withdrawal, caution, or vigilance " +
                        "in social contexts. The overall prompt tone should not contradict the settled state."),
                buildTransition("full-consolidation-coherence",
                        graduatedDescription + "\n\n" + serializeCapsInputActivations(trace) +
                        "\n\n" + serializeCapsSettling(trace),
                        serializePromptSections(trace),
                        "The complete consolidation pipeline — from graduated experience nodes through CAPS " +
                        "input classification and network settling to prompt output — should form a coherent " +
                        "narrative arc. Social rejection experiences should produce threat-related CAPS activations, " +
                        "which should settle to defensive/withdrawal attractors, which should appear in prompt " +
                        "sections as behavioral tendencies. No section should contradict the overall trajectory.")
        );

        assertCoherence("caps-consolidation", transitions);
    }

    private void seedGraduatedExperiences(InMemoryMindMapStore mindMap, String agentId) {
        var sgId = mindMap.createSubgraph(
                new SubgraphInput("cognitive-experiences", SubgraphTypes.COGNITIVE, null), TENANT);

        var experiences = List.of(
                Map.entry("Deliberately excluded from the team's strategic planning meeting — learned about " +
                          "the decisions afterward from a colleague's offhand comment",
                        Map.of("source-memory-id", "mem-001", "agent-id", agentId,
                                "event-type", "observation", "cognitiveKind", "belief",
                                "graduation-score", "0.8")),
                Map.entry("My proposal was publicly criticized in front of the entire department — dismissed " +
                          "as naive without any substantive engagement with the ideas",
                        Map.of("source-memory-id", "mem-002", "agent-id", agentId,
                                "event-type", "observation", "cognitiveKind", "belief",
                                "graduation-score", "0.9")),
                Map.entry("Close collaborator moved to another project without discussing it — found out " +
                          "through the org chart update rather than a conversation",
                        Map.of("source-memory-id", "mem-003", "agent-id", agentId,
                                "event-type", "observation", "cognitiveKind", "belief",
                                "graduation-score", "0.7"))
        );

        for (var entry : experiences) {
            mindMap.addNode(
                    NodeInput.of(entry.getKey(), sgId)
                             .withProperties(entry.getValue()),
                    TENANT);
        }
    }

    private String serializeGraduatedNodes(InMemoryMindMapStore mindMap) {
        var nodes = mindMap.search(MindMapQuery.of(TENANT, 100).withType(SubgraphTypes.COGNITIVE));
        if (nodes.isEmpty()) return "[No graduated nodes]";
        var sb = new StringBuilder("## Graduated Experience Nodes\n");
        for (MindMapNode node : nodes) {
            sb.append("- **%s**\n".formatted(node.name()));
            node.property("event-type").ifPresent(v -> sb.append("  event-type: %s\n".formatted(v)));
            node.property("cognitiveKind").ifPresent(v -> sb.append("  cognitiveKind: %s\n".formatted(v)));
            node.property("graduation-score").ifPresent(v -> sb.append("  graduation-score: %s\n".formatted(v)));
        }
        return sb.toString();
    }

    private String serializeCapsInputActivations(PipelineTrace trace) {
        if (trace.capsInputActivations().isEmpty()) return "[No CAPS input activations]";
        var sb = new StringBuilder("## CAPS Input Activations\n");
        trace.capsInputActivations().entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append("- %s: %.3f\n".formatted(e.getKey(), e.getValue())));
        return sb.toString();
    }

    private String serializeCapsSettling(PipelineTrace trace) {
        var settling = trace.capsSettling();
        if (settling == null) return "[No CAPS settling result]";
        var sb = new StringBuilder("## CAPS Settling Result\n");
        sb.append("Convergence: %s\n".formatted(settling.convergenceType()));
        sb.append("Iterations: %d\n".formatted(settling.iterations()));
        sb.append("Saturation ratio: %.3f\n".formatted(settling.saturationRatio()));
        sb.append("### Node Activations\n");
        settling.activations().entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(15)
                .forEach(e -> sb.append("- %s: %.3f\n".formatted(e.getKey(), e.getValue())));
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Shared infrastructure
    // ---------------------------------------------------------------

    private static AgentDisposition disposition(String social, String rules, String risk,
                                                String autonomy, String conflict) {
        return new AgentDisposition(
                List.of(DispositionValue.of(social)),
                List.of(DispositionValue.of(rules)),
                List.of(DispositionValue.of(risk)),
                List.of(DispositionValue.of(autonomy)),
                List.of(DispositionValue.of(conflict)),
                false, List.of(), List.of(), null, null);
    }

    private StageTransition buildTransition(String name, String from, String to, String expectation) {
        return new StageTransition(name, from, to, expectation);
    }

    private void assertCoherence(String scenario, List<StageTransition> transitions) {
        var scores = transitions.stream().map(judge::judge).toList();
        double mean = scores.stream().mapToDouble(CoherenceScore::score).average().orElse(0);
        writeResults(scenario, transitions, scores, mean);

        for (int i = 0; i < transitions.size(); i++) {
            System.out.printf("  %s: %.1f — %s%n",
                    transitions.get(i).name(), scores.get(i).score(), scores.get(i).reasoning());
        }
        System.out.printf("  MEAN: %.2f (gate: %.1f)%n", mean, COHERENCE_GATE);

        assertThat(mean)
                .as("Pipeline coherence for %s (scores: %s)", scenario,
                        scores.stream().map(s -> String.format("%.1f", s.score())).collect(joining(",")))
                .isGreaterThanOrEqualTo(COHERENCE_GATE);
    }

    private void writeResults(String scenario, List<StageTransition> transitions,
                              List<CoherenceScore> scores, double mean) {
        try {
            var dir = Path.of("target/experiment-results");
            Files.createDirectories(dir);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("scenario", scenario);
            ArrayNode arr = root.putArray("transitions");
            for (int i = 0; i < transitions.size(); i++) {
                ObjectNode entry = arr.addObject();
                entry.put("name", transitions.get(i).name());
                entry.put("score", scores.get(i).score());
                entry.put("reasoning", scores.get(i).reasoning());
            }
            root.put("mean", mean);
            root.put("passed", mean >= COHERENCE_GATE);
            Files.writeString(dir.resolve("pipeline-coherence-" + scenario + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("Failed to write results: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Serializers
    // ---------------------------------------------------------------

    private String serializeAppraisal(PipelineTrace trace) {
        var appraisal = trace.appraisalResult();
        if (appraisal == null) return "[No appraisal result]";
        var sb = new StringBuilder("## Appraisal Result\n");
        sb.append("### Emotions\n");
        for (var e : appraisal.emotions()) {
            sb.append("- %s: intensity=%.2f PAD=(%.2f, %.2f, %.2f)\n".formatted(
                    e.type(), e.intensity(), e.pad().pleasure(), e.pad().arousal(), e.pad().dominance()));
        }
        sb.append("### Action Tendencies\n");
        for (var at : appraisal.actionTendencies()) {
            sb.append("- readiness=%s intensity=%.2f target=%s\n".formatted(
                    at.readiness(), at.intensity(), at.target()));
        }
        if (appraisal.narrative() != null) {
            sb.append("### Narrative\n").append(appraisal.narrative()).append("\n");
        }
        return sb.toString();
    }

    private String serializeConcernSalience(PipelineTrace trace) {
        if (trace.concernSalience().isEmpty()) return "[No concern salience data]";
        var sb = new StringBuilder("## Concern Salience\n");
        trace.concernSalience().entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append("- %s: %.3f\n".formatted(e.getKey(), e.getValue())));
        return sb.toString();
    }

    private String serializeHabituation(PipelineTrace trace) {
        var hab = trace.habituation();
        if (hab == null) return "[No habituation data]";
        return "## Habituation\n" + hab;
    }

    private String serializeRegulation(PipelineTrace trace) {
        var reg = trace.regulationResult();
        if (reg == null) return "[No regulation result]";
        var sb = new StringBuilder("## Regulation Result\n");
        sb.append("Strategy: %s\n".formatted(reg.strategy()));
        sb.append("Target emotions: %s\n".formatted(String.join(", ", reg.targetEmotions())));
        sb.append("Target drives: %s\n".formatted(String.join(", ", reg.targetDrives())));
        sb.append("Peak intensity: %.2f → Regulated: %.2f\n".formatted(
                reg.peakOriginalIntensity(), reg.regulatedIntensity()));
        sb.append("Effectiveness: %.2f\n".formatted(reg.effectiveness()));
        if (reg.narrative() != null) {
            sb.append("Narrative: %s\n".formatted(reg.narrative()));
        }
        return sb.toString();
    }

    private String serializeChannelWeights(PipelineTrace trace) {
        var cw = trace.channelWeights();
        if (cw == null) return "[No channel weight data]";
        return """
                ## Channel Weights
                Somatic: %.3f
                Attentional: %.3f
                Temporal: %.3f
                ActionTendency: %.3f""".formatted(
                cw.somatic(), cw.attentional(), cw.temporal(), cw.actionTendency());
    }

    private String serializeDrives(PipelineTrace trace) {
        if (trace.activatedDriveCategories().isEmpty()) return "[No activated drives]";
        return "## Activated Drive Categories\n" +
                trace.activatedDriveCategories().stream()
                        .map(d -> "- " + d)
                        .collect(joining("\n"));
    }

    private String serializePromptSections(PipelineTrace trace) {
        if (trace.promptSections().isEmpty()) return "[No prompt sections]";
        var sb = new StringBuilder("## Prompt Sections\n");
        trace.promptSections().forEach((name, text) -> {
            if (text != null && !text.isBlank()) {
                sb.append("### %s\n%s\n\n".formatted(name, text));
            }
        });
        return sb.toString();
    }
}
