package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.caps.SituationClassifier;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.core.CognitionTickParticipant;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognitive.CognitiveEmotion;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.OverlayRef;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AppraisalTickParticipant implements CognitionTickParticipant {

    private final SalienceStrategy salienceStrategy;
    private final AppraisalStrategy appraisalStrategy;
    private final DriveOrchestrator driveOrchestrator;
    private final MoodOrchestrator moodOrchestrator;
    private final @Nullable CognitiveDefaultsRegistry defaultsRegistry;
    private final CognitionConfig config;
    private final @Nullable MindMapStore mindMapStore;


    private static final String DRIVE_INTENSITY_KIND = "drive-intensity";
    private final AppraisalThresholds thresholds;

    private final ConcurrentHashMap<String, AppraisalResult> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HabituationState> habituationStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Double>> concernSaliences = new ConcurrentHashMap<>();
    private final @Nullable SituationClassifier                  situationClassifier;
    private volatile java.util.function.@Nullable BiFunction<String, String, Optional<io.casehub.neocortex.cognition.search.SearchBudget>> searchBudgetAccessor;

    private final ConcurrentHashMap<String, ChannelWeightProfile> channelWeights  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> regulatedNarratives = new ConcurrentHashMap<>();


    public Map<String, Double> currentConcernSalience(String agentId, String tenantId) {
        return concernSaliences.getOrDefault(agentId + ":" + tenantId, Map.of());
    }

    public AppraisalThresholds thresholds() {
        return thresholds;
    }


    public AppraisalTickParticipant(
            SalienceStrategy salienceStrategy,
            AppraisalStrategy appraisalStrategy,
            DriveOrchestrator driveOrchestrator,
            MoodOrchestrator moodOrchestrator,
            @Nullable CognitiveDefaultsRegistry defaultsRegistry,
            CognitionConfig config) {
        this(salienceStrategy, appraisalStrategy, driveOrchestrator,
             moodOrchestrator, defaultsRegistry, config, null, AppraisalThresholds.defaults());
    }

    public AppraisalTickParticipant(
            SalienceStrategy salienceStrategy,
            AppraisalStrategy appraisalStrategy,
            DriveOrchestrator driveOrchestrator,
            MoodOrchestrator moodOrchestrator,
            @Nullable CognitiveDefaultsRegistry defaultsRegistry,
            CognitionConfig config,
            @Nullable MindMapStore mindMapStore) {
        this(salienceStrategy, appraisalStrategy, driveOrchestrator,
             moodOrchestrator, defaultsRegistry, config, mindMapStore, AppraisalThresholds.defaults());
    }

    public AppraisalTickParticipant(
            SalienceStrategy salienceStrategy,
            AppraisalStrategy appraisalStrategy,
            DriveOrchestrator driveOrchestrator,
            MoodOrchestrator moodOrchestrator,
            @Nullable CognitiveDefaultsRegistry defaultsRegistry,
            CognitionConfig config,
            @Nullable MindMapStore mindMapStore,
            AppraisalThresholds thresholds) {
        this(salienceStrategy, appraisalStrategy, driveOrchestrator,
             moodOrchestrator, defaultsRegistry, config, mindMapStore, thresholds, null);
    }

    public AppraisalTickParticipant(
            SalienceStrategy salienceStrategy,
            AppraisalStrategy appraisalStrategy,
            DriveOrchestrator driveOrchestrator,
            MoodOrchestrator moodOrchestrator,
            @Nullable CognitiveDefaultsRegistry defaultsRegistry,
            CognitionConfig config,
            @Nullable MindMapStore mindMapStore,
            AppraisalThresholds thresholds,
            @Nullable SituationClassifier situationClassifier) {
        this.salienceStrategy     = salienceStrategy;
        this.appraisalStrategy    = appraisalStrategy;
        this.driveOrchestrator    = driveOrchestrator;
        this.moodOrchestrator     = moodOrchestrator;
        this.defaultsRegistry     = defaultsRegistry;
        this.config               = config;
        this.mindMapStore         = mindMapStore;
        this.thresholds           = thresholds;
        this.situationClassifier  = situationClassifier;
    }


    @Override
    public void tick(CognitionTickContext context) {
        if (!config.appraisalEnabled()) {return;}

        var agentKey    = context.agentId() + ":" + context.tenantId();
        regulatedNarratives.remove(agentKey);
        var observation = context.observation();

        var profileOpt = driveOrchestrator.currentDrives(context.agentId(), context.tenantId());
        if (profileOpt.isEmpty()) {return;}
        var drives = new ArrayList<>(profileOpt.get().allDrives());

        List<MindMapNode>                        driveNodes     = List.of();
        List<io.casehub.neocortex.memory.Memory> activeConcerns = List.of();
        if (mindMapStore != null) {
            driveNodes = loadDriveIntensityNodes(context.agentId(), context.tenantId());
            for (var node : driveNodes) {
                String driveType = node.properties().getOrDefault("drive-type", node.name());
                double intensity = parseDoubleSafe(node.properties().get("intensity"), 0.5);
                drives.add(new Drive(driveType, DriveCategory.CHARACTER, intensity, "adapted"));
            }
            activeConcerns = buildActiveConcerns(driveNodes, context.agentId(), context.tenantId());
        }

        var mood = moodOrchestrator.currentMood(context.agentId(), context.tenantId())
                                   .orElse(null);

        var defaults = defaultsRegistry != null
                       ? defaultsRegistry.forAgentOrDefaults(context.agentId()) : null;
        var weights   = defaults != null ? defaults.appraisalWeights() : null;
        var habConfig = defaults != null ? defaults.habituationConfig() : null;
        var disposition = defaults != null && defaults.descriptor() != null
                          ? defaults.descriptor().disposition() : null;
        var habituation = habituationStates.getOrDefault(agentKey, HabituationState.empty());

        PerceivedSituation situation;
        if (config.salienceEnabled() && observation != null) {
            situation = salienceStrategy.perceive(
                    new SalienceContext(observation, drives, mood, activeConcerns, List.of(), context.tenantId()));
        } else if (observation != null) {
            situation = PerceivedSituation.passThrough(observation);
        } else {
            return;
        }

        RelationalSchema targetSchema = null;
        List<PersonalityFacet> personalityFacets = null;
        if (context.targetAgentId() != null && mindMapStore != null) {
            targetSchema = resolveRelationalSchema(
                    context.agentId(), context.targetAgentId(), context.tenantId());
        }
        if (defaults != null && defaults.personalityProfile() != null) {
            personalityFacets = defaults.personalityProfile().facets();
        }

        var result = appraisalStrategy.appraise(
                new AppraisalContext(context.agentId(), situation, drives, weights, habConfig, habituation, mood,
                        disposition, targetSchema, personalityFacets));

        results.put(agentKey, result);
        habituationStates.put(agentKey, result.updatedHabituation());

        if (!situation.salience().isEmpty()) {
            concernSaliences.put(agentKey, situation.salience());
        }

        if (situationClassifier != null && observation != null) {
            try {
                var classCtx = new io.casehub.neocortex.caps.ClassificationContext(context.tenantId(), context.agentId());
                var activations = situationClassifier.classify(observation, Map.of(), classCtx).activations();
                activations = RelationalBiasing.apply(targetSchema, activations);
                channelWeights.put(agentKey, TopologyChannelWeighter.weight(activations));
            } catch (Exception e) {
                channelWeights.put(agentKey, ChannelWeightProfile.DEFAULT);
            }
        }

        bridgeToMood(result, context);
        generateCapacityFrustration(context);
    }

    public Optional<ChannelWeightProfile> currentChannelWeights(String agentId, String tenantId) {
        return Optional.ofNullable(channelWeights.get(agentId + ":" + tenantId));
    }

    public Optional<AppraisalResult> currentResult(String agentId, String tenantId) {
        return Optional.ofNullable(results.get(agentId + ":" + tenantId));
    }

    public Optional<String> currentRegulatedNarrative(String agentId, String tenantId) {
        return Optional.ofNullable(regulatedNarratives.get(agentId + ":" + tenantId));
    }

    public void setRegulatedNarrative(String agentId, String tenantId, String narrative) {
        regulatedNarratives.put(agentId + ":" + tenantId, narrative);
    }

    public @Nullable HabituationState currentHabituation(String agentId, String tenantId) {
        var state = habituationStates.get(agentId + ":" + tenantId);
        return state != null && !state.observationCounts().isEmpty() ? state : null;
    }

    public void setSearchBudgetAccessor(java.util.function.BiFunction<String, String, Optional<io.casehub.neocortex.cognition.search.SearchBudget>> accessor) {
        this.searchBudgetAccessor = accessor;
    }


    private static final io.casehub.neocortex.memory.MemoryDomain CONCERN_DOMAIN =
            new io.casehub.neocortex.memory.MemoryDomain("concern");

    private @Nullable RelationalSchema resolveRelationalSchema(
            String observerAgentId, String targetAgentId, String tenantId) {
        var peopleSg = mindMapStore.listSubgraphs(tenantId).stream()
                .filter(sg -> SubgraphTypes.PERSON.equals(sg.type()) || SubgraphTypes.PERSON.equals(sg.name()))
                .findFirst();
        if (peopleSg.isEmpty()) return null;

        var nodes = mindMapStore.nodesIn(peopleSg.get().id(), tenantId);

        String targetNodeId = null;
        for (var node : nodes) {
            if (node.traits().contains("overlay")) continue;
            if (targetAgentId.equals(node.property("agentId").orElse(null))) {
                targetNodeId = node.id();
                break;
            }
        }
        if (targetNodeId == null) return null;

        for (var overlay : nodes) {
            if (!overlay.traits().contains("overlay")) continue;
            if (!observerAgentId.equals(overlay.property(OverlayRef.AGENT_ID).orElse(null))) continue;
            var sharedId = OverlayRef.sharedNodeId(overlay).orElse(null);
            if (targetNodeId.equals(sharedId)) {
                return RelationalSchema.fromProperties(overlay.properties());
            }
        }
        return null;
    }

    private List<MindMapNode> loadDriveIntensityNodes(String agentId, String tenantId) {
        return mindMapStore.search(MindMapQuery.of(tenantId, 200).withType(SubgraphTypes.COGNITIVE))
                           .stream()
                           .filter(n -> DRIVE_INTENSITY_KIND.equals(n.properties().get("cognitiveKind")))
                           .filter(n -> agentId.equals(n.properties().get("agent-id")))
                           .toList();
    }

    private List<io.casehub.neocortex.memory.Memory> buildActiveConcerns(
            List<MindMapNode> driveNodes, String agentId, String tenantId) {
        var concerns = new ArrayList<io.casehub.neocortex.memory.Memory>();
        var now = java.time.Instant.now();
        var conf = new io.casehub.neocortex.cognitive.Confidence(
                io.casehub.neocortex.cognitive.ConfidenceOrigin.STATED, 0.8, now);

        for (var node : driveNodes) {
            String desc = node.properties().get("description");
            if (desc == null || desc.isBlank()) continue;
            String driveType = node.properties().getOrDefault("drive-type", node.name());
            concerns.add(new io.casehub.neocortex.memory.Memory(
                    driveType, io.casehub.neocortex.memory.Subject.of("drive", driveType),
                    CONCERN_DOMAIN, tenantId, "", desc, Map.of(), now, conf,
                    null, null, null, null, null));
        }

        var goalNodes = mindMapStore.search(MindMapQuery.of(tenantId, 200).withType(SubgraphTypes.GOAL));
        for (var node : goalNodes) {
            if (!"active".equals(node.properties().get("status"))) continue;
            if (!agentId.equals(node.properties().get("agent-id"))) continue;
            concerns.add(new io.casehub.neocortex.memory.Memory(
                    node.name(), io.casehub.neocortex.memory.Subject.of("goal", node.name()),
                    CONCERN_DOMAIN, tenantId, "", node.name(), Map.of(), now, conf,
                    null, null, null, null, null));
        }

        return concerns;
    }

    private static double parseDoubleSafe(String value, double fallback) {
        if (value == null) return fallback;
        try { return Double.parseDouble(value); }
        catch (NumberFormatException e) { return fallback; }
    }


    private void generateCapacityFrustration(CognitionTickContext context) {
        var accessor = this.searchBudgetAccessor;
        if (accessor == null) {return;}
        var budgetOpt = accessor.apply(context.agentId(), context.tenantId());
        if (budgetOpt.isEmpty() || !budgetOpt.get().capacityConstrained()) {return;}

        var budget       = budgetOpt.get();
        var frustrations = new ArrayList<CognitiveEmotion>();
        var now          = java.time.Instant.now();

        if (budget.depthGap() > 0.05) {
            double intensity = Math.min(1.0, budget.depthGap());
            frustrations.add(new CognitiveEmotion(
                    io.casehub.neocortex.cognitive.EmotionType.DISAPPOINTMENT,
                    intensity, "capacity-depth", now,
                    io.casehub.neocortex.cognitive.EmotionSource.INTRINSIC,
                    io.casehub.neocortex.cognitive.AlmaPadTable.project(
                            io.casehub.neocortex.cognitive.EmotionType.DISAPPOINTMENT, intensity)));
        }
        if (budget.breadthGap() > 0.05) {
            double intensity = Math.min(1.0, budget.breadthGap());
            frustrations.add(new CognitiveEmotion(
                    io.casehub.neocortex.cognitive.EmotionType.DISAPPOINTMENT,
                    intensity, "capacity-breadth", now,
                    io.casehub.neocortex.cognitive.EmotionSource.INTRINSIC,
                    io.casehub.neocortex.cognitive.AlmaPadTable.project(
                            io.casehub.neocortex.cognitive.EmotionType.DISAPPOINTMENT, intensity)));
        }
        if (budget.lateralityGap() > 0.05) {
            double intensity = Math.min(1.0, budget.lateralityGap());
            frustrations.add(new CognitiveEmotion(
                    io.casehub.neocortex.cognitive.EmotionType.ANGER,
                    intensity, "capacity-laterality", now,
                    io.casehub.neocortex.cognitive.EmotionSource.INTRINSIC,
                    io.casehub.neocortex.cognitive.AlmaPadTable.project(
                            io.casehub.neocortex.cognitive.EmotionType.ANGER, intensity)));
        }

        if (!frustrations.isEmpty()) {
            var combined = new AppraisalResult(
                    frustrations, List.of(), HabituationState.empty(), null);
            bridgeToMood(combined, context);
        }
    }

    private void bridgeToMood(AppraisalResult result, CognitionTickContext context) {
        if (result.emotions().isEmpty()) return;

        double totalIntensity = result.emotions().stream()
                .mapToDouble(CognitiveEmotion::intensity).sum();
        if (totalIntensity <= 0) return;

        double p = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().pleasure()).sum() / totalIntensity;
        double a = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().arousal()).sum() / totalIntensity;
        double d = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().dominance()).sum() / totalIntensity;

        moodOrchestrator.record(
                new MoodSignal.DirectShift(clamp(p), clamp(a), clamp(d), "appraisal-emotions"),
                context.agentId(), context.tenantId());
    }

    private static double clamp(double value) {
        return Math.max(-2.0, Math.min(2.0, value));
    }
}
