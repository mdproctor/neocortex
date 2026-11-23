package io.casehub.neocortex.cognition.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.core.CognitionPhase;
import io.casehub.neocortex.cognition.core.CognitionTestAccess;
import io.casehub.neocortex.cognition.core.ConsolidationMediator;
import io.casehub.neocortex.cognition.core.SubjectResolver;
import io.casehub.neocortex.cognition.drive.CuriosityChannel;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveComposer;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.cognition.search.SearchBudget;
import io.casehub.neocortex.cognition.search.SearchBudgetTickParticipant;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.experience.ExperienceEvent;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.inmem.InMemoryMemoryStore;
import io.casehub.neocortex.memory.mood.MoodState;
import io.casehub.neocortex.mindmap.ConsolidationArtifact;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.RecurringThoughtAssessment;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapDetector;
import io.casehub.neocortex.mindmap.intelligence.InformationGapScore;
import io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationCompleted;
import io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationPhase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.caps.ConvergenceType;
import io.casehub.neocortex.caps.SettlingResult;
import io.casehub.neocortex.caps.engine.RuleBasedSituationClassifier;
import io.casehub.neocortex.caps.testing.InMemoryCapsEngine;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.appraisal.AppraisalThresholds;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.appraisal.NoOpSalienceStrategy;
import io.casehub.neocortex.cognition.core.CognitivePipelineBuilder;
import io.casehub.neocortex.cognition.drive.AffiliationDrive;
import io.casehub.neocortex.cognition.drive.AutonomyDrive;
import io.casehub.neocortex.cognition.drive.CompetenceDrive;
import io.casehub.neocortex.cognition.goal.GoalEscalationConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalOrchestrator;
import io.casehub.neocortex.cognition.innerlife.InnerLifeConfig;
import io.casehub.neocortex.cognition.innerlife.InnerLifeOrchestrator;
import io.casehub.neocortex.cognition.memory.MemoryHygieneOrchestrator;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelMemory;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator;
import io.casehub.neocortex.cognition.narrative.NarrativeConfig;
import io.casehub.neocortex.cognition.narrative.NarrativeMemory;
import io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator;
import io.casehub.neocortex.cognition.strategy.StrategyLearningConfig;
import io.casehub.neocortex.cognition.strategy.StrategyLearningOrchestrator;
import io.casehub.neocortex.cognition.strategy.StrategyMemory;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import io.casehub.neocortex.cognition.usermodel.UserModelConfig;
import io.casehub.neocortex.cognition.usermodel.UserModelOrchestrator;
import io.casehub.neocortex.cognition.usermodel.UserProfile;
import io.casehub.neocortex.cognition.usermodel.UserProfileMemory;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.memory.KnowledgeGapSummary;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrRecordStore;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.neocortex.mindmap.intelligence.consolidation.BehavioralSynthesisPhase;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.function.ToDoubleFunction;
import org.mockito.Mockito;

public class CognitionTestHarness {

    private static final SubjectResolver NO_OP_RESOLVER = (a, t) -> Set.of();

    private final CognitionCore core;
    private final MindMapStore mindMapStore;
    private final CaseMemoryStore memoryStore;
    private final CuriosityDrive curiosityDrive;
    private final MoodOrchestrator moodOrchestrator;
    private final SearchBudgetTickParticipant budgetParticipant;
    private final EffectiveCognitiveDefaults registry;
    private final ConsolidationMediator mediator;
    private final List<ConsolidationPhase> consolidationPhases;
    private final Map<String, AgentDescriptor> descriptors;
    private final Map<String, String> lastObservations = new ConcurrentHashMap<>();
    private final BehavioralSynthesisPhase behavioralSynthesisPhase;
    private final CapsEngine                                             capsEngine;
    private final Map<String, CognitiveDefaults>              agentDefaults;


    CognitionTestHarness(CognitionCore core,
                         MindMapStore mindMapStore,
                         CaseMemoryStore memoryStore,
                         CuriosityDrive curiosityDrive,
                         MoodOrchestrator moodOrchestrator,
                         SearchBudgetTickParticipant budgetParticipant,
                         EffectiveCognitiveDefaults registry,
                         ConsolidationMediator mediator,
                         List<ConsolidationPhase> consolidationPhases,
                         Map<String, AgentDescriptor> descriptors,
                         BehavioralSynthesisPhase behavioralSynthesisPhase,
                         CapsEngine capsEngine,
                         Map<String, CognitiveDefaults> agentDefaults) {
        this.core                     = core;
        this.mindMapStore             = mindMapStore;
        this.memoryStore              = memoryStore;
        this.curiosityDrive           = curiosityDrive;
        this.moodOrchestrator         = moodOrchestrator;
        this.budgetParticipant        = budgetParticipant;
        this.registry                 = registry;
        this.mediator                 = mediator;
        this.consolidationPhases      = List.copyOf(consolidationPhases);
        this.descriptors              = Map.copyOf(descriptors);
        this.behavioralSynthesisPhase = behavioralSynthesisPhase;
        this.capsEngine               = capsEngine;
        this.agentDefaults            = agentDefaults != null ? Map.copyOf(agentDefaults) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Store access ---

    public MindMapStore mindMapStore() { return mindMapStore; }
    public CaseMemoryStore memoryStore() { return memoryStore; }

    public CognitionCore core()          {return core;}

    public Optional<PipelineTrace> currentTrace(String agentId, String tenantId) {
        return core.currentTrace(agentId, tenantId);
    }


    // --- Observation + experience ---

    public void observe(String agentId, String tenantId, String observation) {
        lastObservations.put(agentId, observation);
    }

    public void addExperiences(String agentId, String tenantId, List<ExperienceEvent> events) {
        for (var event : events) {
            memoryStore.store(ExperienceEvents.toMemoryInput(event));
        }
    }

    public void addExperienceMemories(String agentId, String tenantId, String topicName, int count) {
        var domain = ExperienceEvents.DOMAIN;
        var subject = Subject.of("agent", agentId);
        for (int i = 0; i < count; i++) {
            memoryStore.store(MemoryInput.of(subject, domain, tenantId,
                "Observation about " + topicName + " iteration " + i)
                .withAttributes(Map.of("event-type", "observation", "subject", topicName)));
        }
    }

    public void seedUserProfile(UserProfile profile) {
        var userModel = core.userModel();
        if (userModel != null) {
            userModel.seedProfile(profile);
        }
    }


    // --- Pipeline execution ---

    public void tick(String agentId, String tenantId) {
        var observation = lastObservations.remove(agentId);
        var descriptor = descriptors.get(agentId);
        core.tick(agentId, tenantId, descriptor, NO_OP_RESOLVER, observation, null);
    }

    public void consolidate(String agentId, String tenantId) {
        if (capsEngine != null) {
            for (var entry : agentDefaults.entrySet()) {
                if (capsEngine.loadState(tenantId, entry.getKey()) == null) {
                    var descriptor = entry.getValue().descriptor();
                    var axes       = descriptor != null ? descriptor.disposition() : null;
                    if (axes == null) {
                        axes = new DispositionAxes(
                                "cooperative", "moderate", "calculated", "moderate", "cooperative");
                    }
                    var copingDefaults = entry.getValue().copingDefaults();
                    if (copingDefaults != null) {
                        capsEngine.initializeAgent(tenantId, entry.getKey(), axes, copingDefaults);
                    } else {
                        capsEngine.initializeAgent(tenantId, entry.getKey(), axes);
                    }
                }
            }
        }
        for (var phase : consolidationPhases) {
            phase.beginTick();
            phase.run(tenantId, List.of());
        }
        for (var phase : consolidationPhases) {
            var artifacts = phase.artifacts();
            if (!artifacts.isEmpty()) {
                CognitionTestAccess.pushConsolidationArtifacts(mediator,
                                                               new ConsolidationCompleted(tenantId, List.of(), artifacts));
            }
        }
    }

    public void tickAndConsolidate(String agentId, String tenantId) {
        consolidate(agentId, tenantId);
        tick(agentId, tenantId);
    }

    // --- State injection ---

    public void setContinuumPosition(String agentId, String tenantId,
                                     Map<String, ContinuumPosition> positions) {
        var artifacts = positions.entrySet().stream()
            .map(e -> (ConsolidationArtifact) new ConsolidationArtifact.RecurringThoughtDetected(
                tenantId, agentId,
                new RecurringThoughtAssessment(e.getKey(), 0, 0, 0, 0, 0, 0, 0, 0, e.getValue()),
                Instant.now()))
            .toList();
        CognitionTestAccess.pushConsolidationArtifacts(mediator,
            new ConsolidationCompleted(tenantId, List.of(), artifacts));
    }

    public void recordMood(String agentId, String tenantId,
                           double pleasure, double arousal, double dominance) {
        moodOrchestrator.record(
            new MoodSignal.DirectShift(pleasure, arousal, dominance, "test-harness"),
            agentId, tenantId);
        moodOrchestrator.tick(agentId, tenantId);
    }

    // --- State access ---

    public Optional<SearchBudget> searchBudget(String agentId, String tenantId) {
        return budgetParticipant.currentBudget(agentId, tenantId);
    }

    public Map<String, ContinuumPosition> continuumMap(String agentId) {
        return CognitionTestAccess.lastContinuumMap(core);
    }

    public ContinuumPosition continuumPosition(String agentId, String topic) {
        return continuumMap(agentId).getOrDefault(topic, ContinuumPosition.HEALTHY);
    }

    public List<InformationGapScore> lastGaps(String agentId) {
        return curiosityDrive.lastGaps();
    }

    public CuriosityChannel lastChannel(String agentId) {
        return curiosityDrive.lastChannel();
    }

    public Optional<MoodState> currentMood(String agentId, String tenantId) {
        return moodOrchestrator.currentMood(agentId, tenantId);
    }

    public Optional<SettlingResult> capsSettlingResult(String agentId, String tenantId) {
        if (behavioralSynthesisPhase == null) {
            return Optional.empty();
        }
        return behavioralSynthesisPhase.lastSettlingResult(tenantId, agentId);
    }

    public record CapsTrajectory(int iterations, ConvergenceType convergence,
                                 double saturationRatio) {}

    public Optional<CapsTrajectory> capsTrajectory(String agentId, String tenantId) {
        return capsSettlingResult(agentId, tenantId)
                       .map(r -> new CapsTrajectory(r.iterations(), r.convergence(), r.saturationRatio()));
    }


    // --- Prompt rendering ---

    public String renderPrompt(String agentId, String tenantId) {
        var context = new CognitionRenderContext(agentId, tenantId, null, List.of());
        return core.promptSections().stream()
            .map(s -> s.render(context))
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n\n"));
    }

    // --- Builder ---

    public static class Builder {

        private MindMapStore mindMapStore;
        private CaseMemoryStore memoryStore;
        private final Map<String, CognitiveDefaults> agentDefaults = new LinkedHashMap<>();
        private final List<ConsolidationPhase> extraPhases = new ArrayList<>();
        private Supplier<Set<String>> goalNodeIds = Set::of;
        private CognitionConfig cognitionConfig;
        private boolean consolidationEnabled;
        private boolean behavioralSynthesisEnabled;
        private boolean appraisalEnabled;

        private SessionPipeline sessionPipeline;

        private final Map<String, HabituationState> habituationStates = new HashMap<>();
        private final Map<String, AppraisalResult>  appraisalResults  = new HashMap<>();


        public Builder withMindMapStore(MindMapStore store) {
            this.mindMapStore = store;
            return this;
        }

        public Builder withMemoryStore(CaseMemoryStore store) {
            this.memoryStore = store;
            return this;
        }

        public Builder agentFromAxes(String agentId, String socialOrient,
                                     String ruleFollowing, String riskAppetite,
                                     String autonomy, String conflictMode) {
            var axes = new DispositionAxes(socialOrient, ruleFollowing,
                riskAppetite, autonomy, conflictMode);
            var view = DescriptorView.of(agentId, axes, List.of(), List.of());
            agentDefaults.put(agentId, CognitiveDerivationEngine.derive(view));
            return this;
        }

        public Builder agentFromDefaults(String agentId, CognitiveDefaults defaults) {
            agentDefaults.put(agentId, defaults);
            return this;
        }

        public Builder withGoalNodeIds(Supplier<Set<String>> supplier) {
            this.goalNodeIds = supplier;
            return this;
        }

        public Builder withConsolidationEnabled(boolean enabled) {
            this.consolidationEnabled = enabled;
            return this;
        }

        public Builder withConsolidationPhase(ConsolidationPhase phase) {
            this.extraPhases.add(phase);
            return this;
        }

        public Builder withCognitionConfig(CognitionConfig config) {
            this.cognitionConfig = config;
            return this;
        }

        public Builder withSessionPipeline(SessionPipeline pipeline) {
            this.sessionPipeline = pipeline;
            return this;
        }


        public Builder withHabituationState(String agentId,
                HabituationState state) {
            this.habituationStates.put(agentId, state);
            return this;
        }

        public Builder withAppraisalResult(String agentId,
                                           AppraisalResult result) {
            this.appraisalResults.put(agentId, result);
            return this;
        }

        public Builder withBehavioralSynthesis(boolean enabled) {
            this.behavioralSynthesisEnabled = enabled;
            return this;
        }

        public Builder withAppraisalEnabled(boolean enabled) {
            this.appraisalEnabled = enabled;
            return this;
        }


        public CognitionTestHarness build() {
            var store    = mindMapStore != null ? mindMapStore : new InMemoryMindMapStore();
            var memStore = memoryStore != null ? memoryStore : createDefaultMemoryStore();

            var allDefaults = agentDefaults.values().toArray(new CognitiveDefaults[0]);
            var registry    = EffectiveCognitiveDefaults.forTesting(allDefaults);

            var cbrStore = new InMemoryCbrRecordStore();

            var moodOrchestrator = new MoodOrchestrator(MoodConfig.defaults());

            ReflectionOrchestrator reflectionOrch =
                    (agentId, tenantId, since, maxEntries) -> List.of();

            var narrativeOrch = new NarrativeOrchestrator(
                    new NarrativeMemory(
                            cbrStore, NarrativeConfig.defaults()));

            var userModel = new UserModelOrchestrator(
                    new UserProfileMemory(
                            cbrStore, UserModelConfig.defaults()),
                    UserModelConfig.defaults(), sessionPipeline);

            var mentalModel = new MentalModelOrchestrator(
                    new MentalModelMemory(
                            cbrStore, MentalModelConfig.defaults()),
                    MentalModelConfig.defaults(),
                    Clock.systemUTC(), sessionPipeline);

            var strategy = new StrategyLearningOrchestrator(
                    new StrategyMemory(
                            cbrStore, StrategyLearningConfig.defaults()),
                    reflectionOrch,
                    StrategyLearningConfig.defaults(),
                    Clock.systemUTC(), sessionPipeline);

            MemoryHygieneOrchestrator hygiene =
                    new MemoryHygieneOrchestrator() {
                        @Override
                        public void tick(String agentId, String tenantId) {}

                        @Override
                        public KnowledgeGapSummary knowledgeGaps(
                                String agentId, String tenantId) {
                            return KnowledgeGapSummary.empty();
                        }
                    };

            var gapDetector     = new InformationGapDetector(store);
            var channelComputer = new CuriosityChannelComputer();
            ToDoubleFunction<String> biasLookup = agentId -> {
                var defaults = agentDefaults.get(agentId);
                if (defaults != null && defaults.personalityProfile() != null) {
                    var facets = defaults.personalityProfile().facets();
                    if (facets != null) {
                        return facets.stream()
                                     .filter(f -> "Anxiousness".equals(f.facet()))
                                     .findFirst()
                                     .map(PersonalityFacet::score)
                                     .orElse(0.5);
                    }
                }
                return 0.5;
            };
            var curiosityDrive = new CuriosityDrive(gapDetector, channelComputer, biasLookup, goalNodeIds);

            var driveOrchestrator = new DriveOrchestrator(
                    curiosityDrive,
                    new CompetenceDrive(strategy),
                    new AffiliationDrive(userModel, 0.3, Duration.ofHours(1)),
                    new AutonomyDrive(mentalModel, 0.5),
                    moodOrchestrator, new DriveComposer(), DriveConfig.defaults());

            var innerLife = new InnerLifeOrchestrator(
                    reflectionOrch, List.of(),
                    InnerLifeConfig.defaults(),
                    driveOrchestrator, sessionPipeline);

            var goals = new GoalProposalOrchestrator(
                    driveOrchestrator, List.of(), null, Optional.empty(),
                    null, null, null,
                    GoalProposalConfig.defaults(),
                    GoalEscalationConfig.defaults(),
                    Clock.systemUTC());

            var mediator = new ConsolidationMediator();
            var config   = cognitionConfig != null ? cognitionConfig : CognitionConfig.all();
            if (appraisalEnabled) {
                config = config.with("appraisal", true);
            }

            var pipelineBuilder = CognitivePipelineBuilder
                               .create(moodOrchestrator, driveOrchestrator)
                               .userModel(userModel).mentalModel(mentalModel).strategy(strategy)
                               .narrative(narrativeOrch).goals(goals).memoryHygiene(hygiene)
                               .innerLife(innerLife).config(config).mindMapStore(store)
                               .consolidationMediator(mediator).sessionPipeline(sessionPipeline)
                               .effectiveDefaults(registry);

            if (appraisalEnabled) {
                if (!appraisalResults.isEmpty() || !habituationStates.isEmpty()) {
                    throw new IllegalStateException(
                            "withAppraisalEnabled and withAppraisalResult/withHabituationState are mutually exclusive");
                }
                pipelineBuilder.appraisal(new TestAppraisalStrategy(),
                        new NoOpSalienceStrategy());
            }

            InMemoryCapsEngine capsEngineInstance = null;
            RuleBasedSituationClassifier classifierInstance = null;
            if (behavioralSynthesisEnabled) {
                capsEngineInstance = new InMemoryCapsEngine();
                classifierInstance = new RuleBasedSituationClassifier(capsEngineInstance.topology());
                pipelineBuilder.situationClassifier(classifierInstance);
            }

            var core = pipelineBuilder.build();

            AppraisalTickParticipant appraisalStub = null;
            if (!habituationStates.isEmpty() || !appraisalResults.isEmpty()) {
                appraisalStub = Mockito.mock(AppraisalTickParticipant.class);
                for (var entry : habituationStates.entrySet()) {
                    Mockito.when(appraisalStub.currentHabituation(
                               Mockito.eq(entry.getKey()), Mockito.anyString()))
                                       .thenReturn(entry.getValue());
                }
                for (var entry : appraisalResults.entrySet()) {
                    Mockito.when(appraisalStub.currentResult(
                               Mockito.eq(entry.getKey()), Mockito.anyString()))
                                       .thenReturn(Optional.of(entry.getValue()));
                }
                Mockito.when(appraisalStub.thresholds())
                                   .thenReturn(AppraisalThresholds.defaults());
                Mockito.when(appraisalStub.currentConcernSalience(
                           Mockito.anyString(), Mockito.anyString()))
                                   .thenReturn(Map.of());
                Mockito.when(appraisalStub.currentChannelWeights(
                           Mockito.anyString(), Mockito.anyString()))
                                   .thenReturn(Optional.empty());
                CognitionTestAccess.setAppraisalParticipant(core, appraisalStub);
            }

            var budgetParticipant = new SearchBudgetTickParticipant(
                    driveOrchestrator, appraisalStub, null, moodOrchestrator,
                    registry, config, curiosityDrive, null);
            core.addParticipant(CognitionPhase.DERIVED, budgetParticipant);

            core.configureSearchBudget(driveOrchestrator, moodOrchestrator,
                                       registry, store, curiosityDrive, null);
            core.configureCuriosity(curiosityDrive, channelComputer, store, 0.5, 0.5);

            var descriptors = new HashMap<String, AgentDescriptor>();
            for (var agentId : agentDefaults.keySet()) {
                descriptors.put(agentId, AgentDescriptor.builder().agentId(agentId).name(agentId).slot("test").tenancyId("test-tenant").build());
            }

            var phases = new ArrayList<>(extraPhases);
            if (consolidationEnabled) {
                phases.add(CognitionTestAccess.createRecurringThoughtPhase(memStore, store, registry));
            }

            BehavioralSynthesisPhase bsp = null;
            CapsEngine capsEngineRef = null;
            if (behavioralSynthesisEnabled) {
                bsp = new BehavioralSynthesisPhase(
                        store, capsEngineInstance, classifierInstance, 20, cbrStore, registry);
                phases.add(bsp);
                capsEngineRef = capsEngineInstance;
                core.configureBehavioralSynthesis(bsp);
            }

            return new CognitionTestHarness(core, store, memStore, curiosityDrive,
                                            moodOrchestrator, budgetParticipant, registry, mediator,
                                            phases, descriptors, bsp, capsEngineRef,
                                            behavioralSynthesisEnabled ? agentDefaults : null);
        }

        private static CaseMemoryStore createDefaultMemoryStore() {
            return new InMemoryMemoryStore(new CurrentPrincipal() {
                @Override public String actorId() { return "test-actor"; }
                @Override public Set<String> groups() { return Set.of(); }
                @Override public String tenancyId() { return "test-tenant"; }
                @Override public boolean isCrossTenantAdmin() { return false; }
            });
        }
    }
}
