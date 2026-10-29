package io.casehub.neocortex.cognition.prompt.observation;

import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.caps.SituationContext;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MindMapSubgraph;
import io.casehub.neocortex.mindmap.intelligence.tom.BehaviorType;
import io.casehub.neocortex.mindmap.intelligence.tom.EmotionalValence;
import io.casehub.neocortex.mindmap.intelligence.tom.TheoryOfMindEngine;
import io.casehub.neocortex.mindmap.intelligence.tom.ThreatLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CognitionObservationBuilder {

    // CAPS resting activation weight (Mischel & Shoda 1995, Higgins 1996 chronic accessibility)
    private static final double OVERLAP_THRESHOLD = 0.15;
    private static final double RESTING_WEIGHT = 0.22;

    private final String agentId;
    private final @Nullable String tenantId;
    private final @Nullable MindMapStore mindMapStore;
    private final @Nullable CognitionCore cognitionCore;
    private final @Nullable SituationClassifier situationClassifier;
    private final List<DriveSnapshot> baseDrives;

    private boolean lastClassifierActive = true;
    private final Map<String, TheoryOfMindEngine> tomEngines = new HashMap<>();
    private final RegulationTracker regulationTracker = new RegulationTracker();

    public CognitionObservationBuilder(
            String agentId,
            @Nullable String tenantId,
            @Nullable MindMapStore mindMapStore,
            @Nullable CognitionCore cognitionCore,
            @Nullable SituationClassifier situationClassifier,
            List<DriveSnapshot> baseDrives) {
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.mindMapStore = mindMapStore;
        this.cognitionCore = cognitionCore;
        this.situationClassifier = situationClassifier;
        this.baseDrives = baseDrives != null ? List.copyOf(baseDrives) : List.of();
    }

    public String agentId() { return agentId; }

    // --- Theory of Mind ---

    public void observeCharacterState(String characterId, BehaviorType behavior, ThreatLevel threat) {
        var engine = tomEngines.computeIfAbsent(characterId, k -> new TheoryOfMindEngine());
        engine.observeBehavior(behavior);
        engine.observeThreat(threat);
        regulationTracker.recordPosterior(characterId, engine.inferEmotionalState());
    }

    public List<ObservationSection> renderTheoryOfMind(Collection<String> nearbyAgentIds,
                                                        Map<String, String> agentNames) {
        var sections = new ArrayList<ObservationSection>();
        for (var id : nearbyAgentIds) {
            var engine = tomEngines.get(id);
            if (engine == null) { continue; }

            double[] state = engine.inferEmotionalState();
            double distressed = state[EmotionalValence.DISTRESSED.ordinal()];
            if (distressed < 0.35) { continue; }

            String reading = readOtherModel(id);
            if (reading == null || reading.isBlank()) { continue; }

            String name = agentNames.getOrDefault(id, id);
            var trajectory = regulationTracker.computeTrajectory(id);
            if (trajectory.isPresent()) {
                String velocityText = switch (trajectory.get().trajectory()) {
                    case IMPROVING -> "Something in your reading of " + name + " is shifting — the distress you sensed is easing.";
                    case STABLE -> "Whatever you have tried has not reached " + name + ". The distress you sense has not shifted.";
                    case WORSENING -> "It is getting worse. The distress you sense in " + name + " is deepening.";
                };
                reading = reading + "\n\n" + velocityText;
            }

            sections.add(ObservationSection.text("What You Sense About " + name, reading));
        }
        return sections;
    }

    private String readOtherModel(String targetId) {
        if (mindMapStore == null || tenantId == null) { return null; }
        var node = mindMapStore.listSubgraphs(tenantId).stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("OtherModelDerived"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .filter(n -> targetId.equals(n.properties().get("target-id")))
                .findFirst()
                .orElse(null);
        if (node == null) { return null; }

        var parts = new ArrayList<String>();
        var sensing = node.properties().get("empathic-sensing");
        if (sensing != null && !sensing.isBlank()) { parts.add(sensing); }
        var values = node.properties().get("inferred-values");
        if (values != null && !values.isBlank()) { parts.add(values); }
        var sensitivities = node.properties().get("appraisal-sensitivities");
        if (sensitivities != null && !sensitivities.isBlank()) { parts.add(sensitivities); }
        var bond = node.properties().get("relational-understanding");
        if (bond != null && !bond.isBlank()) { parts.add(bond); }

        if (parts.isEmpty()) {
            var legacy = node.properties().get("empathic-reading");
            if (legacy != null && !legacy.isBlank()) { parts.add(legacy); }
        }

        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    // --- Internal State (drives, attractors, differentiation, priority signatures) ---

    public ObservationSection renderInternalState(List<String> activatedCategories,
                                                   String situationText,
                                                   Collection<String> nearbyAgentIds,
                                                   SituationContext situationContext) {
        var drives = resolveAdaptedDrives();
        var generatedDescriptions = readGeneratedDriveDescriptions();

        Function<DriveSnapshot, String> descriptionFor = d -> {
            String label = d.type().toUpperCase().replace('-', ' ')
                           + " (" + String.format("%.1f", d.intensity()) + ")";
            String generated = generatedDescriptions.get(d.type());
            if (generated != null) {
                return label + ": " + generated;
            }
            return label;
        };

        var eligible = drives.stream()
                .filter(d -> d.intensity() > 0.2 && !descriptionFor.apply(d).isBlank())
                .toList();

        var filtered = appraisalFilterDrives(eligible, situationContext, nearbyAgentIds);

        var driveDescriptions = filtered.stream()
                .sorted(Comparator.comparingDouble(DriveSnapshot::intensity).reversed())
                .map(descriptionFor)
                .toList();

        var parts = new ArrayList<>(driveDescriptions);

        int memoryCount = 0;
        int attractorCount = 0;
        int diffTextCount = 0;
        int selfStandardCount = 0;
        int prioritySigCount = 0;

        if (mindMapStore != null && tenantId != null) {
            var subgraphs = mindMapStore.listSubgraphs(tenantId);

            var selfStandards = readSelfStandards(subgraphs);
            for (var d : filtered) {
                String standard = selfStandards.get(d.type());
                if (standard != null && !standard.isBlank()) {
                    parts.add(standard);
                    selfStandardCount++;
                }
            }

            var primedMemories = selectSituationPrimedMemories(situationContext, activatedCategories);
            if (!primedMemories.isEmpty()) {
                parts.addAll(primedMemories);
                memoryCount = primedMemories.size();
            }

            var attractorLines = subgraphs.stream()
                    .filter(s -> "behavioral".equals(s.type()))
                    .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                    .filter(n -> n.traits().contains("CapsGenerated"))
                    .filter(n -> agentId.equals(n.properties().get("agent-id")))
                    .filter(n -> {
                        var str = n.properties().get("strength");
                        return str != null && Double.parseDouble(str) > 0.1;
                    })
                    .sorted(Comparator.comparingDouble(
                            (MindMapNode n) -> {
                                var str = n.properties().get("strength");
                                return str != null ? Double.parseDouble(str) : 0.0;
                            }).reversed())
                    .limit(5)
                    .map(this::renderAttractorAsFelt)
                    .toList();
            if (!attractorLines.isEmpty()) {
                parts.addAll(attractorLines);
                attractorCount = attractorLines.size();
            }

            if (lastClassifierActive) {
                var activeDriveTypes = filtered.stream().map(DriveSnapshot::type).collect(Collectors.toSet());
                var diffTexts = readDriveDifferentiation(subgraphs, activeDriveTypes);
                parts.addAll(diffTexts);
                diffTextCount = diffTexts.size();
            }

            if (filtered.size() >= 2) {
                var prioritySigs = readPrioritySignatures(subgraphs, filtered);
                parts.addAll(prioritySigs);
                prioritySigCount = prioritySigs.size();
            }
        }

        if (parts.isEmpty()) { return null; }

        var driveNames = filtered.stream().map(DriveSnapshot::type).collect(Collectors.joining(", "));
        System.out.printf("[observation-assembly] %s | drives=%d [%s] | selfStandards=%d | diffTexts=%d | prioritySigs=%d | memories=%d | attractors=%d | totalParts=%d%n",
                agentId, filtered.size(), driveNames, selfStandardCount, diffTextCount, prioritySigCount, memoryCount, attractorCount, parts.size());

        return ObservationSection.text("What Pulls at You", String.join("\n\n", parts));
    }

    // Russell (1980) circumplex distance-from-origin
    private String renderAttractorAsFelt(MindMapNode node) {
        var strengthStr = node.properties().get("strength");
        double strength = strengthStr != null ? Double.parseDouble(strengthStr) : 0.0;
        var prevStr = node.properties().get("previous-strength");
        var sources = node.properties().get("source-names");
        var personalityLabel = node.properties().get("personality-label");

        String name = personalityLabel != null ? personalityLabel : node.name();

        String label = strength > 0.7 ? "Something deep-rooted"
                : strength > 0.4 ? "Something"
                : "A faint thread of something";

        var sb = new StringBuilder();
        sb.append(label).append(" — a ").append(name);
        if (prevStr != null) {
            double prev = Double.parseDouble(prevStr);
            if (strength > prev + 0.05) { sb.append(", growing stronger"); }
            else if (strength < prev - 0.05) { sb.append(", starting to fade"); }
        }
        if (sources != null && !sources.isBlank()) {
            String clean = sources.replaceAll("\\{\\{serves: [\\w-]+\\|\\w+: ", "").replace("}}", "");
            sb.append(" — rooted in ").append(clean.trim());
        }
        sb.append(".");
        return sb.toString();
    }

    // --- Drive resolution ---

    public List<DriveSnapshot> resolveAdaptedDrives() {
        if (mindMapStore == null || tenantId == null) {
            return baseDrives;
        }
        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var driveNodes = subgraphs.stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> "drive-intensity".equals(n.properties().get("cognitiveKind")))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .toList();
        if (driveNodes.isEmpty()) {
            return baseDrives;
        }
        var contextByType = baseDrives.stream()
                .collect(Collectors.toMap(DriveSnapshot::type, DriveSnapshot::context, (a, b) -> a));
        return driveNodes.stream()
                .map(n -> {
                    String driveType = n.properties().get("drive-type");
                    return new DriveSnapshot(
                            driveType,
                            Double.parseDouble(n.properties().getOrDefault("intensity", "0")),
                            n.properties().getOrDefault("description", ""),
                            contextByType.getOrDefault(driveType, "any"));
                })
                .toList();
    }

    public List<DriveSnapshot> appraisalFilterDrives(
            List<DriveSnapshot> eligible,
            SituationContext situationContext,
            Collection<String> nearbyAgentIds) {
        if (mindMapStore == null || tenantId == null) { return eligible; }
        if (situationContext.isEmpty()) {
            if (situationClassifier != null) { lastClassifierActive = false; }
            return eligible;
        }
        lastClassifierActive = true;

        var profiles = readDriveProfiles();
        if (profiles.isEmpty()) { return eligible; }

        var included = new ArrayList<DriveSnapshot>();
        var excluded = new ArrayList<String>();
        for (var d : eligible) {
            String inputs = profiles.get(d.type());
            if (inputs == null) {
                included.add(d);
            } else {
                double overlap = computeInputOverlap(inputs, situationContext.confidenceMap());
                double resting = d.intensity() * d.intensity() * RESTING_WEIGHT;
                if (overlap + resting >= OVERLAP_THRESHOLD) {
                    included.add(d);
                } else {
                    excluded.add(d.type());
                }
            }
        }

        var activationStr = situationContext.activations().stream()
                .map(sa -> sa.nodeId() + ":" + String.format("%.2f", sa.confidence()))
                .collect(Collectors.joining(", "));
        var includedStr = included.stream().map(DriveSnapshot::type)
                .collect(Collectors.joining(", "));
        var ratioStr = eligible.stream()
                .map(d -> {
                    String inputs = profiles.get(d.type());
                    if (inputs == null) { return d.type() + "=no-profile"; }
                    double overlap = computeInputOverlap(inputs, situationContext.confidenceMap());
                    double resting = d.intensity() * d.intensity() * RESTING_WEIGHT;
                    return d.type() + "=" + String.format("%.3f(overlap=%.3f,resting=%.3f)", overlap + resting, overlap, resting);
                })
                .collect(Collectors.joining(", "));
        System.out.printf("[appraisal-filter] %s | situation=[%s] | ratios=[%s] | rendered=[%s] | filtered=[%s]%n",
                agentId, activationStr, ratioStr, includedStr, String.join(", ", excluded));

        return included;
    }

    // --- Situation classification ---

    public SituationContext classifySituation(String situationText, Map<String, String> metadata) {
        if (situationClassifier == null || situationText == null || situationText.isBlank()) {
            return SituationContext.EMPTY;
        }
        try {
            var activations = situationClassifier.classify(situationText, metadata);
            return SituationContext.from(activations);
        } catch (Exception e) {
            return SituationContext.EMPTY;
        }
    }

    // --- CognitionCore prompt sections ---

    public List<ObservationSection> renderCognitionCoreSections() {
        if (cognitionCore == null || tenantId == null) { return List.of(); }
        var activatedDrives = cognitionCore.activatedDriveCategories();
        var ctx = new CognitionRenderContext(agentId, tenantId, cognitionCore.lastTargetAgentId(), activatedDrives);
        var sections = new ArrayList<ObservationSection>();
        for (var section : cognitionCore.promptSections()) {
            if (section.blockTag() != null) { continue; }
            var text = section.render(ctx);
            if (text != null && !text.isBlank()) {
                sections.add(adaptPromptSection(text));
            }
        }
        return sections;
    }

    public List<String> renderTaggedSections() {
        if (cognitionCore == null || tenantId == null) { return List.of(); }
        var activatedDrives = cognitionCore.activatedDriveCategories();
        var ctx = new CognitionRenderContext(agentId, tenantId, cognitionCore.lastTargetAgentId(), activatedDrives);
        var tagged = new ArrayList<String>();
        for (var section : cognitionCore.promptSections()) {
            if (section.blockTag() == null) { continue; }
            var text = section.render(ctx);
            if (text != null && !text.isBlank()) {
                tagged.add(text);
            }
        }
        return tagged;
    }

    // --- Beliefs from mindmap ---

    public List<ObservationSection> renderBeliefs(String beliefSubgraphName) {
        if (mindMapStore == null || tenantId == null) { return List.of(); }
        var subgraphs = mindMapStore.listSubgraphs(tenantId);
        var beliefSg = subgraphs.stream()
                .filter(sg -> beliefSubgraphName.equals(sg.name()))
                .findFirst();
        if (beliefSg.isEmpty()) { return List.of(); }

        var nodes = mindMapStore.nodesIn(beliefSg.get().id(), tenantId);
        var beliefNodes = nodes.stream()
                .filter(n -> n.traits().contains("Belieflike"))
                .toList();
        if (beliefNodes.isEmpty()) { return List.of(); }

        var revisedKeys = new java.util.HashSet<String>();
        var items = new ArrayList<String>();
        for (var node : beliefNodes) {
            var key = node.property("subject").orElse(node.name());
            var value = node.name();
            int entrenchment = (int) (node.confidence().value() * 10);
            String prefix = "belief-revision".equals(node.provenance()) ? "[REVISED] " : "";
            items.add(prefix + key + ": " + value);
        }
        return List.of(ObservationSection.items("Your Beliefs", null, items));
    }

    // --- Primed memories ---

    public List<String> selectSituationPrimedMemories(
            SituationContext situationContext,
            List<String> activatedDriveTypes) {
        if (mindMapStore == null || tenantId == null) { return List.of(); }
        if (situationContext.isEmpty()) { return List.of(); }
        if (activatedDriveTypes.isEmpty()) { return List.of(); }

        var cognitiveNodes = mindMapStore.listSubgraphs(tenantId).stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .toList();

        var profiles = cognitiveNodes.stream()
                .filter(n -> n.traits().contains("DriveProfileInferred"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .filter(n -> activatedDriveTypes.contains(n.properties().get("drive-type")))
                .toList();

        record DriveOverlap(String driveType, double score) {}
        var overlaps = new ArrayList<DriveOverlap>();
        for (var profile : profiles) {
            String driveType = profile.properties().get("drive-type");
            String inputsStr = profile.properties().getOrDefault("inferred-inputs", "");
            double overlap = computeInputOverlap(inputsStr, situationContext.confidenceMap());
            if (overlap > 0.0) {
                overlaps.add(new DriveOverlap(driveType, overlap));
            }
        }

        if (overlaps.isEmpty()) { return List.of(); }
        overlaps.sort(Comparator.comparingDouble(DriveOverlap::score).reversed());

        var formativeMemories = cognitiveNodes.stream()
                .filter(n -> "formative-experience".equals(n.properties().get("cognitiveKind")))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .toList();

        var result = new ArrayList<String>();
        for (var o : overlaps) {
            if (result.size() >= 2) { break; }
            var memories = formativeMemories.stream()
                    .filter(m -> {
                        String types = m.properties().getOrDefault("situation-types", "");
                        return Arrays.asList(types.split(",")).contains(o.driveType());
                    })
                    .limit(2)
                    .map(m -> {
                        String text = m.name().replaceAll("\\{\\{serves: [\\w-]+\\|\\w+: ", "").replace("}}", "");
                        if (text.length() > 100) {
                            int end = text.lastIndexOf(' ', 100);
                            if (end < 40) { end = 100; }
                            text = text.substring(0, end) + "...";
                        }
                        return "A memory surfaces — " + text;
                    })
                    .toList();
            result.addAll(memories);
        }
        return result;
    }

    // --- Internal helpers ---

    private Map<String, String> readGeneratedDriveDescriptions() {
        if (mindMapStore == null || tenantId == null) { return Map.of(); }
        var result = new HashMap<String, String>();
        mindMapStore.listSubgraphs(tenantId).stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("DriveDescriptionGenerated"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .forEach(n -> {
                    String driveType = n.properties().get("drive-type");
                    String desc = n.properties().get("generated-description");
                    if (driveType != null && desc != null && !desc.isBlank()) {
                        result.put(driveType, desc);
                    }
                });
        return result;
    }

    private Map<String, String> readSelfStandards(List<MindMapSubgraph> subgraphs) {
        var result = new HashMap<String, String>();
        subgraphs.stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("SelfStandardDerived"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .forEach(n -> {
                    String driveType = n.properties().get("drive-type");
                    String standard = n.properties().get("self-standard");
                    if (driveType != null && standard != null && !standard.isBlank()) {
                        result.put(driveType, standard);
                    }
                });
        return result;
    }

    private List<String> readPrioritySignatures(List<MindMapSubgraph> subgraphs,
                                                 List<DriveSnapshot> filteredDrives) {
        var activeDriveTypes = filteredDrives.stream()
                .map(DriveSnapshot::type)
                .collect(Collectors.toSet());

        return subgraphs.stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("PrioritySignatureDerived"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .filter(n -> {
                    String driveA = n.properties().get("drive-a");
                    String driveB = n.properties().get("drive-b");
                    return activeDriveTypes.contains(driveA) && activeDriveTypes.contains(driveB);
                })
                .map(n -> n.properties().get("priority-signature"))
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    // Easterbrook (1959): irrelevant content degrades attentional performance
    private List<String> readDriveDifferentiation(List<MindMapSubgraph> subgraphs, Set<String> activeDriveTypes) {
        var allCompetition = subgraphs.stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("DriveCompetitionInferred"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .toList();

        var included = new ArrayList<String>();
        var excludedPairs = new ArrayList<String>();
        for (var n : allCompetition) {
            String driveA = n.properties().get("drive-a");
            String driveB = n.properties().get("drive-b");
            String text = n.properties().get("differentiation-text");
            if (text == null || text.isBlank()) { continue; }
            if (activeDriveTypes.contains(driveA) && activeDriveTypes.contains(driveB)) {
                included.add(text);
            } else {
                excludedPairs.add(driveA + "↔" + driveB);
            }
        }

        if (!allCompetition.isEmpty()) {
            System.out.printf("[differentiation-filter] %s | total=%d | included=%d | excluded=%d [%s] | activeDrives=[%s]%n",
                    agentId, allCompetition.size(), included.size(), excludedPairs.size(),
                    String.join(", ", excludedPairs),
                    String.join(", ", activeDriveTypes));
        }

        return included;
    }

    private Map<String, String> readDriveProfiles() {
        if (mindMapStore == null || tenantId == null) { return Map.of(); }
        var result = new HashMap<String, String>();
        mindMapStore.listSubgraphs(tenantId).stream()
                .filter(s -> "cognitive".equals(s.type()))
                .flatMap(s -> mindMapStore.nodesIn(s.id(), tenantId).stream())
                .filter(n -> n.traits().contains("DriveProfileInferred"))
                .filter(n -> agentId.equals(n.properties().get("agent-id")))
                .forEach(n -> {
                    String driveType = n.properties().get("drive-type");
                    String inputs = n.properties().get("inferred-inputs");
                    if (driveType != null && inputs != null) {
                        result.put(driveType, inputs);
                    }
                });
        return result;
    }

    double computeInputOverlap(String inferredInputs, Map<String, Double> situationMap) {
        if (inferredInputs == null || inferredInputs.isBlank()) { return 0.0; }
        double overlap = 0.0;
        double profileTotal = 0.0;
        for (String entry : inferredInputs.split(",")) {
            int colonIdx = entry.indexOf(':');
            if (colonIdx < 0) { continue; }
            String nodeId = entry.substring(0, colonIdx).strip();
            double driveWeight = Double.parseDouble(entry.substring(colonIdx + 1).strip());
            profileTotal += driveWeight;
            Double situationWeight = situationMap.get(nodeId);
            if (situationWeight != null) {
                overlap += Math.min(driveWeight, situationWeight);
            }
        }
        if (profileTotal == 0.0) { return 0.0; }
        return overlap / profileTotal;
    }

    static ObservationSection adaptPromptSection(String text) {
        if (text.startsWith("## ")) {
            int end = text.indexOf('\n');
            if (end > 0) {
                return ObservationSection.text(text.substring(3, end).strip(), text.substring(end).strip());
            }
        }
        int nl = text.indexOf('\n');
        if (nl > 0) {
            return ObservationSection.text(text.substring(0, nl).strip(), text.substring(nl).strip());
        }
        return ObservationSection.text("Cognitive State", text);
    }

    protected @Nullable CognitionCore cognitionCore() { return cognitionCore; }
    protected @Nullable String tenantId() { return tenantId; }
    protected @Nullable MindMapStore mindMapStore() { return mindMapStore; }
    protected boolean lastClassifierActive() { return lastClassifierActive; }
}
