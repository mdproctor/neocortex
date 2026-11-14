package io.casehub.neocortex.caps.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.ClassificationResult;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class LlmSituationClassifier implements SituationClassifier {

    private static final Logger LOG = Logger.getLogger(LlmSituationClassifier.class.getName());
    private static final double CONFIDENCE_THRESHOLD = 0.3;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private static final double TENSION_THRESHOLD = 0.5;

    private final SessionPipeline pipeline;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String systemPrompt;
    private final CapsTopology topology;
    private final Set<String> validNodeIds;

    public LlmSituationClassifier(SessionPipeline pipeline, CapsTopology topology) {
        this(pipeline, topology, "haiku");
    }

    public LlmSituationClassifier(SessionPipeline pipeline, CapsTopology topology, String model) {
        this.pipeline = pipeline;
        this.model = model;
        this.topology = topology;
        this.validNodeIds = topology.nodes().entrySet().stream()
            .filter(e -> e.getValue().type() == NodeType.INPUT)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        this.systemPrompt = buildSystemPrompt(topology);
    }

    @Override
    public ClassificationResult classify(String description,
                                          Map<String, String> metadata,
                                          ClassificationContext context) {
        if (description == null || description.isBlank()) {
            return new ClassificationResult(List.of());
        }

        boolean hasPersonality = PersonalityPromptBuilder.hasDisposition(metadata);
        double tension = hasPersonality ? DispositionTension.maxTension(metadata) : 0.0;
        boolean pathC = tension > TENSION_THRESHOLD;

        var sessionId = context.agentId() != null
                ? "classifier-" + context.agentId()
                : "classifier-" + java.util.UUID.randomUUID();

        AgentSessionInit init;
        if (pathC) {
            var tensionEntry = DispositionTension.topTension(metadata).orElseThrow();
            String personalitySystem = PersonalityPromptBuilder.personalitySystemPrompt(
                metadata, tensionEntry, topology);
            init = AgentSessionInit.of(personalitySystem).withModel(model);
        } else {
            init = AgentSessionInit.of(systemPrompt).withModel(model);
        }

        var session = pipeline.openSession(sessionId, init);
        try {
            String userPrompt = buildUserPrompt(description, metadata);
            if (hasPersonality) {
                userPrompt += PersonalityPromptBuilder.userPromptSuffix(metadata);
            }
            var events = session.query(userPrompt)
                .collect().asList()
                .await().atMost(TIMEOUT);

            String text = events.stream()
                .filter(AgentEvent.TextDelta.class::isInstance)
                .map(AgentEvent.TextDelta.class::cast)
                .map(AgentEvent.TextDelta::text)
                .collect(Collectors.joining());

            return new ClassificationResult(parseResponse(text));
        } catch (Exception e) {
            throw new ClassificationException(
                "LLM situation classification failed for: " +
                description.substring(0, Math.min(80, description.length())), e);
        }
    }

    List<SituationActivation> parseResponse(String text) {
        String json = extractJson(text);
        if (json == null) return List.of();

        try {
            JsonNode root = mapper.readTree(json);
            JsonNode activationsNode = root.path("activations");
            if (!activationsNode.isArray()) return List.of();

            List<SituationActivation> result = new ArrayList<>();
            for (JsonNode entry : activationsNode) {
                String nodeId = entry.path("node").asText(null);
                double confidence = entry.path("confidence").asDouble(0.0);

                if (nodeId == null || !validNodeIds.contains(nodeId)) continue;
                if (confidence < CONFIDENCE_THRESHOLD) continue;

                result.add(new SituationActivation(nodeId, Math.min(1.0, confidence)));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to parse LLM classifier response: " + e.getMessage());
            return List.of();
        }
    }

    private String buildUserPrompt(String description, Map<String, String> metadata) {
        var sb = new StringBuilder("Description: ").append(description);
        if (metadata != null && !metadata.isEmpty()) {
            var filtered = metadata.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("disposition."))
                .toList();
            if (!filtered.isEmpty()) {
                sb.append("\n\nMetadata:");
                filtered.forEach(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String buildSystemPrompt(CapsTopology topology) {
        var sb = new StringBuilder("""
                                   You are a psychological situation classifier. Given a description of \
                                   an experience, classify it into one or more CAPS input node activations.

                                   Respond with a JSON object:
                                   {"activations": [{"node": "node_id", "confidence": 0.0-1.0}]}

                                   Rules:
                                   - Only use node IDs from the list below
                                   - confidence reflects how strongly the description matches the node
                                   - Multiple nodes can activate for the same description
                                   - Minimum confidence threshold is 0.3
                                   - Respond with valid JSON only

                                   Available input nodes:
                                   """);

        PersonalityPromptBuilder.appendTopologyNodes(sb, topology);

        return sb.toString();
    }

    static String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }

}
