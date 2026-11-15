package io.casehub.neocortex.cognition.core;

/**
 * Runtime feature toggles for the cognitive pipeline.
 *
 * <p>Each toggle corresponds to a section in the agency {@code CognitionDefinition} YAML schema.
 * The {@link #with(String, boolean)} keys are the canonical section names used by both systems.
 *
 * <h3>Toggle-to-section mapping</h3>
 * <table>
 *   <tr><th>Toggle</th><th>Definition section</th><th>Controls</th></tr>
 *   <tr><td>{@code moodEnabled}</td><td>{@code mood}</td><td>MoodOrchestrator, MoodPromptSection</td></tr>
 *   <tr><td>{@code drivesEnabled}</td><td>{@code drives}</td><td>DriveOrchestrator, DrivePromptSection</td></tr>
 *   <tr><td>{@code mentalModelEnabled}</td><td>{@code mentalModel}</td><td>MentalModelOrchestrator, MentalModelPromptSection</td></tr>
 *   <tr><td>{@code userModelEnabled}</td><td>{@code userModel}</td><td>UserModelOrchestrator, UserModelPromptSection</td></tr>
 *   <tr><td>{@code strategyEnabled}</td><td>{@code strategy}</td><td>StrategyLearningOrchestrator, StrategyPromptSection</td></tr>
 *   <tr><td>{@code narrativeEnabled}</td><td>{@code narrative}</td><td>NarrativeOrchestrator, NarrativePromptSection</td></tr>
 *   <tr><td>{@code goalsEnabled}</td><td>{@code goals}</td><td>GoalProposalOrchestrator, EmergentGoalPromptSection</td></tr>
 *   <tr><td>{@code memoryHygieneEnabled}</td><td>{@code memoryHygiene}</td><td>MemoryHygieneOrchestrator</td></tr>
 *   <tr><td>{@code innerLifeEnabled}</td><td>{@code innerLife}</td><td>InnerLifeOrchestrator</td></tr>
 *   <tr><td>{@code directivePrompts}</td><td>{@code directivePrompts}</td><td>DirectiveSection wrapping on all sections</td></tr>
 *   <tr><td>{@code characterDrivesEnabled}</td><td>{@code characterDrives}</td><td>CharacterDrivePromptSection (suppressed when appraisal active)</td></tr>
 *   <tr><td>{@code needsPyramidEnabled}</td><td>{@code needsPyramid}</td><td>NeedsPyramidPromptSection</td></tr>
 *   <tr><td>{@code attentionEnabled}</td><td>{@code attention}</td><td>AttentionPromptSection</td></tr>
 *   <tr><td>{@code temporalFocusEnabled}</td><td>{@code temporalFocus}</td><td>TemporalFocusOrchestrator, TemporalFocusPromptSection</td></tr>
 *   <tr><td>{@code reflectionEnabled}</td><td>{@code reflection}</td><td>ReflectionRetrievalOrchestrator, ReflectionPromptSection</td></tr>
 *   <tr><td>{@code consolidationEnabled}</td><td>{@code consolidation}</td><td>ConsolidationMediator, ConsolidationPromptSection</td></tr>
 *   <tr><td>{@code entityKnowledgeEnabled}</td><td>{@code entityKnowledge}</td><td>EntityKnowledgePromptSection</td></tr>
 *   <tr><td>{@code perspectiveComparisonEnabled}</td><td>{@code perspectiveComparison}</td><td>SocialComparisonPromptSection</td></tr>
 *   <tr><td>{@code domainActivationEnabled}</td><td>{@code domainActivation}</td><td>DomainActivationPromptSection</td></tr>
 *   <tr><td>{@code appraisalEnabled}</td><td>{@code appraisal}</td><td>AppraisalTickParticipant, AppraisalPromptSection, ChannelEmphasisPromptSection</td></tr>
 *   <tr><td>{@code salienceEnabled}</td><td>{@code salience}</td><td>ConcernBasedSalienceStrategy</td></tr>
 *   <tr><td>{@code behavioralEnabled}</td><td>{@code behavioral}</td><td>BehavioralPromptSection (CAPS attractors + gut feeling)</td></tr>
 *   <tr><td>{@code subThoughtsEnabled}</td><td>{@code subThoughts}</td><td>SubThoughtPromptSection</td></tr>
 *   <tr><td>{@code otherModelEnabled}</td><td>{@code otherModel}</td><td>OtherModelPromptSection</td></tr>
 *   <tr><td>{@code regulationEnabled}</td><td>{@code regulation}</td><td>EmotionRegulationTickParticipant (Gross 1998 post-appraisal)</td></tr>
 *   <tr><td>{@code copingEnabled}</td><td>{@code coping}</td><td>CopingMicroLoop (Marsella &amp; Gratch 2009 EMA, pre-regulation)</td></tr>
 * </table>
 *
 * @see CognitionCore#promptSections()
 */
public record CognitionConfig(
        boolean moodEnabled,
        boolean drivesEnabled,
        boolean mentalModelEnabled,
        boolean userModelEnabled,
        boolean strategyEnabled,
        boolean narrativeEnabled,
        boolean goalsEnabled,
        boolean memoryHygieneEnabled,
        boolean innerLifeEnabled,
        boolean directivePrompts,
        boolean characterDrivesEnabled,
        boolean needsPyramidEnabled,
        boolean attentionEnabled,
        boolean temporalFocusEnabled,
        boolean reflectionEnabled,
        boolean consolidationEnabled,
        boolean entityKnowledgeEnabled,
        boolean perspectiveComparisonEnabled,
        boolean domainActivationEnabled,
        boolean appraisalEnabled,
        boolean salienceEnabled,
        boolean behavioralEnabled,
        boolean subThoughtsEnabled,
        boolean otherModelEnabled,
        boolean regulationEnabled,
        boolean copingEnabled
) {
    public static CognitionConfig all()               {return new CognitionConfig(true, true, true, true, true, true, true, true, true, false, true, true, true, true, true, true, false, false, false, false, false, false, true, true, false, false);}

    public static CognitionConfig withAllSubsystems() {return new CognitionConfig(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true);}

    public static CognitionConfig none()              {return new CognitionConfig(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);}

    public CognitionConfig withDirectives() {
        return new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled,
                                   userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled,
                                   memoryHygieneEnabled, innerLifeEnabled, true, characterDrivesEnabled,
                                   needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
    }

    public CognitionConfig with(String subsystem, boolean enabled) {
        return switch (subsystem) {
            case "mood" -> new CognitionConfig(enabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "drives" -> new CognitionConfig(moodEnabled, enabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "mentalModel" -> new CognitionConfig(moodEnabled, drivesEnabled, enabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "userModel" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, enabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "strategy" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, enabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "narrative" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, enabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "goals" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, enabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "memoryHygiene" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, enabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "innerLife" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, enabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "directivePrompts" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, enabled, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "characterDrives" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, enabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "needsPyramid" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, enabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "attention" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, enabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "temporalFocus" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, enabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "reflection" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, enabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "consolidation" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, enabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "entityKnowledge" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, enabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "perspectiveComparison" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, enabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "domainActivation" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, enabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "appraisal" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, enabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "salience" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, enabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "behavioral" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, enabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "subThoughts" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, enabled, otherModelEnabled, regulationEnabled, copingEnabled);
            case "otherModel" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, enabled, regulationEnabled, copingEnabled);
            case "regulation" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, enabled, copingEnabled);
            case "coping" -> new CognitionConfig(moodEnabled, drivesEnabled, mentalModelEnabled, userModelEnabled, strategyEnabled, narrativeEnabled, goalsEnabled, memoryHygieneEnabled, innerLifeEnabled, directivePrompts, characterDrivesEnabled, needsPyramidEnabled, attentionEnabled, temporalFocusEnabled, reflectionEnabled, consolidationEnabled, entityKnowledgeEnabled, perspectiveComparisonEnabled, domainActivationEnabled, appraisalEnabled, salienceEnabled, behavioralEnabled, subThoughtsEnabled, otherModelEnabled, regulationEnabled, enabled);
            default -> throw new IllegalArgumentException("Unknown subsystem: " + subsystem);
        };
    }

    public CognitionConfig without(String... subsystems) {
        var config = this;
        for (var s : subsystems) {
            config = config.with(s, false);
        }
        return config;
    }
}
