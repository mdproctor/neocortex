package io.casehub.neocortex.caps.llm;

import io.casehub.neocortex.caps.CapsNode;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.NodeRange;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.WeightUpdateParameters;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import io.casehub.platform.agent.gate.AimdAdmissionStrategy;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.neocortex.caps.ClassificationContext;

import static org.assertj.core.api.Assertions.assertThat;

class LlmSituationClassifierTest {

    private static final CapsTopology TOPOLOGY = new CapsTopology(1,
        Map.of(
            "reward", new CapsNode("reward", NodeType.INPUT, NodeRange.UNIPOLAR,
                "consequence", List.of("operant"), Map.of("rewarded", 0.8, "praised", 0.5)),
            "punishment", new CapsNode("punishment", NodeType.INPUT, NodeRange.UNIPOLAR,
                "consequence", List.of("operant"), Map.of("punished", 0.9)),
            "rejection", new CapsNode("rejection", NodeType.INPUT, NodeRange.UNIPOLAR,
                "relationship", List.of("attachment"), Map.of("rejected", 0.9)),
            "approach", new CapsNode("approach", NodeType.OUTPUT, NodeRange.UNIPOLAR,
                "approach_avoidance", List.of(), Map.of())
        ),
        List.of(), Map.of(), List.of(),
        new WeightUpdateParameters(0.01, 0.25, 0.1, 0.5, 0.95,
            Map.of(), 0.2, 100, 0.001,
            new double[]{-2.0, 2.0}, new double[]{0.1, 3.0}, 0.6));

    @Test
    void classify_usesPipelineOpenAndRelease() {
        var pipeline = stubPipeline("""
                            {"activations": [{"node": "reward", "confidence": 0.8}]}
                            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        var result = classifier.classify("Got rewarded", Map.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().nodeId()).isEqualTo("reward");

        // session released — second call succeeds
        var result2 = classifier.classify("Got rewarded again", Map.of());
        assertThat(result2).hasSize(1);
    }

    @Test
    void classify_releasesPipelineOnParseFailure() {
        var pipeline   = stubPipeline("garbled response no json");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        // garbled response → empty list (no valid JSON activations)
        var result = classifier.classify("test", Map.of());
        assertThat(result).isEmpty();

        // session released — second call succeeds
        var result2 = classifier.classify("test again", Map.of());
        assertThat(result2).isEmpty();
    }

    @Test
    void parsesValidResponse() {
        var pipeline = stubPipeline("""
            {"activations": [{"node": "reward", "confidence": 0.8}, {"node": "rejection", "confidence": 0.5}]}
            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify(
            "Was rewarded and then rejected", Map.of());

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(a -> {
            assertThat(a.nodeId()).isEqualTo("reward");
            assertThat(a.confidence()).isEqualTo(0.8);
        });
        assertThat(result).anySatisfy(a -> {
            assertThat(a.nodeId()).isEqualTo("rejection");
            assertThat(a.confidence()).isEqualTo(0.5);
        });
    }

    @Test
    void filtersInvalidNodeIds() {
        var pipeline = stubPipeline("""
            {"activations": [{"node": "reward", "confidence": 0.8}, {"node": "nonexistent", "confidence": 0.9}]}
            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify("test", Map.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().nodeId()).isEqualTo("reward");
    }

    @Test
    void filtersOutputNodes() {
        var pipeline = stubPipeline("""
            {"activations": [{"node": "approach", "confidence": 0.9}]}
            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify("test", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void filtersBelowThreshold() {
        var pipeline = stubPipeline("""
            {"activations": [{"node": "reward", "confidence": 0.1}]}
            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify("test", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void handlesMarkdownWrappedJson() {
        var pipeline = stubPipeline("""
            ```json
            {"activations": [{"node": "reward", "confidence": 0.7}]}
            ```
            """);
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify("test", Map.of());

        assertThat(result).hasSize(1);
    }

    @Test
    void handlesGarbledResponse() {
        var pipeline = stubPipeline("I think the situation involves reward");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        List<SituationActivation> result = classifier.classify("test", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForNullDescription() {
        var pipeline = stubPipeline("{}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        assertThat(classifier.classify(null, Map.of())).isEmpty();
        assertThat(classifier.classify("", Map.of())).isEmpty();
    }

    @Test
    void extractJsonHandlesEdgeCases() {
        assertThat(LlmSituationClassifier.extractJson(null)).isNull();
        assertThat(LlmSituationClassifier.extractJson("")).isNull();
        assertThat(LlmSituationClassifier.extractJson("no json here")).isNull();
        assertThat(LlmSituationClassifier.extractJson("prefix {\"a\": 1} suffix"))
            .isEqualTo("{\"a\": 1}");
    }

    @Test
    void classify_pathA_appendsDispositionToUserPrompt() {
        var capturedPrompts = new ArrayList<String>();
        var pipeline = capturingPipeline(capturedPrompts,
            "{\"activations\": [{\"node\": \"reward\", \"confidence\": 0.8}]}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        var metadata = new HashMap<String, String>();
        metadata.put("disposition.socialOrient", "competitive");
        metadata.put("disposition.ruleFollowing", "flexible");
        metadata.put("disposition.riskAppetite", "bold");
        metadata.put("disposition.autonomy", "high");
        metadata.put("disposition.conflictMode", "assertive");

        classifier.classify("Got rewarded", metadata,
            ClassificationContext.of("t1"));

        assertThat(capturedPrompts).hasSize(1);
        assertThat(capturedPrompts.getFirst()).contains("competitive");
        assertThat(capturedPrompts.getFirst()).contains("Classify the situation as this character");
    }

    @Test
    void classify_pathC_usesPersonalitySystemPrompt() {
        var capturedInits = new ArrayList<AgentSessionInit>();
        var pipeline = capturingInitPipeline(capturedInits,
            "{\"activations\": [{\"node\": \"reward\", \"confidence\": 0.7}]}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        var metadata = new HashMap<String, String>();
        metadata.put("disposition.riskAppetite", "bold");
        metadata.put("disposition.ruleFollowing", "strict");

        classifier.classify("Ambiguous situation", metadata,
            ClassificationContext.of("t1"));

        assertThat(capturedInits).hasSize(1);
        assertThat(capturedInits.getFirst().systemPrompt())
            .contains("through a specific character's lens");
    }

    @Test
    void classify_pathC_alsoAppendsDispositionToUserPrompt() {
        var capturedPrompts = new ArrayList<String>();
        var pipeline = capturingPipeline(capturedPrompts,
            "{\"activations\": [{\"node\": \"reward\", \"confidence\": 0.7}]}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        var metadata = new HashMap<String, String>();
        metadata.put("disposition.riskAppetite", "bold");
        metadata.put("disposition.ruleFollowing", "strict");

        classifier.classify("Ambiguous situation", metadata,
            ClassificationContext.of("t1"));

        assertThat(capturedPrompts).hasSize(1);
        assertThat(capturedPrompts.getFirst()).contains("bold");
        assertThat(capturedPrompts.getFirst()).contains("Classify the situation as this character");
    }

    @Test
    void classify_noDisposition_usesDefaultPrompt() {
        var capturedPrompts = new ArrayList<String>();
        var pipeline = capturingPipeline(capturedPrompts,
            "{\"activations\": [{\"node\": \"reward\", \"confidence\": 0.8}]}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        classifier.classify("Got rewarded", Map.of(),
            ClassificationContext.of("t1"));

        assertThat(capturedPrompts).hasSize(1);
        assertThat(capturedPrompts.getFirst()).doesNotContain("Character disposition");
    }

    @Test
    void classify_noDisposition_usesDefaultSystemPrompt() {
        var capturedInits = new ArrayList<AgentSessionInit>();
        var pipeline = capturingInitPipeline(capturedInits,
            "{\"activations\": [{\"node\": \"reward\", \"confidence\": 0.8}]}");
        var classifier = new LlmSituationClassifier(pipeline, TOPOLOGY);

        classifier.classify("Got rewarded", Map.of(),
            ClassificationContext.of("t1"));

        assertThat(capturedInits).hasSize(1);
        assertThat(capturedInits.getFirst().systemPrompt())
            .doesNotContain("through a specific character's lens");
        assertThat(capturedInits.getFirst().systemPrompt())
            .contains("psychological situation classifier");
    }

    private static SessionPipeline capturingPipeline(List<String> capturedPrompts, String response) {
        return buildPipeline(response, null, capturedPrompts);
    }

    private static SessionPipeline capturingInitPipeline(List<AgentSessionInit> capturedInits, String response) {
        return buildPipeline(response, capturedInits, null);
    }

    private static SessionPipeline stubPipeline(String response) {
        return buildPipeline(response, null, null);
    }

    private static SessionPipeline buildPipeline(String response,
                                                  List<AgentSessionInit> capturedInits,
                                                  List<String> capturedPrompts) {
        AgentBackend backend = new AgentBackend() {
            @Override public String key() { return "stub"; }
            @Override public AgentSession openSession(AgentSessionInit init) {
                if (capturedInits != null) capturedInits.add(init);
                return new AgentSession() {
                    @Override public Multi<AgentEvent> query(String prompt) {
                        if (capturedPrompts != null) capturedPrompts.add(prompt);
                        return Multi.createFrom().items(
                            (AgentEvent) new AgentEvent.TextDelta(response),
                            new AgentEvent.InvocationComplete(0, 0, 0, 0, 0, null, 0, 0, null, 1, false, null, null));
                    }
                    @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
                    @Override public void close(Duration timeout) {}
                    @Override public void clear() {}
                };
            }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                throw new UnsupportedOperationException();
            }
        };
        BackendInstanceRegistry registry = new BackendInstanceRegistry() {
            @Override public void register(AgentBackend b) {}
            @Override public Optional<AgentBackend> resolve(String key, String instanceId) { return Optional.of(backend); }
            @Override public List<AgentBackend> resolveByKey(String key) { return List.of(backend); }
        };
        var pool = new PoolDeclaration("test", "test", "stub", 0, 20, null, Map.of(), Map.of());
        var poolRegistry = new SessionPoolRegistry(List.of(pool), registry);
        var lifecycleManager = new SessionLifecycleManager(poolRegistry);
        return new SessionPipeline(lifecycleManager, "stub",
                new AimdAdmissionStrategy(6, 5, Duration.ofSeconds(10)), 50_000);
    }
}
