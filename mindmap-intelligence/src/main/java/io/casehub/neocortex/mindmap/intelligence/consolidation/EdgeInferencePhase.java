package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.inference.DenseEmbedder;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapConfidenceDefaults;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.intelligence.ParsedRelationship;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.session.ParallelLlmExecutor;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@IfBuildProperty(name = "casehub.mindmap.consolidation.edge-inference.enabled",
                 stringValue = "true", enableIfMissing = false)
@Priority(22)
public class EdgeInferencePhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(EdgeInferencePhase.class.getName());
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile(
        "\"source\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"target\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]+)\"");

    static final String SYSTEM_PROMPT = """
        You are a knowledge graph edge inference agent. Given pairs of entities \
        and the context where they co-occur, determine if a relationship exists.

        For each pair with a relationship, produce an edge. Skip pairs with no relationship.

        Relationship vocabulary: child-of, sibling-of, parent-of, married-to, \
        treated-by, taught-by, championed, collaborated-with, pursues, enables, \
        blocks, requires, contributes-to, lives-at, located-in, at, inspired-by, \
        created-by, depicted-in, embodies, exemplifies, contradicts, influenced-by, \
        participated, preceded-by, concurrent-with, caused-by, resulted-in, motivated-by

        Respond with JSON only: \
        {"relationships": [{"source": "...", "target": "...", "type": "...", "confidence": "INFERRED"}]}""";

    final MindMapStore mindMapStore;
    final CaseMemoryStore memoryStore;
    final DenseEmbedder denseEmbedder;
    final int degreeThreshold;
    final int maxCandidates;
    final boolean embeddingEnabled;
    final double embeddingSimilarityThreshold;
    private volatile SessionPipeline sessionPipeline;

    @Inject
    public EdgeInferencePhase(MindMapStore mindMapStore,
                              Instance<CaseMemoryStore> memoryStoreInstance,
                              Instance<DenseEmbedder> denseEmbedderInstance,
                              Instance<EdgeInferenceConfig> configInstance) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStoreInstance.isResolvable() ? memoryStoreInstance.get() : null;
        this.denseEmbedder = denseEmbedderInstance.isResolvable() ? denseEmbedderInstance.get() : null;
        EdgeInferenceConfig c = configInstance.isResolvable() ? configInstance.get() : null;
        this.degreeThreshold = c != null ? c.degreeThreshold() : 1;
        this.maxCandidates = c != null ? c.maxCandidates() : 30;
        this.embeddingEnabled = c != null && c.embeddingCandidatesEnabled();
        this.embeddingSimilarityThreshold = c != null ? c.embeddingSimilarityThreshold() : 0.7;
    }

    EdgeInferencePhase(MindMapStore mindMapStore, CaseMemoryStore memoryStore,
                       int degreeThreshold, int maxCandidates) {
        this(mindMapStore, memoryStore, degreeThreshold, maxCandidates, null, false, 0.7);
    }

    EdgeInferencePhase(MindMapStore mindMapStore, CaseMemoryStore memoryStore,
                       int degreeThreshold, int maxCandidates,
                       DenseEmbedder denseEmbedder, boolean embeddingEnabled,
                       double embeddingSimilarityThreshold) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStore;
        this.denseEmbedder = denseEmbedder;
        this.degreeThreshold = degreeThreshold;
        this.maxCandidates = maxCandidates;
        this.embeddingEnabled = embeddingEnabled;
        this.embeddingSimilarityThreshold = embeddingSimilarityThreshold;
    }

    @Override
    public String name() { return "edge-inference"; }

    @Override
    public void setSessionPipeline(SessionPipeline pipeline) {
        this.sessionPipeline = pipeline;
    }

    SessionPipeline sessionPipeline() { return sessionPipeline; }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        if (sessionPipeline == null || memoryStore == null) {
            LOG.fine("Edge inference skipped: no SessionPipeline or CaseMemoryStore");
            return;
        }

        List<MindMapNode> underConnected = findUnderConnectedNodes(tenantId);
        if (underConnected.isEmpty()) return;

        List<CandidatePair> coOccurrenceCandidates = buildCandidates(underConnected, tenantId);

        Set<String> existingPairKeys = new HashSet<>();
        for (CandidatePair cp : coOccurrenceCandidates) {
            existingPairKeys.add(pairKey(cp.nodeIdA(), cp.nodeIdB()));
        }

        int remaining = maxCandidates - coOccurrenceCandidates.size();
        List<CandidatePair> embeddingCandidates = remaining > 0
            ? buildEmbeddingCandidates(underConnected, existingPairKeys, tenantId)
            : List.of();

        List<CandidatePair> candidates = new ArrayList<>(coOccurrenceCandidates);
        if (!embeddingCandidates.isEmpty()) {
            candidates.addAll(embeddingCandidates.subList(0, Math.min(remaining, embeddingCandidates.size())));
        }

        if (candidates.isEmpty()) return;

        LOG.info("Edge inference: " + candidates.size() + " candidate pairs ("
                 + coOccurrenceCandidates.size() + " co-occurrence, "
                 + (candidates.size() - coOccurrenceCandidates.size()) + " embedding) from "
                 + underConnected.size() + " under-connected nodes");

        List<ParsedRelationship> inferred = invokeEdgeInference(candidates);
        if (!inferred.isEmpty()) {
            persistEdges(inferred, tenantId);
        }
    }

    record CandidatePair(String nodeIdA, String nameA,
                         String nodeIdB, String nameB,
                         int coOccurrenceCount, List<String> sharedContexts) {}

    List<MindMapNode> findUnderConnectedNodes(String tenantId) {
        List<MindMapNode> result = new ArrayList<>();
        for (MindMapSubgraph sg : mindMapStore.listSubgraphs(tenantId)) {
            for (MindMapNode node : mindMapStore.nodesIn(sg.id(), tenantId)) {
                if (node.traits().contains("Summary") || node.traits().contains("CapsGenerated")) continue;
                int degree = mindMapStore.neighbors(node.id(), tenantId).size();
                if (degree <= degreeThreshold) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    List<CandidatePair> buildCandidates(List<MindMapNode> underConnected, String tenantId) {
        Set<String> nodeNamesLower = new HashSet<>();
        Map<String, String> lowerToId = new HashMap<>();
        Map<String, String> lowerToName = new HashMap<>();
        for (MindMapNode n : underConnected) {
            String lower = n.name().toLowerCase();
            nodeNamesLower.add(lower);
            lowerToId.put(lower, n.id());
            lowerToName.put(lower, n.name());
        }

        Map<String, Integer> pairCounts = new HashMap<>();
        Map<String, List<String>> pairContexts = new HashMap<>();

        List<Memory> memories = memoryStore.scan(
            new MemoryScanRequest(tenantId, "experience", null, null, 500, null));

        for (Memory mem : memories) {
            String subject = mem.attributes().getOrDefault("subject", "").toLowerCase();
            String relatedRaw = mem.attributes().getOrDefault("related_entities", "");
            Set<String> mentionedNames = new HashSet<>();
            if (nodeNamesLower.contains(subject)) mentionedNames.add(subject);
            for (String part : relatedRaw.split(",")) {
                String trimmed = part.strip().toLowerCase();
                if (nodeNamesLower.contains(trimmed)) mentionedNames.add(trimmed);
            }
            if (mentionedNames.size() < 2) continue;

            List<String> sorted = new ArrayList<>(mentionedNames);
            sorted.sort(String::compareTo);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    String pairKey = sorted.get(i) + "|" + sorted.get(j);
                    pairCounts.merge(pairKey, 1, Integer::sum);
                    List<String> contexts = pairContexts.computeIfAbsent(pairKey, k -> new ArrayList<>());
                    if (contexts.size() < 3) {
                        String snippet = mem.text().substring(0, Math.min(200, mem.text().length()));
                        contexts.add(snippet);
                    }
                }
            }
        }

        List<CandidatePair> candidates = new ArrayList<>();
        for (var entry : pairCounts.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            if (parts.length != 2) continue;
            String idA = lowerToId.get(parts[0]);
            String idB = lowerToId.get(parts[1]);
            if (idA == null || idB == null) continue;

            boolean hasEdge = mindMapStore.neighbors(idA, tenantId).stream()
                .anyMatch(e -> e.targetNodeId().equals(idB) || e.sourceNodeId().equals(idB));
            if (hasEdge) continue;

            candidates.add(new CandidatePair(
                idA, lowerToName.get(parts[0]),
                idB, lowerToName.get(parts[1]),
                entry.getValue(),
                pairContexts.getOrDefault(entry.getKey(), List.of())));
        }

        candidates.sort(Comparator.comparingInt(CandidatePair::coOccurrenceCount).reversed());
        return candidates.subList(0, Math.min(maxCandidates, candidates.size()));
    }

    List<CandidatePair> buildEmbeddingCandidates(List<MindMapNode> underConnected,
                                                   Set<String> existingPairKeys,
                                                   String tenantId) {
        if (denseEmbedder == null || !embeddingEnabled || underConnected.size() < 2) {
            return List.of();
        }

        List<String> texts = new ArrayList<>(underConnected.size());
        for (MindMapNode node : underConnected) {
            texts.add(buildNodeText(node));
        }
        List<float[]> embeddings = denseEmbedder.embedBatch(texts);

        record Scored(String idA, String nameA, String idB, String nameB, double similarity) {}
        List<Scored> scored = new ArrayList<>();

        for (int i = 0; i < underConnected.size(); i++) {
            for (int j = i + 1; j < underConnected.size(); j++) {
                MindMapNode a = underConnected.get(i);
                MindMapNode b = underConnected.get(j);

                if (existingPairKeys.contains(pairKey(a.id(), b.id()))) continue;

                boolean hasEdge = mindMapStore.neighbors(a.id(), tenantId).stream()
                    .anyMatch(e -> e.targetNodeId().equals(b.id()) || e.sourceNodeId().equals(b.id()));
                if (hasEdge) continue;

                double similarity = TextSimilarityCorroborator.cosineSimilarity(
                    embeddings.get(i), embeddings.get(j));
                if (similarity >= embeddingSimilarityThreshold) {
                    scored.add(new Scored(a.id(), a.name(), b.id(), b.name(), similarity));
                }
            }
        }

        scored.sort((x, y) -> Double.compare(y.similarity(), x.similarity()));

        return scored.stream()
            .map(s -> new CandidatePair(s.idA(), s.nameA(), s.idB(), s.nameB(), 0,
                List.of(String.format("Semantic similarity: %.2f", s.similarity()))))
            .toList();
    }

    static String buildNodeText(MindMapNode node) {
        var sb = new StringBuilder(node.name());
        for (String trait : node.traits()) {
            sb.append(' ').append(trait);
        }
        for (var entry : node.properties().entrySet()) {
            if (entry.getValue() != null) {
                sb.append(' ').append(entry.getValue());
            }
        }
        return sb.toString();
    }

    static String pairKey(String idA, String idB) {
        return idA.compareTo(idB) < 0 ? idA + "|" + idB : idB + "|" + idA;
    }

    List<ParsedRelationship> invokeEdgeInference(List<CandidatePair> candidates) {
        String userPrompt = buildUserPrompt(candidates);
        List<ParsedRelationship> inferred = new ArrayList<>();

        var tasks = new ArrayList<Consumer<AgentSession>>();
        tasks.add(session -> {
            try {
                var buf = new StringBuilder();
                session.query(userPrompt).subscribe().asStream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .forEach(buf::append);
                List<ParsedRelationship> parsed = parseRelationships(buf.toString());
                synchronized (inferred) {
                    inferred.addAll(parsed);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Edge inference LLM call failed", e);
            }
        });

        try {
            ParallelLlmExecutor.execute(sessionPipeline, "consolidation", 1,
                SYSTEM_PROMPT, "edge-inference", tasks);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Edge inference execution failed", e);
        }

        return inferred;
    }

    static List<ParsedRelationship> parseRelationships(String json) {
        List<ParsedRelationship> result = new ArrayList<>();
        Matcher matcher = RELATIONSHIP_PATTERN.matcher(json);
        while (matcher.find()) {
            result.add(new ParsedRelationship(
                matcher.group(1), matcher.group(2), matcher.group(3), ConfidenceOrigin.INFERRED));
        }
        return result;
    }

    private String buildUserPrompt(List<CandidatePair> candidates) {
        var sb = new StringBuilder("Entity pairs to evaluate for relationships:\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            CandidatePair cp = candidates.get(i);
            sb.append(i + 1).append(". ").append(cp.nameA()).append(" <-> ").append(cp.nameB());
            sb.append(" (co-occurs ").append(cp.coOccurrenceCount()).append("x)\n");
            for (String ctx : cp.sharedContexts()) {
                sb.append("   Context: ").append(ctx).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    void persistEdges(List<ParsedRelationship> relationships, String tenantId) {
        List<EdgeInput> edgeInputs = new ArrayList<>();
        for (ParsedRelationship pr : relationships) {
            MindMapNode source = mindMapStore.resolveNode(pr.source(), null, tenantId);
            MindMapNode target = mindMapStore.resolveNode(pr.target(), null, tenantId);
            if (source == null || target == null) {
                LOG.fine("Skipping unresolvable edge: " + pr.source() + " -> " + pr.target());
                continue;
            }

            boolean exists = mindMapStore.neighbors(source.id(), tenantId).stream()
                .anyMatch(e -> (e.targetNodeId().equals(target.id()) || e.sourceNodeId().equals(target.id()))
                               && e.edgeType().equals(pr.type()));
            if (exists) continue;

            edgeInputs.add(new EdgeInput(
                source.id(), target.id(), pr.type(),
                MindMapConfidenceDefaults.forOrigin(pr.origin(), Instant.now()),
                "edge-inference-consolidation",
                null, null, null, null, null, Map.of(), null));
        }

        if (!edgeInputs.isEmpty()) {
            mindMapStore.addEdges(edgeInputs, tenantId);
            LOG.info("Edge inference: persisted " + edgeInputs.size() + " inferred edges");
        }
    }
}
