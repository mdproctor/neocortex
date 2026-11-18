package io.casehub.neocortex.cognition;

import io.casehub.neocortex.cognition.belief.BeliefRevisionConfig;
import io.casehub.neocortex.cognition.core.CognitiveImpact;
import io.casehub.neocortex.cognition.core.CognitiveResourcePool;
import io.casehub.neocortex.cognition.core.DefaultCognitiveResourcePool;
import io.casehub.neocortex.cognition.core.InteractionMapper;
import io.casehub.neocortex.cognition.core.SubjectResolver;
import io.casehub.neocortex.cognition.drive.DriveConfig;
import io.casehub.neocortex.cognition.emergence.NormDetectionConfig;
import io.casehub.neocortex.cognition.emergence.NormFilter;
import io.casehub.neocortex.cognition.goal.CognitiveGoalConfig;
import io.casehub.neocortex.cognition.goal.GoalEscalationConfig;
import io.casehub.neocortex.cognition.goal.GoalProposalConfig;
import io.casehub.neocortex.cognition.innerlife.InnerLifeConfig;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelConfig;
import io.casehub.neocortex.cognition.mentalmodel.MentalModelLookup;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodCongruenceConfig;
import io.casehub.neocortex.cognition.narrative.NarrativeConfig;
import io.casehub.neocortex.cognition.personality.PersonalityEvolutionConfig;
import io.casehub.neocortex.cognition.strategy.StrategyLearningConfig;
import io.casehub.neocortex.cognition.usermodel.UserModelConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.platform.agent.session.SessionPipeline;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Set;

@ApplicationScoped
public class CognitionDefaultBeans {

    @Inject
    Instance<SessionPipeline> sessionPipelineInstance;

    @Produces
    @DefaultBean
    @Singleton
    java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Produces
    @DefaultBean
    @Singleton
    CognitiveResourcePool cognitiveResourcePool(EffectiveCognitiveDefaults effectiveDefaults) {
        return new DefaultCognitiveResourcePool(effectiveDefaults);
    }


    @Produces @DefaultBean @Singleton
    BeliefRevisionConfig beliefRevisionConfig() {
        return new BeliefRevisionConfig(false, 0.15, 0.3, 0.6);
    }

    @Produces @DefaultBean @Singleton
    DriveConfig driveConfig() {
        return DriveConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    MoodConfig moodConfig() {
        return MoodConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    PersonalityEvolutionConfig personalityEvolutionConfig() {
        return PersonalityEvolutionConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    InnerLifeConfig innerLifeConfig() {
        return InnerLifeConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    MentalModelConfig mentalModelConfig() {
        return MentalModelConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    UserModelConfig userModelConfig() {
        return UserModelConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    StrategyLearningConfig strategyLearningConfig() {
        return StrategyLearningConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    NarrativeConfig narrativeConfig() {
        return NarrativeConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    GoalProposalConfig goalProposalConfig() {
        return GoalProposalConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    GoalEscalationConfig goalEscalationConfig() {
        return GoalEscalationConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    NormDetectionConfig normDetectionConfig() {
        return NormDetectionConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    MoodCongruenceConfig moodCongruenceConfig() {
        return MoodCongruenceConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    CognitiveGoalConfig cognitiveGoalConfig() {
        return CognitiveGoalConfig.defaults();
    }

    @Produces @DefaultBean @Singleton
    SubjectResolver subjectResolver() {
        return (agentId, tenantId) -> Set.of();
    }

    @Produces @DefaultBean @Singleton
    InteractionMapper interactionMapper() {
        return (agentId, targetId, interactionType) ->
                CognitiveImpact.fromText(interactionType);
    }

    @Produces @DefaultBean @Singleton
    NormFilter normFilter() {
        return (norms, agentId, tenantId) -> norms;
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognition.appraisal.SalienceStrategy salienceStrategy(
            Instance<io.casehub.neocortex.mindmap.MindMapStore> mindMapStore) {
        io.casehub.neocortex.cognition.appraisal.SalienceStrategy base;
        if (mindMapStore.isResolvable()) {
            base = new io.casehub.neocortex.cognition.salience.ConcernBasedSalienceStrategy();
        } else {
            base = new io.casehub.neocortex.cognition.appraisal.NoOpSalienceStrategy();
        }
        return new io.casehub.neocortex.cognition.salience.EntityAwareSalienceStrategy(base);
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognition.appraisal.AppraisalStrategy appraisalStrategy() {
        if (sessionPipelineInstance == null || !sessionPipelineInstance.isResolvable()) {
            throw new IllegalStateException(
                    "SessionPipeline is required for cognitive appraisal — " +
                    "you cannot run a cognitive neocortex without an LLM");
        }
        return new io.casehub.neocortex.cognition.appraisal.LlmAppraisalStrategy(sessionPipelineInstance.get());
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognition.goal.DeductiveGoalFormationStrategy deductiveGoalFormationStrategy() {
        if (sessionPipelineInstance != null && sessionPipelineInstance.isResolvable()) {
            return new io.casehub.neocortex.cognition.goal.LlmDeductiveGoalFormationStrategy(sessionPipelineInstance.get());
        }
        return new io.casehub.neocortex.cognition.goal.NoOpDeductiveGoalFormationStrategy();
    }

    @jakarta.enterprise.inject.Produces
    @io.quarkus.arc.DefaultBean
    @jakarta.inject.Singleton
    io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant subThoughtTickParticipant(
            io.casehub.neocortex.cognition.subthought.RuleBasedSubThoughtExtractor extractor,
            jakarta.enterprise.inject.Instance<io.casehub.neocortex.cognition.mentalmodel.MentalModelOrchestrator> mentalModel,
            jakarta.enterprise.inject.Instance<io.casehub.neocortex.cognition.core.SubjectResolver> resolver) {
        return new io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant(
                extractor,
                mentalModel.isResolvable() ? mentalModel.get() : null,
                resolver.isResolvable() ? resolver.get() : null
        );
    }

    @jakarta.enterprise.inject.Produces
    @io.quarkus.arc.DefaultBean
    @jakarta.inject.Singleton
    io.casehub.neocortex.cognition.goal.CrossAxisGoalEnricher crossAxisGoalEnricher() {
        return (agentId, heuristicProposal, narrative, sourceTheme) -> heuristicProposal;
    }

    @jakarta.enterprise.inject.Produces
    @io.quarkus.arc.DefaultBean
    @jakarta.inject.Singleton
    io.casehub.neocortex.cognition.innerlife.CivilityConstraint civilityConstraint() {
        return context -> new io.casehub.neocortex.cognition.innerlife.CivilityCheck.Permitted();
    }

    @jakarta.enterprise.inject.Produces
    @io.quarkus.arc.DefaultBean
    @jakarta.inject.Singleton
    io.casehub.neocortex.cognition.goal.GoalEscalationPolicy goalEscalationPolicy() {
        return (proposal, context) -> new io.casehub.neocortex.cognition.goal.EscalationResult(
                proposal.suggestedPriority(), "", "no-escalation");
    }

    @jakarta.enterprise.inject.Produces
    @io.quarkus.arc.DefaultBean
    @jakarta.inject.Singleton
    io.casehub.neocortex.cognition.emergence.SocialNormDetector socialNormDetector(
            Instance<io.casehub.neocortex.memory.cbr.CbrRecordStore> cbrStore,
            io.casehub.neocortex.cognition.emergence.NormDetectionConfig config) {
        if (!cbrStore.isResolvable()) return null;
        return new io.casehub.neocortex.cognition.emergence.SocialNormDetector(
                cbrStore.get(), config);
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognitive.index.RetrievalModulationConfig retrievalModulationConfig(
            RetrievalModulationProperties props) {
        return new io.casehub.neocortex.cognitive.index.RetrievalModulationConfig(
                props.recencyEnabled(), props.recencyHalfLifeDays(),
                props.confidenceEnabled(), props.moodCongruenceEnabled(),
                props.moodCongruenceWeight(), props.goalRelevanceEnabled(),
                props.domainEnabled(), props.entityOverlapEnabled());
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever cognitiveMemoryRetriever(
            Instance<io.casehub.neocortex.memory.CaseMemoryStore> memoryStore,
            io.casehub.neocortex.cognitive.index.RetrievalModulationConfig config,
            Instance<io.casehub.neocortex.mindmap.MindMapStore> mindMapStore) {
        if (!memoryStore.isResolvable()) {return null;}
        var staticFactors = new java.util.ArrayList<io.casehub.neocortex.cognitive.ModulationFactor<io.casehub.neocortex.memory.Memory>>();
        var now = java.time.Instant.now();
        if (config.recencyEnabled()) {
            staticFactors.add(io.casehub.neocortex.cognitive.index.ModulationFactors.recencyDecay(
                    java.time.Duration.ofDays(config.recencyHalfLifeDays()), now));
        }
        if (config.confidenceEnabled()) {
            staticFactors.add(io.casehub.neocortex.cognitive.index.ModulationFactors.confidenceWeight());
        }
        var contextualFactories = new java.util.ArrayList<io.casehub.neocortex.cognitive.index.ContextualModulationFactory<io.casehub.neocortex.memory.Memory>>();
        if (config.moodCongruenceEnabled()) {
            double weight = config.moodCongruenceWeight();
            contextualFactories.add(ctx -> {
                if (ctx.currentMood() == null) return null;
                return io.casehub.neocortex.cognitive.index.ModulationFactors.moodCongruence(ctx.currentMood(), weight);
            });
        }
        if (config.goalRelevanceEnabled() && mindMapStore.isResolvable()) {
            var ms = mindMapStore.get();
            contextualFactories.add(ctx -> new io.casehub.neocortex.cognitive.index.GoalRelevanceModulationFactor(ms, ctx.tenantId()));
        }
        if (config.domainEnabled()) {
            contextualFactories.add(ctx -> {
                if (ctx.personalityWeights() == null) return null;
                return io.casehub.neocortex.cognitive.index.ModulationFactors.domainWeight(ctx.personalityWeights());
            });
        }
        if (config.entityOverlapEnabled()) {
            contextualFactories.add(ctx -> {
                if (ctx.contextEntityNodeIds().isEmpty()) return null;
                return new io.casehub.neocortex.cognitive.index.EntityOverlapModulationFactor(ctx.contextEntityNodeIds());
            });
        }
        return new io.casehub.neocortex.cognitive.index.CognitiveMemoryRetriever(
                memoryStore.get(), staticFactors, contextualFactories);
    }

    @Produces
    @DefaultBean
    @Singleton
    public MentalModelLookup mentalModelLookup() {
        return (agentId, subjectId, tenantId) -> java.util.Optional.empty();
    }

    @Produces @DefaultBean @Singleton
    SearchBudgetConfig searchBudgetConfig() {
        return SearchBudgetConfig.defaults();
    }

    @Produces
    @DefaultBean
    @Singleton
    io.casehub.neocortex.cognitive.RecurringThoughtConfig recurringThoughtConfig() {
        return io.casehub.neocortex.cognitive.RecurringThoughtConfig.defaults();
    }

}
