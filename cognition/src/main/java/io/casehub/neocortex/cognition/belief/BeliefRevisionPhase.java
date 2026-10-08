package io.casehub.neocortex.cognition.belief;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationPhase;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.api.identity.PrincipalId;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
@Priority(14)
public class BeliefRevisionPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(BeliefRevisionPhase.class.getName());
    private static final String CURSOR_NODE_NAME = "belief-revision-cursor";
    private static final String BELIEF_TRAIT = "Belieflike";
    private static final String EXPERIENCE_PROVENANCE = "experience-consolidation";
    private static final String REVISION_PROVENANCE = "belief-revision";

    private static final String SYSTEM_PROMPT = """
            You are analyzing a character's beliefs against recent evidence from their experiences.
            For each belief that is contradicted by the evidence, identify the contradiction.
            Respond with JSON only. If no contradictions are found, return {"contradictions": []}.
            """;

    private final MindMapStore mindMapStore;
    private final @Nullable AgentProvider agentProvider;
    private final BeliefRevisionConfig config;

    @Inject
    public BeliefRevisionPhase(MindMapStore mindMapStore,
                               Instance<AgentProvider> agentProvider,
                               Instance<BeliefRevisionConfig> config) {
        this(mindMapStore,
             agentProvider.isResolvable() ? agentProvider.get() : null,
             config.isResolvable() ? config.get() : BeliefRevisionConfig.defaults());
    }

    BeliefRevisionPhase(MindMapStore mindMapStore, @Nullable AgentProvider agentProvider,
                        BeliefRevisionConfig config) {
        this.mindMapStore = mindMapStore;
        this.agentProvider = agentProvider;
        this.config = config;
    }

    @Override
    public String name() {
        return "belief-revision";
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        if (agentProvider == null) return;
        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var cognitiveSubgraphs = subgraphs.stream()
            .filter(sg -> "cognitive".equals(sg.type()))
            .toList();
        if (cognitiveSubgraphs.isEmpty()) return;

        var allBeliefs = new HashMap<String, List<MindMapNode>>();
        var allEvidence = new HashMap<String, List<MindMapNode>>();
        String cursorSubgraphId = null;
        String cursorNodeId = null;
        Instant lastProcessedAt = null;

        for (var sg : cognitiveSubgraphs) {
            var nodes = mindMapStore.nodesIn(sg.id(), tenantId);
            for (var node : nodes) {
                if (CURSOR_NODE_NAME.equals(node.name())) {
                    cursorSubgraphId = sg.id();
                    cursorNodeId = node.id();
                    var tsStr = node.properties().get("last-processed-timestamp");
                    if (tsStr != null) {
                        lastProcessedAt = Instant.parse(tsStr);
                    }
                    continue;
                }
                if (node.traits().contains(BELIEF_TRAIT)) {
                    var agentId = node.principalId() != null ? node.principalId().id() : null;
                    if (agentId != null) {
                        allBeliefs.computeIfAbsent(agentId, k -> new ArrayList<>()).add(node);
                    }
                } else if (EXPERIENCE_PROVENANCE.equals(node.provenance())) {
                    var agentId = node.properties().get("agent-id");
                    if (agentId != null) {
                        allEvidence.computeIfAbsent(agentId, k -> new ArrayList<>()).add(node);
                    }
                }
            }
        }

        if (allBeliefs.isEmpty()) return;

        Instant latestTimestamp = lastProcessedAt;
        for (var agentId : allBeliefs.keySet()) {
            var beliefs = allBeliefs.get(agentId);
            var evidence = allEvidence.getOrDefault(agentId, List.of());
            var newEvidence = filterNewEvidence(evidence, lastProcessedAt);
            if (newEvidence.isEmpty()) continue;

            processAgent(agentId, beliefs, newEvidence, tenantId);

            for (var ev : newEvidence) {
                if (ev.createdAt() != null && (latestTimestamp == null || ev.createdAt().isAfter(latestTimestamp))) {
                    latestTimestamp = ev.createdAt();
                }
            }
        }

        if (latestTimestamp != null && !latestTimestamp.equals(lastProcessedAt)) {
            saveCursor(cognitiveSubgraphs.get(0).id(), cursorSubgraphId,
                       cursorNodeId, latestTimestamp, tenantId);
        }
    }

    private List<MindMapNode> filterNewEvidence(List<MindMapNode> evidence, Instant lastProcessedAt) {
        if (lastProcessedAt == null) return evidence;
        return evidence.stream()
            .filter(n -> n.createdAt() != null && n.createdAt().isAfter(lastProcessedAt))
            .toList();
    }

    private boolean processAgent(String agentId, List<MindMapNode> beliefs,
                                 List<MindMapNode> evidence, String tenantId) {
        try {
            var contradictions = detectContradictions(agentId, beliefs, evidence);
            if (contradictions.isEmpty()) return false;

            for (var c : contradictions) {
                var beliefNode = beliefs.stream()
                    .filter(b -> b.id().equals(c.beliefNodeId) || b.name().equals(c.beliefText))
                    .findFirst().orElse(null);
                if (beliefNode == null) continue;

                double currentConfidence = beliefNode.confidence().value();
                double effectiveDecay = config.beliefDecayPerContradiction() * c.contradictionStrength;
                double newConfidence = Math.max(0.0, currentConfidence - effectiveDecay);
                if (newConfidence < config.beliefSupersessionThreshold()) {
                    var subjectKey = beliefNode.property("subject").orElse(beliefNode.name());
                    var newNodeId = mindMapStore.addNode(
                        NodeInput.of(c.revisedBelief, beliefNode.subgraphId())
                            .withConfidence(Confidence.inferred(
                                config.revisedBeliefInitialConfidence(), Instant.now()))
                            .withProvenance(REVISION_PROVENANCE)
                            .withTraits(Set.of(BELIEF_TRAIT))
                            .withProperties(Map.of("subject", subjectKey))
                            .withPrincipalId(PrincipalId.agent(agentId)),
                        tenantId);
                    mindMapStore.supersede(beliefNode.id(), newNodeId, c.reasoning, tenantId);
                } else {
                    mindMapStore.updateNode(beliefNode.id(),
                        NodeUpdate.empty().withConfidence(
                            new Confidence(ConfidenceOrigin.INFERRED, newConfidence, Instant.now())),
                        tenantId);
                }
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, agentId + ": belief revision failed (non-fatal)", e);
            return false;
        }
    }

    record Contradiction(String beliefNodeId, String beliefText,
                         String contradictingEvidence, String reasoning,
                         double contradictionStrength, String revisedBelief) {}

    private List<Contradiction> detectContradictions(String agentId,
            List<MindMapNode> beliefs, List<MindMapNode> evidence) {
        var beliefsText = new StringBuilder();
        for (int i = 0; i < beliefs.size(); i++) {
            var b = beliefs.get(i);
            beliefsText.append(String.format("%d. [%s] \"%s\" (confidence: %.2f)\n",
                i + 1, b.id(), b.name(), b.confidence().value()));
        }

        var evidenceText = new StringBuilder();
        for (var e : evidence) {
            var eventType = e.properties().getOrDefault("event-type", "unknown");
            evidenceText.append(String.format("- \"%s\" (event-type: %s)\n", e.name(), eventType));
        }

        var userPrompt = String.format("""
                Character: %s

                Current beliefs:
                %s
                Recent evidence:
                %s
                For each belief contradicted by this evidence, provide JSON:
                - beliefNodeId: the ID in brackets above
                - beliefText: the belief text
                - contradictingEvidence: which evidence contradicts it
                - reasoning: why it's a contradiction
                - contradictionStrength: 0.0 (barely relevant) to 1.0 (directly disproven)
                - revisedBelief: what the character should now believe (one sentence, their perspective)""",
                agentId, beliefsText, evidenceText);

        var sessionConfig = AgentSessionConfig.of(SYSTEM_PROMPT, userPrompt);
        var responseText = new StringBuilder();
        agentProvider.invoke(sessionConfig).subscribe().asStream()
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .map(e -> ((AgentEvent.TextDelta) e).text())
            .forEach(responseText::append);

        return parseContradictions(responseText.toString());
    }

    private List<Contradiction> parseContradictions(String json) {
        json = json.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").strip();
        }
        var results = new ArrayList<Contradiction>();
        int searchFrom = 0;
        while (true) {
            int objStart = json.indexOf('{', searchFrom);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            searchFrom = objEnd + 1;

            if (!obj.contains("beliefNodeId")) continue;

            var nodeId = extractField(obj, "beliefNodeId");
            var text = extractField(obj, "beliefText");
            var evidence = extractField(obj, "contradictingEvidence");
            var reasoning = extractField(obj, "reasoning");
            var strength = extractDoubleField(obj, "contradictionStrength");
            var revised = extractField(obj, "revisedBelief");

            if (nodeId != null && text != null && strength > 0) {
                results.add(new Contradiction(nodeId, text, evidence,
                    reasoning != null ? reasoning : "LLM-detected contradiction",
                    strength, revised != null ? revised : text + " (revised)"));
            }
        }
        return results;
    }

    private static String extractField(String json, String field) {
        int start = json.indexOf("\"" + field + "\"");
        if (start < 0) return null;
        int colon = json.indexOf(':', start);
        if (colon < 0) return null;
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return null;
        quote++;
        var sb = new StringBuilder();
        for (int i = quote; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static double extractDoubleField(String json, String field) {
        int start = json.indexOf("\"" + field + "\"");
        if (start < 0) return 0.0;
        int colon = json.indexOf(':', start);
        if (colon < 0) return 0.0;
        var numStr = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']') break;
            if (!Character.isWhitespace(c)) numStr.append(c);
        }
        try {
            return Double.parseDouble(numStr.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void saveCursor(String defaultSubgraphId, String cursorSubgraphId,
                            String cursorNodeId, Instant lastTimestamp, String tenantId) {
        String sgId = cursorSubgraphId != null ? cursorSubgraphId : defaultSubgraphId;
        if (cursorNodeId != null) {
            mindMapStore.updateNode(cursorNodeId,
                NodeUpdate.empty().withPropertiesToSet(
                    Map.of("last-processed-timestamp", lastTimestamp.toString())),
                tenantId);
        } else {
            mindMapStore.addNode(
                NodeInput.of(CURSOR_NODE_NAME, sgId)
                    .withProvenance("belief-revision-cursor")
                    .withProperties(Map.of(
                        "cognitiveKind", "cursor",
                        "last-processed-timestamp", lastTimestamp.toString())),
                tenantId);
        }
    }
}
