package io.casehub.neocortex.cognition.core;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.NeedTierMappingProvider;
import io.casehub.neocortex.cognition.goal.DriveGoalBridgeParticipant;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.gut.GutFeelingParticipant;
import io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator;
import io.casehub.neocortex.cognition.memory.MemoryHygieneOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.CueType;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalStateSignal;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognition.mood.MoodTick;
import io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator;
import io.casehub.neocortex.cognition.need.NeedTier;
import io.casehub.neocortex.cognition.prompt.AppraisalPromptSection;
import io.casehub.neocortex.cognition.prompt.AttentionPromptSection;
import io.casehub.neocortex.cognition.prompt.BehavioralPromptSection;
import io.casehub.neocortex.cognition.prompt.ChannelEmphasisPromptSection;
import io.casehub.neocortex.cognition.prompt.CharacterDrivePromptSection;
import io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer;
import io.casehub.neocortex.cognition.prompt.ConsolidationPromptSection;
import io.casehub.neocortex.cognition.prompt.ConstraintPromptSection;
import io.casehub.neocortex.cognition.prompt.DirectiveSection;
import io.casehub.neocortex.cognition.prompt.DrivePromptSection;
import io.casehub.neocortex.cognition.prompt.EmergentGoalPromptSection;
import io.casehub.neocortex.cognition.prompt.MentalModelPromptSection;
import io.casehub.neocortex.cognition.prompt.MoodPromptSection;
import io.casehub.neocortex.cognition.prompt.NarrativePromptSection;
import io.casehub.neocortex.cognition.prompt.NeedsPyramidPromptSection;
import io.casehub.neocortex.cognition.prompt.OtherModelPromptSection;
import io.casehub.neocortex.cognition.prompt.ReflectionPromptSection;
import io.casehub.neocortex.cognition.prompt.StrategyPromptSection;
import io.casehub.neocortex.cognition.prompt.TemporalFocusPromptSection;
import io.casehub.neocortex.cognition.prompt.UserModelPromptSection;
import io.casehub.neocortex.cognition.strategy.EngagementSignal;
import io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator;
import io.casehub.neocortex.cognition.temporal.ReflectionRetrievalOrchestrator;
import io.casehub.neocortex.cognition.temporal.TemporalFocusOrchestrator;
import io.casehub.neocortex.cognition.usermodel.InteractionSignal;
import io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator;
import io.casehub.neocortex.knowledge.TermNormalizer;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.engagement.EngagementEvent;
import io.casehub.neocortex.memory.mood.MoodState;
import io.casehub.neocortex.memory.relationship.QualitySignal;
import io.casehub.neocortex.mindmap.AttentionBriefing;
import io.casehub.neocortex.mindmap.ConsolidationArtifact;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public class CognitionCore implements io.casehub.neocortex.cognition.trace.CognitionStateProvider {

    private static final System.Logger LOG =
            System.getLogger(CognitionCore.class.getName());
    private static final String MOOD_APPRAISAL_PROMPT = """
            Rate the emotional tone of this conversation exchange from the \
            perspective of the agent who just responded. Score each PAD axis \
            as a delta (change from neutral):
            - pleasure: how pleasant/unpleasant was this exchange? [-0.3, +0.3]
            - arousal: how energizing/calming? [-0.3, +0.3]
            - dominance: how empowering/diminishing? [-0.3, +0.3]

            Respond with JSON only:
            {"pleasure":0.15,"arousal":0.1,"dominance":0.05,"cause":"mutual intellectual recognition"}""";
    private static final String BDI_EXTRACTION_PROMPT = """
            Extract mental state signals from what this person said. \
            Classify each statement as a belief (what they think is true), \
            desire (what they want), or intention (what they plan to do).

            Respond with JSON only. Use short descriptive keys (2-4 words, \
            kebab-case). Only include clear, confident signals — skip vague \
            or ambiguous statements.

            {"beliefs":[{"key":"values-universal-energy","text":"Believes energy should be freely available to all","type":"BELIEF_STATEMENT"}],\
            "desires":[{"key":"recognition-for-ac","text":"Wants recognition for alternating current work","type":"DESIRE_EXPRESSION"}],\
            "intentions":[{"key":"build-wardenclyffe","text":"Plans to build the Wardenclyffe tower","type":"INTENTION_DECLARATION"}]}""";

    private final MoodOrchestrator mood;
    private final DriveOrchestrator drives;
    private final @Nullable UserModelOrchestrator userModel;
    private final @Nullable MentalModelOrchestrator mentalModel;
    private final @Nullable StrategyLearningOrchestrator strategy;
    private final @Nullable NarrativeOrchestrator narrative;
    private final @Nullable GoalProposalOrchestrator goals;
    private final @Nullable MemoryHygieneOrchestrator memoryHygiene;
    private final @Nullable SessionPipeline sessionPipeline;
    private final CognitionConfig config;
    private final Map<CognitionPhase, List<CognitionTickParticipant>> customParticipants = new EnumMap<>(CognitionPhase.class);
    private final @Nullable MindMapStore mindMapStore;
    private final Map<String, Set<NeedTier>> needTierMapping;
    private final @Nullable CognitiveAttentionMediator attentionMediator;
    private final @Nullable Consumer<EngagementEvent> engagementPersister;
    private final @Nullable TemporalFocusOrchestrator temporalFocus;
    private final @Nullable ReflectionRetrievalOrchestrator reflectionOrchestrator;
    private final @Nullable InnerLifeOrchestrator innerLife;
    private final @Nullable ConsolidationMediator consolidationMediator;

    private volatile @Nullable AttentionBriefing lastBriefing;
    private volatile @Nullable AgentDescriptor lastDescriptor;
    private volatile @Nullable List<ConsolidationArtifact> lastConsolidationArtifacts;
    private volatile @Nullable Map<String, io.casehub.neocortex.mindmap.ContinuumPosition> lastContinuumMap;

    private UnaryOperator<List<CognitionPromptRenderer>> sectionCustomizer;
    private volatile @Nullable AppraisalTickParticipant  appraisalParticipant;
    private volatile @Nullable GutFeelingParticipant gutFeelingParticipant;
    private volatile io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant subThoughtParticipant;
    private volatile io.casehub.neocortex.cognition.regulation.EmotionRegulationTickParticipant regulationParticipant;


    private volatile @Nullable Consumer<MoodState> moodPersister;
    private volatile String lastAgentId;
    private volatile String lastTenantId;
    private volatile io.casehub.neocortex.cognition.prompt.TierFilterCustomizer tierFilter;
    private volatile io.casehub.neocortex.cognitive.index.@Nullable CognitiveDefaultsRegistry defaultsRegistry;
private volatile @Nullable String lastTargetAgentId;
private volatile           io.casehub.neocortex.cognitive.index.@org.jspecify.annotations.Nullable CognitiveMemoryRetriever cognitiveMemoryRetriever;
private volatile           io.casehub.neocortex.mindmap.intelligence.consolidation.@org.jspecify.annotations.Nullable BehavioralSynthesisPhase behavioralSynthesis;
private volatile           io.casehub.neocortex.cognitive.observability.@org.jspecify.annotations.Nullable PipelineSnapshotStore               pipelineSnapshotStore;
private final              java.util.concurrent.atomic.AtomicInteger                                                                           tickCounter = new java.util.concurrent.atomic.AtomicInteger();


    private final java.util.concurrent.atomic.AtomicLong moodSuccess = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong moodSkipped = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong moodFallback = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong moodError = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bdiSuccess = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bdiFallback = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bdiSkipped = new java.util.concurrent.atomic.AtomicLong();
    private static final double MOOD_FALLBACK_DECAY = 0.5;
    private final java.util.concurrent.ConcurrentHashMap<String, double[]> lastMoodDelta = new java.util.concurrent.ConcurrentHashMap<>();

    public CognitionCore(MoodOrchestrator mood,
                         DriveOrchestrator drives,
                         @Nullable UserModelOrchestrator userModel,
                         @Nullable MentalModelOrchestrator mentalModel,
                         @Nullable StrategyLearningOrchestrator strategy,
                         @Nullable NarrativeOrchestrator narrative,
                         @Nullable GoalProposalOrchestrator goals,
                         @Nullable MemoryHygieneOrchestrator memoryHygiene) {
        this(mood, drives, userModel, mentalModel, strategy, narrative,
             goals, memoryHygiene, null, CognitionConfig.all(), null);
    }

    public CognitionCore(MoodOrchestrator mood,
                         DriveOrchestrator drives,
                         @Nullable UserModelOrchestrator userModel,
                         @Nullable MentalModelOrchestrator mentalModel,
                         @Nullable StrategyLearningOrchestrator strategy,
                         @Nullable NarrativeOrchestrator narrative,
                         @Nullable GoalProposalOrchestrator goals,
                         @Nullable MemoryHygieneOrchestrator memoryHygiene,
                         @Nullable InnerLifeOrchestrator innerLife,
                         CognitionConfig config,
                         @Nullable SessionPipeline sessionPipeline) {
        this(mood, drives, userModel, mentalModel, strategy, narrative,
             goals, memoryHygiene, innerLife, config, null, null, null, null, null, null, null, sessionPipeline);
    }

    public CognitionCore(MoodOrchestrator mood,
                         DriveOrchestrator drives,
                         @Nullable UserModelOrchestrator userModel,
                         @Nullable MentalModelOrchestrator mentalModel,
                         @Nullable StrategyLearningOrchestrator strategy,
                         @Nullable NarrativeOrchestrator narrative,
                         @Nullable GoalProposalOrchestrator goals,
                         @Nullable MemoryHygieneOrchestrator memoryHygiene,
                         @Nullable InnerLifeOrchestrator innerLife,
                         CognitionConfig config,
                         @Nullable MindMapStore mindMapStore,
                         @Nullable NeedTierMappingProvider needTierMappingProvider,
                         @Nullable CognitiveAttentionMediator attentionMediator,
                         @Nullable Consumer<EngagementEvent> engagementPersister,
                         @Nullable TemporalFocusOrchestrator temporalFocus,
                         @Nullable ReflectionRetrievalOrchestrator reflectionOrchestrator,
                         @Nullable ConsolidationMediator consolidationMediator,
                         @Nullable SessionPipeline sessionPipeline) {
        this.mood = mood;
        this.drives = drives;
        this.userModel = userModel;
        this.mentalModel = mentalModel;
        this.strategy = strategy;
        this.narrative = narrative;
        this.goals = goals;
        this.memoryHygiene = memoryHygiene;
        this.innerLife = config.innerLifeEnabled() ? innerLife : null;
        this.config = config;
        this.mindMapStore = mindMapStore;
        this.needTierMapping = (needTierMappingProvider != null ? needTierMappingProvider : NeedTierMappingProvider.empty()).tierMapping();
        this.attentionMediator = attentionMediator;
        this.engagementPersister = engagementPersister;
        this.temporalFocus = temporalFocus;
        this.reflectionOrchestrator = reflectionOrchestrator;
        this.consolidationMediator = consolidationMediator;
        this.sessionPipeline = sessionPipeline;
    }

    public void tick(String agentId, String tenantId,
                     @Nullable AgentDescriptor descriptor,
                     SubjectResolver resolver) {
        tick(agentId, tenantId, descriptor, resolver, null, null);
    }

    public void tick(String agentId, String tenantId,
                     @Nullable AgentDescriptor descriptor,
                     SubjectResolver resolver,
                     @Nullable String observation) {
        tick(agentId, tenantId, descriptor, resolver, observation, null);
    }

    public void tick(String agentId, String tenantId,
                     @Nullable AgentDescriptor descriptor,
                     SubjectResolver resolver,
                     @Nullable String observation,
                     @Nullable String targetAgentId) {tick(agentId, tenantId, descriptor, resolver, observation, targetAgentId, null);}

    public void tick(String agentId, String tenantId,
                     @Nullable AgentDescriptor descriptor,
                     SubjectResolver resolver,
                     @Nullable String observation,
                     @Nullable String targetAgentId,
                     io.casehub.neocortex.cognition.regulation.@Nullable RegulationContext regulationContext) {
        this.lastDescriptor    = descriptor;
        this.lastAgentId       = agentId;
        this.lastTenantId      = tenantId;
        this.lastTargetAgentId = targetAgentId;
        this.lastBriefing      = null;
        if (attentionMediator != null) {
            var briefing = attentionMediator.drainAttention(agentId);
            if (config.attentionEnabled()) {
                this.lastBriefing = briefing.orElse(null);
            }
        }
        this.lastConsolidationArtifacts = null;
        this.lastContinuumMap = null;
        if (consolidationMediator != null && config.consolidationEnabled()) {
            var artifacts = consolidationMediator.drainForAgent(agentId, tenantId);
            if (!artifacts.isEmpty()) {
                this.lastConsolidationArtifacts = artifacts;
                var continuumMap = new java.util.HashMap<String, io.casehub.neocortex.mindmap.ContinuumPosition>();
                for (var artifact : artifacts) {
                    if (artifact instanceof ConsolidationArtifact.RecurringThoughtDetected r) {
                        continuumMap.put(r.assessment().topicNodeId(), r.assessment().position());
                    }
                }
                if (!continuumMap.isEmpty()) {
                    this.lastContinuumMap = java.util.Map.copyOf(continuumMap);
                }
            }
        }
        if (config.temporalFocusEnabled() && temporalFocus != null) {
            safeRun(() -> temporalFocus.tick(agentId, tenantId,
                                             resolver.relevantSubjects(agentId, tenantId)));
        }
        var context = new CognitionTickContext(agentId, tenantId, descriptor, resolver, observation, this.lastBriefing, targetAgentId, regulationContext);

        if (config.moodEnabled()) {
            safeRun(() -> tickMood(agentId, tenantId));
        }
        if (config.memoryHygieneEnabled() && memoryHygiene != null) {
            safeRun(() -> memoryHygiene.tick(agentId, tenantId));
        }
        runCustomParticipants(CognitionPhase.FOUNDATION, context);

        if (cognitiveMemoryRetriever != null) {
            var moodState = mood.currentMood(agentId, tenantId).orElse(null);
            var personality = defaultsRegistry != null
                              ? defaultsRegistry.forAgentOrDefaults(agentId).personality()
                              : null;
            cognitiveMemoryRetriever.setTickContext(
                    new io.casehub.neocortex.cognitive.index.ModulationContext(
                            agentId, tenantId, moodState, personality, Set.of()));
        }

        if (config.narrativeEnabled() && narrative != null) {
            safeRun(() -> narrative.tick(agentId, tenantId));
        }
        if (config.strategyEnabled() && strategy != null) {
            safeRun(() -> strategy.tick(agentId, tenantId));
        }
        if (config.reflectionEnabled() && reflectionOrchestrator != null) {
            safeRun(() -> reflectionOrchestrator.tick(agentId, tenantId));
        }
        runCustomParticipants(CognitionPhase.SOURCE, context);

        for (String subjectId : resolver.relevantSubjects(agentId, tenantId)) {
            if (config.userModelEnabled() && userModel != null) {
                safeRun(() -> userModel.tick(agentId, subjectId, tenantId));
            }
            if (config.mentalModelEnabled() && mentalModel != null) {
                safeRun(() -> mentalModel.tick(agentId, subjectId, tenantId));
            }
        }

        if (config.drivesEnabled() && descriptor != null) {
            safeRun(() -> drives.tick(agentId, tenantId, descriptor));
        }
        runCustomParticipants(CognitionPhase.DERIVED, context);

        // Appraisal→mood→drive propagation: bridge appraisal emotions into
        // mood signals, then re-tick mood and drives so prompt sections reflect
        // the current appraisal state within the same tick cycle.
        if (config.appraisalEnabled() && appraisalParticipant != null) {
            appraisalParticipant.currentResult(agentId, tenantId).ifPresent(r -> {
                if (config.moodEnabled()) {
                    bridgeAppraisalToMood(r, agentId, tenantId);
                }
                if (config.drivesEnabled()) {
                    var driveDeltas = io.casehub.neocortex.cognition.drive.AppraisalDriveModulator.modulate(r.emotions());
                    drives.recordAppraisalModulation(agentId, tenantId, driveDeltas);
                }
            });
            if (config.moodEnabled()) {
                safeRun(() -> tickMood(agentId, tenantId));
            }
            if (config.drivesEnabled() && descriptor != null) {
                safeRun(() -> drives.tick(agentId, tenantId, descriptor));
            }
        }

        if (config.goalsEnabled() && goals != null && descriptor != null) {
            safeRun(() -> goals.tick(agentId, tenantId, descriptor));
        }
        runCustomParticipants(CognitionPhase.TERMINAL, context);

        if (pipelineSnapshotStore != null) {
            int tick = tickCounter.incrementAndGet();
            currentTrace(agentId, tenantId).ifPresent(trace ->
                    pipelineSnapshotStore.store(tenantId, agentId, tick, java.time.Instant.now(), trace));
        }
    }


    public void recordInteraction(String agentId, String tenantId,
                                  @Nullable String subjectId,
                                  String userMessage, String response,
                                  @Nullable CognitiveImpact impact) {
        if (config.moodEnabled()) {
            if (impact != null && impact.moodSignal() != null) {
                mood.record(impact.moodSignal(), agentId, tenantId);
            } else {
                safeRun(() -> appraiseMood(agentId, tenantId, userMessage, response));
            }
        }

        if (subjectId != null) {
            if (config.userModelEnabled() && userModel != null) {
                var signal = (impact != null && impact.userModelSignal() != null)
                             ? impact.userModelSignal()
                             : deriveQualitySignal(agentId, tenantId, userMessage);
                safeRun(() -> userModel.record(signal, agentId, subjectId, tenantId));
            }
            if (config.mentalModelEnabled() && mentalModel != null
                && (impact == null || !impact.suppressBdiExtraction())) {
                safeRun(() -> extractAndRecordMentalState(
                        agentId, subjectId, tenantId, userMessage));
            }
            if (config.strategyEnabled() && strategy != null) {
                if (impact != null && impact.strategySignal() != null) {
                    var strategySignal = impact.strategySignal();
                    safeRun(() -> strategy.record(strategySignal,
                                                  agentId, subjectId, tenantId));
                } else {
                    String caseId = (impact != null && impact.conversationId() != null)
                                    ? impact.conversationId() : null;
                    safeRun(() -> strategy.record(
                            new EngagementSignal.TurnOutcome(
                                    new EngagementEvent(agentId, subjectId,
                                                        tenantId, caseId,
                                                        UUID.randomUUID().toString(),
                                                        Instant.now(),
                                                        userMessage.isBlank()
                                                        ? "[interaction]"
                                                        : userMessage,
                                                        null, Map.of(), true, null,
                                                        (int) response.length(),
                                                        null, null, null),
                                    Map.of(), response),
                            agentId, subjectId, tenantId));
                }
            }
            if (engagementPersister != null) {
                var engagementEvent = (impact != null && impact.strategySignal() instanceof EngagementSignal.TurnOutcome t)
                                      ? t.event()
                                      : new EngagementEvent(agentId, subjectId,
                                              tenantId,
                                              (impact != null && impact.conversationId() != null)
                                                  ? impact.conversationId() : null,
                                              UUID.randomUUID().toString(),
                                              Instant.now(),
                                              userMessage.isBlank() ? "[interaction]" : userMessage,
                                              null, Map.of(), true, null,
                                              (int) response.length(),
                                              null, null, null);
                safeRun(() -> engagementPersister.accept(engagementEvent));
            }
        }
    }

    public record CognitiveTickMetrics(long moodSuccess, long moodFallback, long moodSkipped, long moodError,
                                       long bdiSuccess, long bdiFallback, long bdiSkipped) {
        public long moodTotal() { return moodSuccess + moodFallback + moodSkipped + moodError; }
        public long bdiTotal() { return bdiSuccess + bdiFallback + bdiSkipped; }
        public double moodSuccessRate() { return moodTotal() == 0 ? 0 : (double) moodSuccess / moodTotal(); }
        public double moodSignalRate() { return moodTotal() == 0 ? 0 : (double) (moodSuccess + moodFallback) / moodTotal(); }
        public double bdiSuccessRate() { return bdiTotal() == 0 ? 0 : (double) bdiSuccess / bdiTotal(); }
    }

    public CognitiveTickMetrics tickMetrics() {
        return new CognitiveTickMetrics(
            moodSuccess.get(), moodFallback.get(), moodSkipped.get(), moodError.get(),
            bdiSuccess.get(), bdiFallback.get(), bdiSkipped.get());
    }

    public void seedMoodFallback(String agentId, double p, double a, double d) {
        lastMoodDelta.put(agentId, new double[]{p, a, d});
    }

    private void appraiseMood(String agentId, String tenantId,
                              String userMessage, String response) {
        if (sessionPipeline == null) {
            applyMoodFallback(agentId, tenantId);
            return;
        }
        long t0 = System.nanoTime();
        try {
            var truncatedMsg = userMessage.length() > 200
                               ? userMessage.substring(0, 200) + "..." : userMessage;
            var truncatedResp = response.length() > 200
                                ? response.substring(0, 200) + "..." : response;
            var userPrompt = "Other person said:\n" + truncatedMsg
                             + "\n\nAgent responded:\n" + truncatedResp;
            var session = sessionPipeline.openSession("mood-appraisal-" + agentId, AgentSessionInit.of(MOOD_APPRAISAL_PROMPT));
            var responseText = new StringBuilder();
            session.query(userPrompt).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseText::append);
            var json = responseText.toString();
            var jsonStart = json.indexOf('{');
            var jsonEnd = json.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = json.substring(jsonStart, jsonEnd + 1);
                double p = extractDouble(json, "pleasure");
                double a = extractDouble(json, "arousal");
                double d = extractDouble(json, "dominance");
                var cause = extractJsonString(json, "cause");
                p = Math.clamp(p, -0.3, 0.3);
                a = Math.clamp(a, -0.3, 0.3);
                d = Math.clamp(d, -0.3, 0.3);
                lastMoodDelta.put(agentId, new double[]{p, a, d});
                mood.record(new MoodSignal.InteractionAppraisal(p, a, d,
                                                                cause.isEmpty() ? "interaction" : cause),
                            agentId, tenantId);
                moodSuccess.incrementAndGet();
                double fp = p; double fa = a; double fd = d;
                LOG.log(System.Logger.Level.INFO,
                        () -> "%s: mood-appraisal %dms P=%.3f A=%.3f D=%.3f cause=%s".formatted(
                                agentId, (System.nanoTime() - t0) / 1_000_000, fp, fa, fd, cause));
            }
        } catch (Exception e) {
            applyMoodFallback(agentId, tenantId);
            moodError.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "Mood appraisal failed, applied fallback", e);
        }
    }

    private void applyMoodFallback(String agentId, String tenantId) {
        double[] prior = lastMoodDelta.get(agentId);
        if (prior == null) {
            moodSkipped.incrementAndGet();
            return;
        }
        double p = prior[0] * MOOD_FALLBACK_DECAY;
        double a = prior[1] * MOOD_FALLBACK_DECAY;
        double d = prior[2] * MOOD_FALLBACK_DECAY;
        prior[0] = p;
        prior[1] = a;
        prior[2] = d;
        if (Math.abs(p) < 0.01 && Math.abs(a) < 0.01 && Math.abs(d) < 0.01) {
            lastMoodDelta.remove(agentId);
            moodSkipped.incrementAndGet();
            return;
        }
        mood.record(new MoodSignal.InteractionAppraisal(p, a, d, "fallback"), agentId, tenantId);
        moodFallback.incrementAndGet();
    }

    private InteractionSignal deriveQualitySignal(String agentId, String tenantId,
                                                  String userMessage) {
        QualitySignal quality = QualitySignal.NEUTRAL;
        var currentMood = mood.currentMood(agentId, tenantId);
        if (currentMood.isPresent()) {
            var m = currentMood.get();
            double pleasure = m.pleasure();
            double arousal = m.arousal();
            if (arousal > 0.3 && pleasure > 0.1) {
                quality = QualitySignal.POSITIVE;
            } else if (arousal < -0.1 && pleasure < -0.1) {
                quality = QualitySignal.NEGATIVE;
            }
        }
        return new InteractionSignal.CustomSignal(userMessage, quality);
    }

    public void deferredBdiExtraction(String agentId, String subjectId,
                                       String tenantId, String utterance) {
        if (config.mentalModelEnabled() && mentalModel != null) {
            extractAndRecordMentalState(agentId, subjectId, tenantId, utterance);
        }
    }

    private void extractAndRecordMentalState(String agentId, String subjectId,
                                             String tenantId, String utterance) {
        if (sessionPipeline != null) {
            long t0 = System.nanoTime();
            try {
                var truncated = utterance.length() > 500
                                ? utterance.substring(0, 500) + "..." : utterance;
                var userPrompt = "What " + subjectId + " said:\n" + truncated;
                var session = sessionPipeline.openSession("bdi-extraction-" + agentId, AgentSessionInit.of(BDI_EXTRACTION_PROMPT));
                var responseText = new StringBuilder();
                session.query(userPrompt).subscribe().asStream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .forEach(responseText::append);
                var json = responseText.toString();
                recordExtractedSignals(agentId, subjectId, tenantId, json);
                bdiSuccess.incrementAndGet();
                LOG.log(System.Logger.Level.INFO,
                        () -> "%s: bdi-extraction %dms".formatted(
                                agentId, (System.nanoTime() - t0) / 1_000_000));
                return;
            } catch (Exception e) {
                bdiFallback.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING,
                        "LLM BDI extraction failed, falling back to heuristic", e);
            }
        } else {
            bdiSkipped.incrementAndGet();
        }
        mentalModel.record(new MentalStateSignal.VerbalCue(
                utterance.length() > 100 ? utterance.substring(0, 100) : utterance,
                CueType.BELIEF_STATEMENT), agentId, subjectId, tenantId);
    }

    private void recordExtractedSignals(String agentId, String subjectId,
                                        String tenantId, String json) {
        var jsonStart = json.indexOf('{');
        var jsonEnd = json.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd < 0) return;
        json = json.substring(jsonStart, jsonEnd + 1);

        recordSignalArray(agentId, subjectId, tenantId, json,
                          "beliefs", CueType.BELIEF_STATEMENT);
        recordSignalArray(agentId, subjectId, tenantId, json,
                          "desires", CueType.DESIRE_EXPRESSION);
        recordSignalArray(agentId, subjectId, tenantId, json,
                          "intentions", CueType.INTENTION_DECLARATION);
    }

    private void recordSignalArray(String agentId, String subjectId,
                                   String tenantId, String json,
                                   String arrayName, CueType cueType) {
        var arrayPattern = Pattern.compile(
                "\"" + arrayName + "\"\\s*:\\s*\\[([^\\]]*)]");
        var arrayMatcher = arrayPattern.matcher(json);
        if (!arrayMatcher.find()) return;
        var arrayContent = arrayMatcher.group(1);

        var itemPattern = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
        var itemMatcher = itemPattern.matcher(arrayContent);
        while (itemMatcher.find()) {
            var text = itemMatcher.group(1);
            mentalModel.record(new MentalStateSignal.VerbalCue(text, cueType),
                               agentId, subjectId, tenantId);
        }
    }

    private static double extractDouble(String json, String key) {
        var pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+\\.?\\d*)");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    private static String extractJsonString(String json, String key) {
        var pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        var matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    public CognitionConfig config() { return config; }
    public MoodOrchestrator mood() { return mood; }
    public DriveOrchestrator drives() { return drives; }
    public @Nullable UserModelOrchestrator userModel() { return userModel; }
    public @Nullable MentalModelOrchestrator mentalModel() { return mentalModel; }
    public @Nullable StrategyLearningOrchestrator strategy() { return strategy; }
    public @Nullable NarrativeOrchestrator narrative() { return narrative; }
    public @Nullable GoalProposalOrchestrator goals() { return goals; }
    public @Nullable MemoryHygieneOrchestrator memoryHygiene() { return memoryHygiene; }
    public @Nullable InnerLifeOrchestrator innerLife() { return innerLife; }
    public @Nullable AttentionBriefing lastBriefing() { return lastBriefing; }
    public @Nullable AgentDescriptor lastDescriptor() { return lastDescriptor; }

    public java.util.List<String> activatedDriveCategories() {
        if (appraisalParticipant == null || lastAgentId == null || lastTenantId == null) {
            return List.of();
        }
        var thresholds = appraisalParticipant.thresholds();
        var activated  = new java.util.LinkedHashSet<String>();

        if (drives != null) {
            var profileOpt = drives.currentDrives(lastAgentId, lastTenantId);
            if (profileOpt.isPresent()) {
                profileOpt.get().allDrives().stream()
                          .filter(d -> d.intensity() > thresholds.driveActivationThreshold())
                          .map(io.casehub.neocortex.cognition.appraisal.Drive::name)
                          .forEach(activated::add);
            }
        }

        appraisalParticipant.currentConcernSalience(lastAgentId, lastTenantId)
                            .entrySet().stream()
                            .filter(e -> e.getValue() > thresholds.concernSalienceThreshold())
                            .map(java.util.Map.Entry::getKey)
                            .forEach(activated::add);

        return List.copyOf(activated);
    }


    public List<CognitionPromptRenderer> promptSections() {
        var sections = new ArrayList<CognitionPromptRenderer>();
        var desc     = lastDescriptor;
        if (desc != null && desc.constraints() != null && !desc.constraints().isEmpty()) {
            var softConstraints = desc.constraints().stream()
                                      .filter(c -> c.severity() != ConstraintSeverity.HARD)
                                      .toList();
            if (!softConstraints.isEmpty()) {
                sections.add(new ConstraintPromptSection(softConstraints));
            }
        }
        var appraisalActive = config.appraisalEnabled() && appraisalParticipant != null;
        if (isEnabled(config.moodEnabled(), AttentionRelevance.MOOD) && !appraisalActive) {
            sections.add(new MoodPromptSection(mood));
        }
        if (isEnabled(config.drivesEnabled(), AttentionRelevance.DRIVES)) {
            sections.add(new DrivePromptSection(drives));
        }
        if (config.narrativeEnabled() && narrative != null) {
            sections.add(new NarrativePromptSection(narrative));
        }
        if (isEnabled(config.userModelEnabled(), AttentionRelevance.USER_MODEL) && userModel != null) {
            sections.add(new UserModelPromptSection(userModel));
        }
        if (isEnabled(config.mentalModelEnabled(), AttentionRelevance.MENTAL_MODEL) && mentalModel != null) {
            sections.add(new MentalModelPromptSection(mentalModel));
        }
        if (config.subThoughtsEnabled() && subThoughtParticipant != null) {
            sections.add(new io.casehub.neocortex.cognition.subthought.SubThoughtPromptSection(subThoughtParticipant));
        }
        if (config.strategyEnabled() && strategy != null) {
            sections.add(new StrategyPromptSection(strategy));
        }
        if (isEnabled(config.goalsEnabled(), AttentionRelevance.GOALS) && goals != null) {
            sections.add(new EmergentGoalPromptSection(goals));
        }
        if (appraisalActive) {
            sections.add(new AppraisalPromptSection(appraisalParticipant));
            sections.add(new ChannelEmphasisPromptSection(appraisalParticipant));
        }
        if (config.characterDrivesEnabled() && !appraisalActive && mindMapStore != null) {
            sections.add(new CharacterDrivePromptSection(mindMapStore));
        }
        if (config.needsPyramidEnabled() && mindMapStore != null) {
            sections.add(new NeedsPyramidPromptSection(mindMapStore, needTierMapping));
        }
        if (config.behavioralEnabled() && mindMapStore != null) {
            sections.add(new BehavioralPromptSection(mindMapStore, gutFeelingParticipant));
        }
        if (config.otherModelEnabled() && mindMapStore != null) {
            sections.add(new OtherModelPromptSection(mindMapStore));
        }
        if (config.attentionEnabled() && lastBriefing != null) {
            sections.add(new AttentionPromptSection(lastBriefing));
        }
        if (config.consolidationEnabled() && lastConsolidationArtifacts != null
            && !lastConsolidationArtifacts.isEmpty()) {
            sections.add(new ConsolidationPromptSection(lastConsolidationArtifacts));
        }
        if (config.temporalFocusEnabled() && temporalFocus != null) {
            var items = temporalFocus.lastFocus();
            if (!items.isEmpty()) {
                sections.add(new TemporalFocusPromptSection(items));
            }
        }
        if (config.reflectionEnabled() && reflectionOrchestrator != null) {
            var reflections = reflectionOrchestrator.lastReflections();
            if (!reflections.isEmpty()) {
                sections.add(new ReflectionPromptSection(reflections));
            }
        }
        if (sectionCustomizer != null) {
            sections = new ArrayList<>(sectionCustomizer.apply(sections));
        }
        if (config.directivePrompts()) {
            return sections.stream()
                .map(DirectiveSection::wrap)
                .sorted(java.util.Comparator.comparingInt(s ->
                    s.blockTag() != null ? s.blockTag().sortOrdinal() : 999))
                .toList();
        }
        return sections;
    }

    public void setSectionCustomizer(UnaryOperator<List<CognitionPromptRenderer>> customizer) {
        this.sectionCustomizer = customizer;
    }

    public void chainSectionCustomizer(UnaryOperator<List<CognitionPromptRenderer>> next) {
        var prev = this.sectionCustomizer;
        this.sectionCustomizer = prev == null
                                 ? next
                                 : sections -> next.apply(new ArrayList<>(prev.apply(sections)));
    }

    public void setAppraisalParticipant(AppraisalTickParticipant appraisalParticipant) {
        this.appraisalParticipant = appraisalParticipant;
    }

    public @Nullable AppraisalTickParticipant appraisalParticipant() {
        return appraisalParticipant;
    }
public @Nullable String lastTargetAgentId() {
    return lastTargetAgentId;
}


    public void configureAppraisal(
            io.casehub.neocortex.cognition.appraisal.AppraisalStrategy appraisalStrategy,
            io.casehub.neocortex.cognition.appraisal.SalienceStrategy salienceStrategy,
            io.casehub.neocortex.cognitive.index.@Nullable CognitiveDefaultsRegistry defaultsRegistry) {
        configureAppraisal(appraisalStrategy, salienceStrategy, defaultsRegistry, null);
    }

    public void configureAppraisal(
            io.casehub.neocortex.cognition.appraisal.AppraisalStrategy appraisalStrategy,
            io.casehub.neocortex.cognition.appraisal.SalienceStrategy salienceStrategy,
            io.casehub.neocortex.cognitive.index.@Nullable CognitiveDefaultsRegistry defaultsRegistry,
            io.casehub.neocortex.caps.@Nullable SituationClassifier situationClassifier) {
        this.appraisalParticipant = new io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant(
                salienceStrategy, appraisalStrategy, drives, mood, defaultsRegistry, config, mindMapStore,
                io.casehub.neocortex.cognition.appraisal.AppraisalThresholds.defaults(), situationClassifier);
        addParticipant(CognitionPhase.DERIVED, this.appraisalParticipant);
    }

    public void configureAppraisal(
            io.casehub.neocortex.cognition.appraisal.AppraisalStrategy appraisalStrategy,
            io.casehub.neocortex.cognition.appraisal.SalienceStrategy salienceStrategy) {
        configureAppraisal(appraisalStrategy, salienceStrategy, this.defaultsRegistry);
    }

    public void configureRegulation() {
        if (!config.regulationEnabled() || appraisalParticipant == null) {return;}

        var loadEstimator = new io.casehub.neocortex.cognition.regulation.CognitiveLoadEstimator(
                drives, goals, mentalModel);
        var generationPhase = sessionPipeline != null
                              ? new io.casehub.neocortex.cognition.regulation.EmotionRegulationGenerationPhase(
                                      sessionPipeline, loadEstimator)
                              : null;

        String cogSgId = null;
        if (mindMapStore != null) {
            var sgList = mindMapStore.listSubgraphs(lastTenantId != null ? lastTenantId : "default");
            for (var sg : sgList) {
                if (io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE.equals(sg.type())) {
                    cogSgId = sg.id();
                    break;
                }
            }
        }

        this.regulationParticipant = new io.casehub.neocortex.cognition.regulation.EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, mindMapStore, cogSgId,
                defaultsRegistry, loadEstimator);
        addParticipant(CognitionPhase.DERIVED, this.regulationParticipant);
    }

    public void configureRegulation(io.casehub.neocortex.cognition.regulation.EmotionRegulationGenerationPhase generationPhase) {
        if (!config.regulationEnabled() || appraisalParticipant == null) {return;}
        this.regulationParticipant = new io.casehub.neocortex.cognition.regulation.EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, mindMapStore, null);
        addParticipant(CognitionPhase.DERIVED, this.regulationParticipant);
    }

    public void configureRegulation(io.casehub.neocortex.caps.CapsEngine capsEngine,
                                    io.casehub.neocortex.cognition.regulation.RegulationCascadeConfig cascadeConfig) {
        if (!config.regulationEnabled() || appraisalParticipant == null) {return;}

        var loadEstimator = new io.casehub.neocortex.cognition.regulation.CognitiveLoadEstimator(
                drives, goals, mentalModel);
        var generationPhase = sessionPipeline != null
                              ? new io.casehub.neocortex.cognition.regulation.EmotionRegulationGenerationPhase(
                sessionPipeline, loadEstimator)
                              : null;

        String cogSgId = null;
        if (mindMapStore != null) {
            var sgList = mindMapStore.listSubgraphs(lastTenantId != null ? lastTenantId : "default");
            for (var sg : sgList) {
                if (io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE.equals(sg.type())) {
                    cogSgId = sg.id();
                    break;
                }
            }
        }

        this.regulationParticipant = new io.casehub.neocortex.cognition.regulation.EmotionRegulationTickParticipant(
                appraisalParticipant, config, generationPhase, mindMapStore, cogSgId,
                defaultsRegistry, loadEstimator, capsEngine, cascadeConfig);
        addParticipant(CognitionPhase.DERIVED, this.regulationParticipant);
    }


    public io.casehub.neocortex.cognition.regulation.@Nullable EmotionRegulationTickParticipant regulationParticipant() {
        return regulationParticipant;
    }

    private io.casehub.neocortex.cognition.regulation.ProactiveRegulationParticipant proactiveRegulationParticipant;

    public void configureProactiveRegulation(
            io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator goalOrchestrator,
            io.casehub.neocortex.mindmap.MindMapStore mindMapStore,
            io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry defaultsRegistry,
            String cognitiveSubgraphId,
            io.casehub.neocortex.cognition.regulation.ProactiveAvoidanceMode mode,
            int decayTimeConstant,
            double tickIntervalSeconds) {
        this.proactiveRegulationParticipant = new io.casehub.neocortex.cognition.regulation.ProactiveRegulationParticipant(
                mindMapStore, goalOrchestrator, defaultsRegistry,
                cognitiveSubgraphId, mode, decayTimeConstant, tickIntervalSeconds);
        addParticipant(CognitionPhase.FOUNDATION, this.proactiveRegulationParticipant);
    }

    public io.casehub.neocortex.cognition.regulation.ProactiveRegulationParticipant proactiveRegulationParticipant() {
        return proactiveRegulationParticipant;
    }


    public void configureRegulationGoals() {
        if (regulationParticipant == null || goals == null) {return;}
        var goalParticipant = new io.casehub.neocortex.cognition.regulation.EmotionRegulationGoalParticipant(
                regulationParticipant, goals);
        addParticipant(CognitionPhase.TERMINAL, goalParticipant);
    }


    public void setGutFeelingParticipant(GutFeelingParticipant participant) {
        this.gutFeelingParticipant = participant;
    }

    public void configureGutFeeling(
            @Nullable CaseMemoryStore memoryStore,
            @Nullable MoodOrchestrator moodOrchestrator) {
        if (memoryStore == null) {return;}
        this.gutFeelingParticipant = new GutFeelingParticipant(cognitiveMemoryRetriever, memoryStore, moodOrchestrator, config);
        addParticipant(CognitionPhase.DERIVED, this.gutFeelingParticipant);
    }

    /**
     * Activates the search budget cognitive exploration model.
     * Must be called AFTER configureAppraisal() and configureGutFeeling()
     * to ensure intra-DERIVED phase ordering — participants execute in
     * registration order.
     */
    public void configureSearchBudget(
            DriveOrchestrator driveOrchestrator,
            @Nullable MoodOrchestrator moodOrchestrator,
            io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry defaultsRegistry,
            io.casehub.neocortex.mindmap.MindMapStore mindMapStore,
            io.casehub.neocortex.cognition.drive.@Nullable CuriosityDrive curiosityDrive,
            @Nullable CognitiveResourcePool cognitiveResourcePool) {
        var participant = new io.casehub.neocortex.cognition.search.SearchBudgetTickParticipant(
                driveOrchestrator,
                this.appraisalParticipant,
                this.gutFeelingParticipant,
                moodOrchestrator,
                defaultsRegistry,
                config,
                curiosityDrive,
                cognitiveResourcePool);
        addParticipant(CognitionPhase.DERIVED, participant);
        if (this.appraisalParticipant != null) {
            this.appraisalParticipant.setSearchBudgetAccessor(participant::currentBudget);
        }
        chainSectionCustomizer(sections -> {
            var result = new java.util.ArrayList<>(sections);
            result.add(new io.casehub.neocortex.cognition.search.SearchBudgetPromptSection(participant, mindMapStore, this.lastContinuumMap));
            return result;
        });
    }

    /**
     * Wire the curiosity prompt section into the cognitive pipeline.
     * Called after drives are initialised — the prompt section reads
     * gap data from CuriosityDrive and renders four-channel curiosity
     * with proportional baseline deviation and drive rank context.
     */
    public void configureCuriosity(
            io.casehub.neocortex.cognition.drive.CuriosityDrive curiosityDrive,
            io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer channelComputer,
            io.casehub.neocortex.mindmap.MindMapStore mindMapStore,
            double deprivationBias,
            double baselineIntensity) {
        chainSectionCustomizer(sections -> {
            var result = new java.util.ArrayList<>(sections);
            result.add(new io.casehub.neocortex.cognition.prompt.CuriosityPromptSection(
                    curiosityDrive, channelComputer, drives, mindMapStore,
                    deprivationBias, baselineIntensity, this.lastContinuumMap));
            return result;
        });
    }


    /** Platform must call this during CognitionCore initialization to activate the sub-thought tick pipeline. */
    public void configureSubThoughts(io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant participant) {
        this.subThoughtParticipant = participant;
        addParticipant(CognitionPhase.FOUNDATION, participant);
        if (drives != null) {
            drives.setSubThoughtParticipant(participant);
        }
    }


    public void configureDriveGoalBridge(MindMapStore mindMapStore, TermNormalizer termNormalizer) {
        if (config.goalsEnabled() && goals != null) {
            addParticipant(CognitionPhase.TERMINAL,
                           new DriveGoalBridgeParticipant(goals, mindMapStore, termNormalizer, config));
        }
    }

    public void configureCognitiveProfile(io.casehub.neocortex.cognitive.index.CognitiveProfile profile) {
        var participant = new CognitiveProfileParticipant(profile, temporalFocus, config);
        addParticipant(CognitionPhase.TERMINAL, participant);
        chainSectionCustomizer(sections -> {
            var result    = new ArrayList<>(sections);
            var knowledge = participant.lastEntityKnowledge();
            if (!knowledge.isEmpty()) {
                result.add(new io.casehub.neocortex.cognition.prompt.EntityKnowledgePromptSection(knowledge));
            }
            var socialComparisons = participant.lastSocialComparisons();
            if (!socialComparisons.isEmpty()) {
                result.add(new io.casehub.neocortex.cognition.prompt.SocialComparisonPromptSection(socialComparisons));
            }
            return result;
        });
    }

    public void configureDomainActivation(io.casehub.neocortex.cognitive.index.DomainActivation domainActivation) {
        if (mindMapStore == null) {return;}
        var participant = new DomainActivationParticipant(
                domainActivation, mindMapStore, consolidationMediator, config);
        addParticipant(CognitionPhase.TERMINAL, participant);
        chainSectionCustomizer(sections -> {
            var result   = new ArrayList<>(sections);
            var snapshot = participant.lastSnapshot();
            if (snapshot != null && snapshot.hasRenderableCorrelations()) {
                result.add(new io.casehub.neocortex.cognition.prompt.DomainActivationPromptSection(snapshot));
            }
            return result;
        });
    }

    public void configureCognitiveGoalOrchestrator(io.casehub.neocortex.cognition.goal.CognitiveGoalOrchestrator orchestrator) {
        addParticipant(CognitionPhase.TERMINAL, orchestrator);
    }

    public void configureGoalEmotionMoodBridge(io.casehub.neocortex.cognition.mood.GoalEmotionMoodBridge bridge) {
        addParticipant(CognitionPhase.TERMINAL, bridge);
    }

    public void configureCognitiveMemoryRetriever(io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever retriever) {
        this.cognitiveMemoryRetriever = retriever;
    }

    public io.casehub.neocortex.cognitive.index.@org.jspecify.annotations.Nullable CognitiveMemoryRetriever cognitiveMemoryRetriever() {
        return cognitiveMemoryRetriever;
    }

    public void configureBehavioralSynthesis(io.casehub.neocortex.mindmap.intelligence.consolidation.BehavioralSynthesisPhase phase) {
        this.behavioralSynthesis = phase;
    }

    public void configurePipelineSnapshotStore(io.casehub.neocortex.cognitive.observability.PipelineSnapshotStore store) {
        this.pipelineSnapshotStore = store;
    }

    @Override
    public java.util.Optional<io.casehub.neocortex.cognition.trace.PipelineTrace> currentTrace(String agentId, String tenantId) {
        if (lastAgentId == null) {return java.util.Optional.empty();}

        io.casehub.neocortex.cognition.appraisal.AppraisalResult      appraisal       = null;
        Map<String, Double>                                           concernSalience = Map.of();
        io.casehub.neocortex.cognition.appraisal.ChannelWeightProfile channelWeights  = null;
        io.casehub.neocortex.cognition.appraisal.HabituationState     habituation     = null;
        if (appraisalParticipant != null) {
            appraisal       = appraisalParticipant.currentResult(agentId, tenantId).orElse(null);
            concernSalience = appraisalParticipant.currentConcernSalience(agentId, tenantId);
            channelWeights  = appraisalParticipant.currentChannelWeights(agentId, tenantId).orElse(null);
            habituation     = appraisalParticipant.currentHabituation(agentId, tenantId);
        }

        io.casehub.neocortex.cognition.regulation.EmotionRegulationResult regulation = null;
        if (regulationParticipant != null) {
            regulation = regulationParticipant.currentResult(agentId, tenantId).orElse(null);
        }

        io.casehub.neocortex.cognition.trace.PipelineTrace.CapsSettlingSnapshot capsSnapshot = null;
        Map<String, Double>                                                     capsInputs   = Map.of();
        if (behavioralSynthesis != null) {
            capsInputs = behavioralSynthesis.lastInputActivations(agentId);
            var settling = behavioralSynthesis.lastSettlingResult(agentId);
            if (settling.isPresent()) {
                var sr = settling.get();
                capsSnapshot = new io.casehub.neocortex.cognition.trace.PipelineTrace.CapsSettlingSnapshot(
                        sr.activations(), sr.iterations(), sr.convergence().name(), sr.saturationRatio());
            }
        }

        var renderContext = new io.casehub.neocortex.cognition.prompt.CognitionRenderContext(
                agentId, tenantId, lastTargetAgentId, activatedDriveCategories());
        Map<String, String> renderedSections = new java.util.LinkedHashMap<>();
        for (var renderer : promptSections()) {
            try {
                String text = renderer.render(renderContext);
                if (text != null) {
                    var    tag = renderer.blockTag();
                    String key = tag != null ? tag.name() : renderer.getClass().getSimpleName();
                    renderedSections.put(key, text);
                }
            } catch (Exception e) {
                // skip sections with unresolvable dependencies
            }
        }

        return java.util.Optional.of(new io.casehub.neocortex.cognition.trace.PipelineTrace(
                appraisal, concernSalience, capsInputs, capsSnapshot,
                regulation, channelWeights, habituation,
                activatedDriveCategories(), renderedSections));
    }

    @Override
    public List<String> activeAgents(String tenantId) {
        if (lastAgentId != null && tenantId.equals(lastTenantId)) {
            return List.of(lastAgentId);
        }
        return List.of();
    }


    /**
     * Sets the cognitive defaults registry. When set, CognitionCore auto-configures:
     * - Tier filtering: reads personalityDominance per-agent from the registry
     * - Appraisal disposition: populates disposition axes per-agent in appraisal context
     * <p>
     * Call this once at setup. The core looks up the active agent's defaults
     * at render time and tick time — no per-agent configure calls needed.
     */
    public void setDefaultsRegistry(io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry registry) {
        this.defaultsRegistry = registry;
        configureTierFilterFromRegistry();
    }

    private void configureTierFilterFromRegistry() {
        if (defaultsRegistry == null) {return;}
        if (tierFilter != null) {return;}
        tierFilter = new io.casehub.neocortex.cognition.prompt.TierFilterCustomizer(
                () -> {
                    String aid = lastAgentId;
                    String tid = lastTenantId;
                    if (aid == null || tid == null) {return 0.0;}
                    return mood.currentMood(aid, tid)
                               .map(io.casehub.neocortex.memory.mood.MoodState::arousal)
                               .orElse(0.0);
                },
                () -> {
                    String aid = lastAgentId;
                    if (aid == null || defaultsRegistry == null) {return 0.5;}
                    return defaultsRegistry.forAgent(aid)
                                           .map(io.casehub.neocortex.cognitive.index.CognitiveDefaults::personalityDominance)
                                           .orElse(0.5);
                }
        );
        chainSectionCustomizer(tierFilter);
    }


    public void configureTierFilter(double personalityDominance) {
        if (tierFilter != null) {
            tierFilter.setPersonalityDominance(personalityDominance);
            return;
        }
        tierFilter = new io.casehub.neocortex.cognition.prompt.TierFilterCustomizer(
                () -> {
                    String aid = lastAgentId;
                    String tid = lastTenantId;
                    if (aid == null || tid == null) {return 0.0;}
                    return mood.currentMood(aid, tid)
                               .map(io.casehub.neocortex.memory.mood.MoodState::arousal)
                               .orElse(0.0);
                },
                personalityDominance
        );
        chainSectionCustomizer(tierFilter);
    }


    public void configureEntityRendering(
            java.util.function.@Nullable BiFunction<String, String,
                                                           io.casehub.neocortex.entity.token.EntityIdentity> identityLookup) {
        chainSectionCustomizer(sections -> sections.stream()
                                                   .<io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer>map(
                                                           s -> new io.casehub.neocortex.cognition.prompt.EntityRenderingSection(
                                                                   s, identityLookup))
                                                   .toList());
    }

    public void setMoodPersister(@Nullable Consumer<MoodState> moodPersister) {
        this.moodPersister = moodPersister;
    }

    private boolean isEnabled(boolean configFlag, Set<SignalCategory> relevance) {
        return configFlag || (lastBriefing != null && config.attentionEnabled()
                              && AttentionRelevance.overrides(lastBriefing, relevance));
    }

    public void addParticipant(CognitionPhase phase, CognitionTickParticipant participant) {
        if (phase == CognitionPhase.SOURCE_PER_SUBJECT) {
            throw new IllegalArgumentException("SOURCE_PER_SUBJECT does not accept custom participants");
        }
        customParticipants.computeIfAbsent(phase, k -> new java.util.ArrayList<>()).add(participant);
    }

    private void runCustomParticipants(CognitionPhase phase, CognitionTickContext context) {
        var participants = customParticipants.get(phase);
        if (participants == null) return;
        for (var participant : participants) {
            safeRun(() -> participant.tick(context));
        }
    }

    private void bridgeAppraisalToMood(io.casehub.neocortex.cognition.appraisal.AppraisalResult result,
                                       String agentId, String tenantId) {
        if (result.emotions().isEmpty()) return;
        double totalIntensity = result.emotions().stream()
                .mapToDouble(io.casehub.neocortex.cognitive.CognitiveEmotion::intensity).sum();
        if (totalIntensity <= 0) return;
        double p = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().pleasure()).sum() / totalIntensity;
        double a = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().arousal()).sum() / totalIntensity;
        double d = result.emotions().stream()
                .mapToDouble(e -> e.intensity() * e.pad().dominance()).sum() / totalIntensity;
        mood.record(new MoodSignal.DirectShift(
                Math.max(-2.0, Math.min(2.0, p)),
                Math.max(-2.0, Math.min(2.0, a)),
                Math.max(-2.0, Math.min(2.0, d)),
                "appraisal-emotions"), agentId, tenantId);
    }

    private void tickMood(String agentId, String tenantId) {
        if (mood.currentMood(agentId, tenantId).isEmpty()) {
            mood.record(new MoodSignal.InteractionAppraisal(0, 0, 0, "init"),
                        agentId, tenantId);
        }
        var result = mood.tick(agentId, tenantId);
        if (result instanceof MoodTick.Updated updated && moodPersister != null) {
            moodPersister.accept(updated.moodState());
        }
    }

    private void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Orchestrator operation failed", e);
        }
    }
}
