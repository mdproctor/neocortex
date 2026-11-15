package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.caps.CapsConnection;
import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.ClassificationContext;
import io.casehub.neocortex.caps.NodeType;
import io.casehub.neocortex.caps.SituationActivation;
import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Derives drive CAPS profiles from formation memories and detects drive competition.
 *
 * Literature: Barrett (2017) constructed emotion — concept = population of experiential
 * instances. Kashdan et al. (2015) — conceptual knowledge enables differentiation.
 * Semerari et al. (2003) — S6-level narrative grounding for metacognitive differentiation.
 *
 * See research doc §10.10.6-7, decision D12.
 */
@ApplicationScoped
@Priority(21)
public class DriveProfileDerivationPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(
        DriveProfileDerivationPhase.class.getName());

    static final String PROFILE_TRAIT = "DriveProfileInferred";
    static final String PINNED_PROFILE_TRAIT = "DriveProfilePinned";
    static final String COMPETITION_TRAIT = "DriveCompetitionInferred";

    private final MindMapStore store;
    private final SituationClassifier classifier;
    private final CapsTopology topology;
    private final io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry defaultsRegistry;

    @Inject
    public DriveProfileDerivationPhase(MindMapStore store,
                                       Instance<SituationClassifier> classifier,
                                       Instance<CapsTopology> topology,
                                       Instance<io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry> defaultsRegistry) {
        this.store = store;
        this.classifier = classifier.isResolvable() ? classifier.get() : null;
        this.topology = topology.isResolvable() ? topology.get() : null;
        this.defaultsRegistry = defaultsRegistry.isResolvable() ? defaultsRegistry.get() : null;
    }

    public DriveProfileDerivationPhase(MindMapStore store,
                                       SituationClassifier classifier,
                                       CapsTopology topology) {
        this.store = store;
        this.classifier = classifier;
        this.topology = topology;
        this.defaultsRegistry = null;
    }

    public DriveProfileDerivationPhase(MindMapStore store,
                                       SituationClassifier classifier,
                                       CapsTopology topology,
                                       io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry defaultsRegistry) {
        this.store            = store;
        this.classifier       = classifier;
        this.topology         = topology;
        this.defaultsRegistry = defaultsRegistry;
    }


    @Override
    public String name() {
        return "drive-profile-derivation";
    }
@Override
    public ConsolidationCapability capability() {
        return new ConsolidationCapability(
                Set.of("formative-experience"),
                Set.of(PROFILE_TRAIT, COMPETITION_TRAIT));
    }


    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        List<MindMapNode> allCognitive = store.search(
                MindMapQuery.of(tenantId, 2000).withType(SubgraphTypes.COGNITIVE));

        Map<String, List<MindMapNode>> drivesByAgent = allCognitive.stream()
                                                                   .filter(n -> "drive-intensity".equals(n.properties().get("cognitiveKind")))
                                                                   .filter(n -> n.properties().containsKey("agent-id"))
                                                                   .collect(Collectors.groupingBy(n -> n.properties().get("agent-id")));

        if (drivesByAgent.isEmpty()) {return;}

        List<MindMapNode> formativeNodes = allCognitive.stream()
                                                       .filter(n -> "formative-experience".equals(n.properties().get("cognitiveKind")))
                                                       .toList();

        Set<String> pinnedDriveKeys = allCognitive.stream()
                                                  .filter(n -> n.traits().contains(PINNED_PROFILE_TRAIT))
                                                  .map(n -> n.properties().get("agent-id") + "/" + n.properties().get("drive-type"))
                                                  .filter(k -> !k.startsWith("null"))
                                                  .collect(Collectors.toSet());

        Set<String> inferredDriveKeys = allCognitive.stream()
                                                    .filter(n -> n.traits().contains(PROFILE_TRAIT))
                                                    .map(n -> n.properties().get("agent-id") + "/" + n.properties().get("drive-type"))
                                                    .filter(k -> !k.startsWith("null"))
                                                    .collect(Collectors.toSet());

        for (var agentEntry : drivesByAgent.entrySet()) {
            String            agentId    = agentEntry.getKey();
            List<MindMapNode> driveNodes = agentEntry.getValue();

            List<MindMapNode> drivesToDerive = driveNodes.stream()
                                                         .filter(dn -> {
                                                             String dt = dn.properties().get("drive-type");
                                                             if (dt == null) {return false;}
                                                             String key = agentId + "/" + dt;
                                                             if (pinnedDriveKeys.contains(key)) {
                                                                 LOG.info("drive-profile-derivation [" + agentId + "/" + dt + "]: skipped — pinned");
                                                                 return false;
                                                             }
                                                             if (inferredDriveKeys.contains(key)) {
                                                                 LOG.info("drive-profile-derivation [" + agentId + "/" + dt + "]: skipped — already derived");
                                                                 return false;
                                                             }
                                                             return true;
                                                         })
                                                         .toList();

            if (drivesToDerive.isEmpty()) {continue;}

            List<MindMapNode> agentMemories = formativeNodes.stream()
                                                            .filter(n -> agentId.equals(n.properties().get("agent-id")))
                                                            .toList();

            if (agentMemories.isEmpty()) {continue;}

            Map<String, DriveProfile> profiles   = deriveProfiles(drivesToDerive, driveNodes, agentMemories, tenantId, agentId);
            String                    subgraphId = findCognitiveSubgraph(tenantId, agentId);
            if (subgraphId == null) {continue;}

            storeProfiles(tenantId, agentId, subgraphId, profiles);
            detectAndStoreCompetition(tenantId, agentId, subgraphId, profiles, agentMemories);

            if (!profiles.isEmpty()) {
                var profileSummary = profiles.entrySet().stream()
                                             .map(e -> e.getKey() + "(" + e.getValue().memoryExcerpts.size() + " memories)")
                                             .collect(Collectors.joining(", "));
                LOG.info("drive-profile-derivation [" + agentId + "]: " + profiles.size()
                         + " profiles — " + profileSummary
                         + (classifier != null ? " [CAPS active]" : " [catalogue fallback]"));
            }
        }
    }

    private Map<String, DriveProfile> deriveProfiles(List<MindMapNode> drivesToDerive,
                                                     List<MindMapNode> allDriveNodes,
                                                     List<MindMapNode> formativeNodes,
                                                     String tenantId, String agentId) {
        Map<String, DriveProfile> profiles = new HashMap<>();

        Map<String, String> driveDescriptions = new HashMap<>();
        for (MindMapNode dn : allDriveNodes) {
            String dt   = dn.properties().get("drive-type");
            String desc = dn.properties().get("description");
            if (dt != null && desc != null) {driveDescriptions.put(dt, desc);}
        }

        for (MindMapNode driveNode : drivesToDerive) {
            String driveType = driveNode.properties().get("drive-type");
            if (driveType == null) {continue;}

            List<MindMapNode> servingMemories = formativeNodes.stream()
                                                              .filter(m -> memoryServesDrive(m, driveType))
                                                              .toList();

            if (servingMemories.isEmpty()) {continue;}

            Map<String, String> discriminationMeta = addDispositionMetadata(
                    buildDiscriminationMetadata(driveType, driveDescriptions), agentId);

            var inputActivations = new java.util.concurrent.ConcurrentHashMap<String, Double>();
            var memoryExcerpts = java.util.Collections.synchronizedList(new ArrayList<String>());

            try (var classifyExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (MindMapNode memory : servingMemories) {
                    String episodeText = memory.name();
                    memoryExcerpts.add(excerpt(episodeText));
                    if (classifier != null) {
                        futures.add(classifyExecutor.submit(() -> {
                            try {
                                var classResult = classifier.classify(
                                        episodeText, discriminationMeta,
                                        ClassificationContext.of(tenantId));
                                for (SituationActivation sa : classResult.activations()) {
                                    inputActivations.merge(sa.nodeId(), sa.confidence(), Math::max);
                                }
                            } catch (Exception e) {
                                LOG.warning("drive-profile-derivation: skipping memory for " + driveType
                                            + " (classification failed): " + excerpt(episodeText));
                            }
                            return null;
                        }));
                    }
                }
                String description = driveNode.properties().get("description");
                if (classifier != null && description != null && !description.isBlank()) {
                    futures.add(classifyExecutor.submit(() -> {
                        try {
                            var descResult = classifier.classify(
                                    description, discriminationMeta,
                                    ClassificationContext.of(tenantId));
                            for (SituationActivation sa : descResult.activations()) {
                                inputActivations.merge(sa.nodeId(), sa.confidence(), Math::max);
                            }
                        } catch (Exception e) {
                            LOG.warning("drive-profile-derivation: skipping description for " + driveType
                                        + " (classification failed): " + excerpt(description));
                        }
                        return null;
                    }));
                }
                for (var f : futures) { try { f.get(); } catch (Exception e) { /* logged */ } }
            }

            Set<String> mediatingNodes = topology != null
                                         ? traceToMediating(inputActivations.keySet())
                                         : Set.of();

            profiles.put(driveType, new DriveProfile(
                    driveType, inputActivations, mediatingNodes, memoryExcerpts));
        }

        return profiles;
    }

    private Map<String, String> buildDiscriminationMetadata(String targetDrive,
                                                            Map<String, String> allDriveDescriptions) {
        var otherDrives = new StringBuilder();
        for (var entry : allDriveDescriptions.entrySet()) {
            if (!entry.getKey().equals(targetDrive)) {
                String briefDesc = entry.getValue().length() > 60
                                   ? entry.getValue().substring(0, 60) + "..."
                                   : entry.getValue();
                otherDrives.append(entry.getKey()).append(" — ").append(briefDesc).append("; ");
            }
        }
        if (otherDrives.isEmpty()) {return Map.of();}

        String targetDesc = allDriveDescriptions.get(targetDrive);
        String targetClause = targetDesc != null
                              ? "'" + targetDrive + "' (" + targetDesc.substring(0, Math.min(80, targetDesc.length())) + ")"
                              : "'" + targetDrive + "'";

        return Map.of("drive-discrimination",
                      "DISCRIMINATIVE CLASSIFICATION — return at most 3 activations. "
                      + "This text relates to the drive " + targetClause + ". "
                      + "The agent's OTHER drives are: " + otherDrives
                      + "Return ONLY the nodes that DISTINGUISH " + targetClause
                      + " from the other drives listed above. "
                      + "A node is distinctive if it would activate for '" + targetDrive
                      + "' but NOT for the other drives. A node that would activate "
                      + "equally for multiple drives is NOT distinctive — exclude it. "
                      + "Example: if one drive is about helping others and another about "
                      + "demonstrating skill, 'acceptance' distinguishes helping but "
                      + "'reward' does not (both drives earn rewards). "
                      + "'mastery' distinguishes skill but not helping. "
                      + "Pick the 2-3 nodes from different categories that best separate "
                      + "this drive from the others. Fewer is better than more.");
    }

    private Map<String, String> addDispositionMetadata(Map<String, String> metadata, String agentId) {
        if (defaultsRegistry == null) {return metadata;}
        var defaults = defaultsRegistry.forAgent(agentId);
        if (defaults.isEmpty() || defaults.get().descriptor() == null) {return metadata;}
        var axes = defaults.get().descriptor().disposition();
        if (axes == null) {return metadata;}

        var enriched = new HashMap<>(metadata);
        if (axes.socialOrient() != null) {enriched.put("disposition.socialOrient", axes.socialOrient());}
        if (axes.ruleFollowing() != null) {enriched.put("disposition.ruleFollowing", axes.ruleFollowing());}
        if (axes.riskAppetite() != null) {enriched.put("disposition.riskAppetite", axes.riskAppetite());}
        if (axes.autonomy() != null) {enriched.put("disposition.autonomy", axes.autonomy());}
        if (axes.conflictMode() != null) {enriched.put("disposition.conflictMode", axes.conflictMode());}
        return enriched;
    }


    private boolean memoryServesDrive(MindMapNode memoryNode, String driveType) {
        String situationTypes = memoryNode.properties().get("situation-types");
        if (situationTypes == null) return false;
        return Arrays.asList(situationTypes.split(",")).contains(driveType);
    }

    private Set<String> traceToMediating(Set<String> inputNodeIds) {
        Set<String> mediating = new HashSet<>();
        for (CapsConnection conn : topology.connections()) {
            if (inputNodeIds.contains(conn.from())) {
                var targetNode = topology.nodes().get(conn.to());
                if (targetNode != null && targetNode.type() == NodeType.MEDIATING) {
                    mediating.add(conn.to());
                }
            }
        }
        return mediating;
    }

    private void detectAndStoreCompetition(String tenantId, String agentId,
                                           String subgraphId,
                                           Map<String, DriveProfile> profiles,
                                           List<MindMapNode> agentMemories) {
        List<String> driveTypes = new ArrayList<>(profiles.keySet());

        for (int i = 0; i < driveTypes.size(); i++) {
            for (int j = i + 1; j < driveTypes.size(); j++) {
                DriveProfile a = profiles.get(driveTypes.get(i));
                DriveProfile b = profiles.get(driveTypes.get(j));

                Set<String> sharedMediating;
                Set<String> uniqueMediatingA;
                Set<String> uniqueMediatingB;

                if (!a.mediatingNodes.isEmpty() || !b.mediatingNodes.isEmpty()) {
                    sharedMediating = new HashSet<>(a.mediatingNodes);
                    sharedMediating.retainAll(b.mediatingNodes);
                    uniqueMediatingA = new HashSet<>(a.mediatingNodes);
                    uniqueMediatingA.removeAll(sharedMediating);
                    uniqueMediatingB = new HashSet<>(b.mediatingNodes);
                    uniqueMediatingB.removeAll(sharedMediating);
                } else {
                    Set<String> catalogueA      = catalogueEntries(agentMemories, a.driveType);
                    Set<String> catalogueB      = catalogueEntries(agentMemories, b.driveType);
                    Set<String> sharedCatalogue = new HashSet<>(catalogueA);
                    sharedCatalogue.retainAll(catalogueB);
                    sharedMediating  = sharedCatalogue;
                    uniqueMediatingA = new HashSet<>(catalogueA);
                    uniqueMediatingA.removeAll(sharedCatalogue);
                    uniqueMediatingB = new HashSet<>(catalogueB);
                    uniqueMediatingB.removeAll(sharedCatalogue);
                }

                String diffText = composeDifferentiationText(a, b, uniqueMediatingA, uniqueMediatingB);

                var props = Map.of(
                        "agent-id", agentId,
                        "drive-a", a.driveType,
                        "drive-b", b.driveType,
                        "shared-mediating", String.join(",", sharedMediating),
                        "unique-mediating-a", String.join(",", uniqueMediatingA),
                        "unique-mediating-b", String.join(",", uniqueMediatingB),
                        "differentiation-text", diffText,
                        "anchor-memories-a", String.join("|", a.memoryExcerpts),
                        "anchor-memories-b", String.join("|", b.memoryExcerpts));

                store.addNode(
                        NodeInput.of("competition-" + a.driveType + "-" + b.driveType, subgraphId)
                                 .withTraits(Set.of(COMPETITION_TRAIT))
                                 .withProperties(props),
                        tenantId);

                LOG.info("drive-competition [" + agentId + "]: "
                    + a.driveType + " vs " + b.driveType
                    + " — shared=" + String.join(",", sharedMediating)
                    + (a.mediatingNodes.isEmpty() ? " [catalogue]" : " [CAPS mediating]"));
            }
        }
    }

    private Set<String> catalogueEntries(List<MindMapNode> memories, String driveType) {
        return memories.stream()
                       .filter(m -> memoryServesDrive(m, driveType))
                       .map(m -> m.properties().get("catalogue-entry-id"))
                       .filter(c -> c != null && !c.isBlank())
                       .collect(Collectors.toSet());
    }


    private String composeDifferentiationText(DriveProfile a, DriveProfile b,
                                               Set<String> uniqueA, Set<String> uniqueB) {
        var sb = new StringBuilder();
        sb.append("How they differ:\n");

        if (!a.memoryExcerpts.isEmpty()) {
            sb.append("One pull traces through ").append(a.memoryExcerpts.getFirst());
            if (a.memoryExcerpts.size() > 1) {
                sb.append(", ").append(a.memoryExcerpts.get(1));
            }
            sb.append(".\n");
        }

        if (!b.memoryExcerpts.isEmpty()) {
            sb.append("The other traces through ").append(b.memoryExcerpts.getFirst());
            if (b.memoryExcerpts.size() > 1) {
                sb.append(", ").append(b.memoryExcerpts.get(1));
            }
            sb.append(".\n");
        }

        sb.append("Both are real. They're not the same.");
        return sb.toString();
    }

    private void storeProfiles(String tenantId, String agentId,
                                String subgraphId,
                                Map<String, DriveProfile> profiles) {
        for (var entry : profiles.entrySet()) {
            DriveProfile profile = entry.getValue();
            String inputsJson = profile.inputActivations.entrySet().stream()
                .map(e -> e.getKey() + ":" + String.format("%.3f", e.getValue()))
                .collect(Collectors.joining(","));

            store.addNode(
                NodeInput.of("drive-profile-" + profile.driveType, subgraphId)
                    .withTraits(Set.of(PROFILE_TRAIT))
                    .withProperties(Map.of(
                        "agent-id", agentId,
                        "drive-type", profile.driveType,
                        "inferred-inputs", inputsJson,
                        "mediating-nodes", String.join(",", profile.mediatingNodes),
                        "source-memory-count", String.valueOf(profile.memoryExcerpts.size()))),
                tenantId);
        }
    }

    /**
     * Stores a pinned profile that won't be re-derived. Use after validating
     * a derived profile to freeze it.
     */
    public static void storePinnedProfile(MindMapStore store, String tenantId,
                                          String subgraphId, String agentId,
                                          String driveType, Map<String, Double> inputActivations) {
        String inputsJson = inputActivations.entrySet().stream()
                                            .map(e -> e.getKey() + ":" + String.format("%.3f", e.getValue()))
                                            .collect(Collectors.joining(","));

        store.addNode(
                NodeInput.of("drive-profile-pinned-" + driveType, subgraphId)
                         .withTraits(Set.of(PINNED_PROFILE_TRAIT))
                         .withProperties(Map.of(
                                 "agent-id", agentId,
                                 "drive-type", driveType,
                                 "inferred-inputs", inputsJson,
                                 "source-memory-count", "0")),
                tenantId);
    }


    private void removeExistingArtifacts(String tenantId, String agentId,
                                          List<MindMapNode> allCognitive) {
        allCognitive.stream()
            .filter(n -> n.traits().contains(PROFILE_TRAIT) || n.traits().contains(COMPETITION_TRAIT))
            .filter(n -> agentId.equals(n.properties().get("agent-id")))
            .forEach(n -> store.eraseNode(n.id(), tenantId));
    }

    private String findCognitiveSubgraph(String tenantId, String agentId) {
        return store.listSubgraphs(tenantId).stream()
            .filter(sg -> SubgraphTypes.COGNITIVE.equals(sg.type()))
            .filter(sg -> sg.id().contains(agentId) || sg.name().contains(agentId))
            .map(sg -> sg.id())
            .findFirst()
            .orElse(store.listSubgraphs(tenantId).stream()
                .filter(sg -> SubgraphTypes.COGNITIVE.equals(sg.type()))
                .map(sg -> sg.id())
                .findFirst()
                .orElse(null));
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        if (text.length() <= 80) return text;
        int end = text.lastIndexOf(' ', 80);
        if (end < 40) end = 80;
        return text.substring(0, end) + "...";
    }

    record DriveProfile(String driveType,
                        Map<String, Double> inputActivations,
                        Set<String> mediatingNodes,
                        List<String> memoryExcerpts) {}
}
