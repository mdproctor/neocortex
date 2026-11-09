/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.CognitiveResourcePoolConfig;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.RecurringThoughtConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.memory.mood.MoodBaseline;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import io.casehub.neocortex.mindmap.AppraisalWeights;
import io.casehub.neocortex.mindmap.CuriosityConfig;
import io.casehub.neocortex.mindmap.DeclarativeDerivedEdgeRule;
import io.casehub.neocortex.mindmap.DeclarativeTraitRule;
import io.casehub.neocortex.mindmap.MindMapVocabulary;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-agent cognitive configuration record. Derived from personality via {@link CognitiveDerivationEngine}
 * or loaded from YAML cognitive profiles. Explicit YAML values override derived values via
 * {@link CognitiveDerivationEngine#deriveAndMerge}.
 *
 * @param agentId agent identifier (required)
 * @param tenantId tenant scope (nullable)
 * @param personalityProfile personality weights, dominance, expression styles, attachment, PID-5 facets
 * @param moodBaseline PAD emotional resting point for decay (Mehrabian 1996)
 * @param curiosity per-SignalCategory curiosity weights (Berlyne 1960)
 * @param temporalFocus subgraph proximity weights from goals
 * @param cbrStrategy CBR retrieval parameters (minSimilarity, decayDays, retrievalMode)
 * @param socialCognition trust formation rate and conflict interpretation
 * @param graphStructure knowledge graph inference style (CONNECTIVE/CATEGORICAL/BALANCED)
 * @param extractionBias LLM extraction emphasis (relationship bias, affect sensitivity)
 * @param vocabulary registered MindMap edge type vocabularies
 * @param services named SPI selection map
 * @param traitRules declarative trait rules (nullable for global-only)
 * @param derivedEdgeRules declarative derived-edge rules (nullable for global-only)
 * @param descriptor source eidos identity view for re-derivation
 * @param appraisalWeights SEC appraisal dimension weights
 * @param habituationConfig habituation rate, novelty threshold, repetition tolerance
 * @param regulationDefaults reappraisal/suppression/redirect capacities (Barańczuk 2019)
 * @param searchBudgetConfig 4D search budget activation tuning (Mischel and Shoda 1995)
 * @param recurringThoughtConfig fixation/obsession/rumination thresholds (Nolen-Hoeksema 1991)
 */
public record CognitiveDefaults(
        String agentId,
        String tenantId,
        PersonalityProfile personalityProfile,
        MoodBaseline moodBaseline,
        CuriosityConfig curiosity,
        TemporalFocusConfig temporalFocus,
        CbrStrategyDefaults cbrStrategy,
        SocialCognitionDefaults socialCognition,
        GraphStructureDefaults graphStructure,
        ExtractionBiasDefaults extractionBias,
        MindMapVocabulary vocabulary,
        Map<String, String> services,
        List<DeclarativeTraitRule> traitRules,
        List<DeclarativeDerivedEdgeRule> derivedEdgeRules,
        DescriptorView descriptor,
        AppraisalWeights appraisalWeights,
        HabituationConfig habituationConfig,
        RegulationDefaults regulationDefaults,
        SearchBudgetConfig searchBudgetConfig,
        RecurringThoughtConfig recurringThoughtConfig,
        CognitiveCapacityConfig capacityConfig,
        CognitiveResourcePoolConfig cognitiveResourcePoolConfig
) {
    public CognitiveDefaults {
        Objects.requireNonNull(agentId, "agentId required");
        services         = services != null ? Map.copyOf(services) : Map.of();
        traitRules       = traitRules != null ? List.copyOf(traitRules) : null;
        derivedEdgeRules = derivedEdgeRules != null ? List.copyOf(derivedEdgeRules) : null;
    }

    public static CognitiveDefaults empty(String agentId) {
        return new CognitiveDefaults(agentId, null, null, null, null, null, null, null, null, null, null, Map.of(), null, null, null, null, null, null, null, null, null, null);
    }

    public PersonalityWeights personality() {
        return personalityProfile != null ? personalityProfile.personality() : null;
    }

    public Double personalityDominance() {
        return personalityProfile != null ? personalityProfile.personalityDominance() : null;
    }

    public ExpressionStyleDefaults expressionStyles() {
        return personalityProfile != null ? personalityProfile.expressionStyles() : null;
    }

    public CognitiveDefaults withTenantId(String tenantId) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withPersonalityProfile(PersonalityProfile personalityProfile) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withPersonality(PersonalityWeights personality) {
        var pp = personalityProfile != null ? personalityProfile : new PersonalityProfile(null, null, null, null, List.of());
        return withPersonalityProfile(new PersonalityProfile(personality, pp.personalityDominance(), pp.expressionStyles(), pp.attachment(), pp.facets()));
    }

    public CognitiveDefaults withPersonalityDominance(Double personalityDominance) {
        var pp = personalityProfile != null ? personalityProfile : new PersonalityProfile(null, null, null, null, List.of());
        return withPersonalityProfile(new PersonalityProfile(pp.personality(), personalityDominance, pp.expressionStyles(), pp.attachment(), pp.facets()));
    }

    public CognitiveDefaults withExpressionStyles(ExpressionStyleDefaults expressionStyles) {
        var pp = personalityProfile != null ? personalityProfile : new PersonalityProfile(null, null, null, null, List.of());
        return withPersonalityProfile(new PersonalityProfile(pp.personality(), pp.personalityDominance(), expressionStyles, pp.attachment(), pp.facets()));
    }

    public CognitiveDefaults withMoodBaseline(MoodBaseline moodBaseline) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withCuriosity(CuriosityConfig curiosity) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withTemporalFocus(TemporalFocusConfig temporalFocus) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withCbrStrategy(CbrStrategyDefaults cbrStrategy) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withSocialCognition(SocialCognitionDefaults socialCognition) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withGraphStructure(GraphStructureDefaults graphStructure) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withExtractionBias(ExtractionBiasDefaults extractionBias) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withVocabulary(MindMapVocabulary vocabulary) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withServices(Map<String, String> services) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withTraitRules(List<DeclarativeTraitRule> traitRules) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withDerivedEdgeRules(List<DeclarativeDerivedEdgeRule> derivedEdgeRules) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withDescriptor(DescriptorView descriptor) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withAppraisalWeights(AppraisalWeights appraisalWeights) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withHabituationConfig(HabituationConfig habituationConfig) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withRegulationDefaults(RegulationDefaults regulationDefaults) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withSearchBudgetConfig(SearchBudgetConfig searchBudgetConfig) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withRecurringThoughtConfig(RecurringThoughtConfig recurringThoughtConfig) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }

    public CognitiveDefaults withCapacityConfig(CognitiveCapacityConfig capacityConfig) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }


    public CognitiveDefaults withCognitiveResourcePoolConfig(CognitiveResourcePoolConfig cognitiveResourcePoolConfig) {
        return new CognitiveDefaults(agentId, tenantId, personalityProfile, moodBaseline, curiosity, temporalFocus, cbrStrategy, socialCognition, graphStructure, extractionBias, vocabulary, services, traitRules, derivedEdgeRules, descriptor, appraisalWeights, habituationConfig, regulationDefaults, searchBudgetConfig, recurringThoughtConfig, capacityConfig, cognitiveResourcePoolConfig);
    }
}
