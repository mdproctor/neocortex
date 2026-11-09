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

import io.casehub.neocortex.cognitive.ChannelModulationConfig;
import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.CognitiveResourcePoolConfig;
import io.casehub.neocortex.cognitive.ExtremeInteractionConfig;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.RecurringThoughtConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.SearchFatigueEffects;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.mood.MoodBaseline;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import io.casehub.neocortex.mindmap.AppraisalWeights;
import io.casehub.neocortex.mindmap.CuriosityConfig;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CognitiveDerivationEngine {

    // Jungian cognitive function → MemoryDomain weight mappings (Jung 1921). Weights reflect
    // semantic alignment between function and domain — calibrated empirically.
    private static final Map<String, Map<String, Double>> FUNCTION_WEIGHTS = Map.of(
        "ni", Map.of("reflection", 1.5, "experience", 0.8),
        "te", Map.of("experience", 1.3, "reflection", 1.0),
        "fi", Map.of("reflection", 1.2, "relationship", 1.1),
        "fe", Map.of("relationship", 1.4, "engagement", 1.3),
        "se", Map.of("experience", 1.5, "mood", 0.7),
        "si", Map.of("experience", 1.2, "reflection", 0.9),
        "ne", Map.of("reflection", 1.3, "experience", 1.1),
        "ti", Map.of("reflection", 1.4, "experience", 0.9)
    );

    // Disposition → PAD baseline mappings (Mehrabian 1996). Values calibrated empirically.
    private static final Map<String, Double> RISK_PLEASURE = Map.of(
        "conservative", -0.1,
        "calculated", 0.1,
        "bold", 0.3
    );

    private static final Map<String, Double> SOCIAL_AROUSAL = Map.of(
        "independent", -0.1,
        "cooperative", 0.1,
        "competitive", 0.2
    );

    private static final Map<String, Double> AUTONOMY_DOMINANCE = Map.of(
        "low", -0.2,
        "moderate", 0.2,
        "high", 0.4
    );
    // Disposition → curiosity SignalCategory weights (Berlyne 1960). Calibrated empirically.
    private static final Map<String, Double> AUTONOMY_STRUCTURAL = Map.of(
        "low", 0.8,
        "moderate", 1.0,
        "high", 1.3
    );

    private static final Map<String, Double> RULE_QUALITY = Map.of(
        "flexible", 0.8,
        "moderate", 1.0,
        "strict", 1.3
    );

    private static final Map<String, Double> SOCIAL_CENTRALITY = Map.of(
        "independent", 0.8,
        "cooperative", 1.2,
        "competitive", 1.0
    );

    // Goal keyword → MindMap subgraph type. Operational mapping, calibrated empirically.
    private static final Map<String, String> GOAL_SUBGRAPH_MAPPING = Map.of(
        "career", "PROJECT",
        "professional", "PROJECT",
        "work", "PROJECT",
        "family", "PERSON",
        "personal", "PERSON",
        "relationship", "PERSON",
        "research", "RESEARCH_AREA",
        "academic", "RESEARCH_AREA",
        "business", "ORGANISATION"
    );

    // CBR retrieval parameters from disposition. Calibrated empirically.
    private static final Map<String, Double> RULE_MIN_SIMILARITY = Map.of(
        "flexible", 0.35,
        "moderate", 0.5,
        "strict", 0.65
    );

    private static final Map<String, Integer> RISK_DECAY_DAYS = Map.of(
        "conservative", 60,
        "calculated", 90,
        "bold", 180
    );
    // Habituation parameters from disposition. Calibrated empirically.
    private static final Map<String, Double>  RISK_HABITUATION_RATE = Map.of(
            "conservative", 0.1,
            "calculated", 0.2,
            "bold", 0.4
                                                                            );
    private static final Map<String, Double>  RISK_NOVELTY_THRESHOLD = Map.of(
            "conservative", 0.2,
            "calculated", 0.3,
            "bold", 0.5
                                                                             );
    private static final Map<String, Double>  RULE_REPETITION_TOLERANCE = Map.of(
            "flexible", 3.0,
            "moderate", 5.0,
            "strict", 8.0
                                                                                );


    private static final Map<String, RetrievalMode> RULE_RETRIEVAL_MODE = Map.of(
        "flexible", RetrievalMode.SEMANTIC_ONLY,
        "moderate", RetrievalMode.HYBRID,
        "strict", RetrievalMode.FEATURE_ONLY
    );

    // Social cognition parameters from disposition. Calibrated empirically.
    private static final Map<String, Double> SOCIAL_TRUST_RATE = Map.of(
        "independent", 0.3,
        "cooperative", 0.7,
        "competitive", 0.4
    );

    private static final Map<String, ConflictInterpretation> CONFLICT_INTERPRETATION = Map.of(
        "cooperative", ConflictInterpretation.REPAIR,
        "competitive", ConflictInterpretation.INFORMATION,
        "analytical", ConflictInterpretation.NEUTRAL,
        "avoidant", ConflictInterpretation.DISENGAGE
    );
    // Regulation strategy capacity weight tables. Grounded in Barańczuk (2019) meta-analysis
    // of Big Five → emotion regulation correlations. Calibrated post-eval (Phase 2, #830).
    private static final Map<String, Double>                 REAPPRAISAL_CONFLICT    = Map.of(
            "analytical", 0.30, "cooperative", 0.15, "avoidant", -0.15, "competitive", 0.05);
    private static final Map<String, Double>                 REAPPRAISAL_AUTONOMY    = Map.of(
            "high", 0.25, "moderate", 0.05, "low", -0.10);
    private static final Map<String, Double>                 REAPPRAISAL_RULES       = Map.of(
            "strict", 0.15, "flexible", 0.15, "moderate", 0.05);
    private static final Map<String, Double>                 REAPPRAISAL_RISK        = Map.of(
            "bold", 0.20, "calculated", 0.05, "conservative", -0.10);
    private static final Map<String, Double>                 REAPPRAISAL_SOCIAL      = Map.of(
            "cooperative", 0.10, "independent", 0.05, "competitive", 0.05);

    private static final Map<String, Double> SUPPRESSION_CONFLICT = Map.of(
            "avoidant", 0.10, "competitive", 0.15, "analytical", -0.10, "cooperative", -0.05);
    private static final Map<String, Double> SUPPRESSION_SOCIAL   = Map.of(
            "competitive", 0.10, "independent", 0.05, "cooperative", -0.05);
    private static final Map<String, Double> SUPPRESSION_RISK     = Map.of(
            "conservative", 0.15, "calculated", 0.05, "bold", -0.10);
    private static final Map<String, Double> SUPPRESSION_AUTONOMY = Map.of(
            "low", 0.10, "moderate", 0.05, "high", -0.05);
    private static final Map<String, Double> SUPPRESSION_RULES    = Map.of(
            "strict", 0.05, "moderate", 0.0, "flexible", -0.05);

    private static final Map<String, Double> REDIRECT_CONFLICT = Map.of(
            "avoidant", 0.25, "cooperative", 0.05, "analytical", -0.15, "competitive", -0.10);
    private static final Map<String, Double> REDIRECT_RISK     = Map.of(
            "conservative", 0.20, "calculated", 0.05, "bold", -0.15);
    private static final Map<String, Double> REDIRECT_AUTONOMY = Map.of(
            "low", 0.10, "moderate", 0.05, "high", -0.10);
    private static final Map<String, Double> REDIRECT_SOCIAL   = Map.of(
            "independent", 0.05, "cooperative", 0.0, "competitive", -0.05);
    private static final Map<String, Double> REDIRECT_RULES    = Map.of(
            "strict", 0.0, "moderate", 0.0, "flexible", -0.10);
    private static final Map<String, Double> SOCIAL_REG_SOCIAL = Map.of(
            "gregarious", 0.20, "reserved", -0.15, "selective", 0.05,
            "cooperative", 0.10, "competitive", -0.05, "independent", -0.10);
    private static final Map<String, Double> SOCIAL_REG_CONFLICT = Map.of(
            "harmonizing", 0.15, "confrontational", -0.10, "analytical", 0.0, "avoidant", -0.05);
    private static final Map<String, Double> SOCIAL_REG_AUTONOMY = Map.of(
            "independent", -0.10, "collaborative", 0.10, "dependent", 0.15,
            "high", -0.10, "moderate", 0.05, "low", 0.10);


    private CognitiveDerivationEngine() {}

    public static CognitiveDefaults derive(DescriptorView descriptor) {
        PersonalityWeights      personality      = derivePersonality(descriptor.dispositionProfile());
        MoodBaseline            mood             = deriveMoodBaseline(descriptor.disposition());
        CuriosityConfig         curiosity        = deriveCuriosity(descriptor.disposition(), descriptor.goals());
        TemporalFocusConfig     temporalFocus    = deriveTemporalFocus(descriptor.goals());
        CbrStrategyDefaults     cbrStrategy      = deriveCbrStrategy(descriptor.disposition());
        SocialCognitionDefaults socialCognition  = deriveSocialCognition(descriptor.disposition());
        GraphStructureDefaults  graphStructure   = deriveGraphStructure(descriptor.dispositionProfile());
        ExtractionBiasDefaults  extractionBias   = deriveExtractionBias(descriptor.dispositionProfile());
        AppraisalWeights        appraisal        = deriveAppraisalWeights(descriptor.dispositionProfile(), descriptor.disposition());
        HabituationConfig       habituation      = deriveHabituationConfig(descriptor.disposition(), descriptor.goals());
        RegulationDefaults      regulation       = deriveRegulationDefaults(descriptor.disposition());
        SearchBudgetConfig      searchBudget     = deriveSearchBudgetDefaults(descriptor.personalityFacets(), descriptor.disposition(), graphStructure != null ? graphStructure.connectiveBias() : 0.5);
        CognitiveResourcePoolConfig poolConfig   = derivePoolConfig(descriptor.personalityFacets(), descriptor.disposition());
        RecurringThoughtConfig  recurringThought = deriveRecurringThoughtConfig(descriptor.personalityFacets(), descriptor.disposition());
        CognitiveCapacityConfig capacity         = deriveCapacity(descriptor.dispositionProfile());
        double                  personalityDom   = derivePersonalityDominance(descriptor.formationPadSummary());
        ExpressionStyleDefaults expressionStyles = deriveExpressionStyles(descriptor.personalityFacets());
        PersonalityProfile      personalityProf  = new PersonalityProfile(personality, personalityDom, expressionStyles, null, descriptor.personalityFacets());

        return CognitiveDefaults.empty(descriptor.agentId())
                                .withPersonalityProfile(personalityProf)
                                .withMoodBaseline(mood)
                                .withCuriosity(curiosity)
                                .withTemporalFocus(temporalFocus)
                                .withCbrStrategy(cbrStrategy)
                                .withSocialCognition(socialCognition)
                                .withGraphStructure(graphStructure)
                                .withExtractionBias(extractionBias)
                                .withAppraisalWeights(appraisal)
                                .withHabituationConfig(habituation)
                                .withRegulationDefaults(regulation)
                                .withSearchBudgetConfig(searchBudget)
                                .withRecurringThoughtConfig(recurringThought)
                                .withCapacityConfig(capacity)
                                .withCognitiveResourcePoolConfig(poolConfig)
                                .withDescriptor(descriptor);
    }

    public static CognitiveDefaults deriveAndMerge(CognitiveDefaults explicit) {
        if (explicit.descriptor() == null) {
            return explicit;
        }
        CognitiveDefaults derived = derive(explicit.descriptor());
        var mergedProfile = mergePersonalityProfile(explicit.personalityProfile(), derived.personalityProfile());
        return CognitiveDefaults.empty(explicit.agentId())
                                .withTenantId(explicit.tenantId())
                                .withPersonalityProfile(mergedProfile)
                                .withMoodBaseline(explicit.moodBaseline() != null ? explicit.moodBaseline() : derived.moodBaseline())
                                .withCuriosity(explicit.curiosity() != null ? explicit.curiosity() : derived.curiosity())
                                .withTemporalFocus(explicit.temporalFocus() != null ? explicit.temporalFocus() : derived.temporalFocus())
                                .withCbrStrategy(explicit.cbrStrategy() != null ? explicit.cbrStrategy() : derived.cbrStrategy())
                                .withSocialCognition(explicit.socialCognition() != null ? explicit.socialCognition() : derived.socialCognition())
                                .withGraphStructure(explicit.graphStructure() != null ? explicit.graphStructure() : derived.graphStructure())
                                .withExtractionBias(explicit.extractionBias() != null ? explicit.extractionBias() : derived.extractionBias())
                                .withAppraisalWeights(explicit.appraisalWeights() != null ? explicit.appraisalWeights() : derived.appraisalWeights())
                                .withHabituationConfig(explicit.habituationConfig() != null ? explicit.habituationConfig() : derived.habituationConfig())
                                .withRegulationDefaults(explicit.regulationDefaults() != null ? explicit.regulationDefaults() : derived.regulationDefaults())
                                .withSearchBudgetConfig(explicit.searchBudgetConfig() != null ? explicit.searchBudgetConfig() : derived.searchBudgetConfig())
                                .withRecurringThoughtConfig(explicit.recurringThoughtConfig() != null ? explicit.recurringThoughtConfig() : derived.recurringThoughtConfig())
                                .withCapacityConfig(mergeCapacity(explicit.capacityConfig(), derived.capacityConfig()))
                                .withCognitiveResourcePoolConfig(explicit.cognitiveResourcePoolConfig() != null ? explicit.cognitiveResourcePoolConfig() : derived.cognitiveResourcePoolConfig())
                                .withVocabulary(explicit.vocabulary())
                                .withServices(explicit.services())
                                .withTraitRules(explicit.traitRules())
                                .withDerivedEdgeRules(explicit.derivedEdgeRules())
                                .withDescriptor(explicit.descriptor());
    }


    private static PersonalityProfile mergePersonalityProfile(PersonalityProfile explicit, PersonalityProfile derived) {
        if (explicit == null) return derived;
        if (derived == null) return explicit;
        return new PersonalityProfile(
                explicit.personality() != null ? explicit.personality() : derived.personality(),
                explicit.personalityDominance() != null ? explicit.personalityDominance() : derived.personalityDominance(),
                explicit.expressionStyles() != null ? explicit.expressionStyles() : derived.expressionStyles(),
                explicit.attachment() != null ? explicit.attachment() : derived.attachment(),
                !explicit.facets().isEmpty() ? explicit.facets() : derived.facets()
        );
    }

    static CognitiveCapacityConfig deriveCapacity(List<WeightedTerm> profile) {
        if (profile == null || profile.isEmpty()) {return null;}

        double tiWeight = 0.0, teWeight = 0.0, neWeight = 0.0, niWeight = 0.0, siWeight = 0.0;
        for (WeightedTerm term : profile) {
            String fn = term.term().toLowerCase();
            switch (fn) {
                case "ti" -> tiWeight = term.weight();
                case "te" -> teWeight = term.weight();
                case "ne" -> neWeight = term.weight();
                case "ni" -> niWeight = term.weight();
                case "si" -> siWeight = term.weight();
            }
        }

        double relational  = compressCapacity(0.5 * (tiWeight + teWeight));
        double associative = compressCapacity(0.5 * (neWeight + niWeight));
        double wmSpan      = compressCapacity(0.5 * (teWeight + siWeight));

        return new CognitiveCapacityConfig(relational, associative, wmSpan);
    }

    static CognitiveCapacityConfig mergeCapacity(CognitiveCapacityConfig explicit, CognitiveCapacityConfig derived) {
        if (explicit == null) {return derived;}
        if (derived == null) {return explicit;}
        return explicit.mergeWith(derived);
    }

    private static double compressCapacity(double raw) {
        return 0.55 + raw * 0.4;
    }


    /** Maps Jungian cognitive functions to MemoryDomain weights (Jung 1921). Values calibrated empirically. */
    static PersonalityWeights derivePersonality(List<WeightedTerm> profile) {
        if (profile == null || profile.isEmpty()) {
            return null;
        }

        // Collect all domains mentioned by any function in the profile
        var    allDomains  = new LinkedHashSet<String>();
        double totalWeight = 0.0;
        for (WeightedTerm term : profile) {
            Map<String, Double> functionMap = FUNCTION_WEIGHTS.get(term.term().toLowerCase());
            if (functionMap != null) {
                allDomains.addAll(functionMap.keySet());
                totalWeight += term.weight();
            }
        }
        if (totalWeight == 0.0) {return null;}

        // Weighted average per domain — absent function defaults to 1.0 (neutral)
        Map<MemoryDomain, Double> domainWeights = new HashMap<>();
        for (String domain : allDomains) {
            double weightedSum = 0.0;
            for (WeightedTerm term : profile) {
                Map<String, Double> functionMap = FUNCTION_WEIGHTS.get(term.term().toLowerCase());
                if (functionMap == null) {continue;}
                double domainValue = functionMap.getOrDefault(domain, 1.0);
                weightedSum += term.weight() * domainValue;
            }
            double result = weightedSum / totalWeight;
            if (result > 0.0) {
                domainWeights.put(new MemoryDomain(domain), result);
            }
        }

        return domainWeights.isEmpty() ? null : new PersonalityWeights(domainWeights);
    }

    /** Maps disposition axes to PAD emotional baseline (Mehrabian 1996). riskAppetite→pleasure, socialOrient→arousal, autonomy→dominance. */
    static MoodBaseline deriveMoodBaseline(DispositionAxes axes) {
        if (axes == null) return null;

        double pleasure = RISK_PLEASURE.getOrDefault(
            lower(axes.riskAppetite()), 0.0);
        double arousal = SOCIAL_AROUSAL.getOrDefault(
            lower(axes.socialOrient()), 0.0);
        double dominance = AUTONOMY_DOMINANCE.getOrDefault(
            lower(axes.autonomy()), 0.0);

        return new MoodBaseline(pleasure, arousal, dominance);
    }

    /** Maps disposition to curiosity SignalCategory weights (Berlyne 1960). Goal boost calibrated empirically. */
    static CuriosityConfig deriveCuriosity(DispositionAxes axes, List<String> goals) {
        if (axes == null) {return null;}

        Map<String, Double> weights = new HashMap<>();
        weights.put("STRUCTURAL", AUTONOMY_STRUCTURAL.getOrDefault(lower(axes.autonomy()), 1.0));
        weights.put("QUALITY", RULE_QUALITY.getOrDefault(lower(axes.ruleFollowing()), 1.0));
        weights.put("CENTRALITY", SOCIAL_CENTRALITY.getOrDefault(lower(axes.socialOrient()), 1.0));

        if (goals != null) {
            for (String goal : goals) {
                if (goal != null && goal.toLowerCase().contains("strategic")) {
                    weights.merge("CENTRALITY", 0.2, Double::sum);
                    weights.merge("STRUCTURAL", 0.2, Double::sum);
                    break;
                }
            }
        }

        return CuriosityConfig.defaults().withCategoryWeights(weights);
    }

    /** Maps goal keywords to subgraph proximity weights. Operational mapping, calibrated empirically. */
    static TemporalFocusConfig deriveTemporalFocus(List<String> goals) {
        if (goals == null || goals.isEmpty()) {return null;}

        Map<String, Double> weights = new HashMap<>();
        for (String goal : goals) {
            if (goal == null) {continue;}
            String lowerGoal = goal.toLowerCase();
            for (var entry : GOAL_SUBGRAPH_MAPPING.entrySet()) {
                if (lowerGoal.contains(entry.getKey())) {
                    weights.merge(entry.getValue(), 0.3, Double::sum);
                }
            }
        }

        if (weights.isEmpty()) {return null;}

        weights.replaceAll((k, v) -> 1.0 + v);
        return TemporalFocusConfig.defaults().withSubgraphProximityWeights(weights);
    }

    /** Maps rule-following and risk appetite to CBR retrieval parameters. Calibrated empirically. */
    static CbrStrategyDefaults deriveCbrStrategy(DispositionAxes axes) {
        if (axes == null) return null;

        double minSimilarity = RULE_MIN_SIMILARITY.getOrDefault(lower(axes.ruleFollowing()), 0.5);
        int decayDays = RISK_DECAY_DAYS.getOrDefault(lower(axes.riskAppetite()), 90);
        RetrievalMode mode = RULE_RETRIEVAL_MODE.getOrDefault(lower(axes.ruleFollowing()), RetrievalMode.HYBRID);

        return new CbrStrategyDefaults(minSimilarity, decayDays, mode);
    }

    /** Maps social orientation to trust formation rate and conflict interpretation. Calibrated empirically. */
    static SocialCognitionDefaults deriveSocialCognition(DispositionAxes axes) {
        if (axes == null) return null;

        double trustRate = SOCIAL_TRUST_RATE.getOrDefault(lower(axes.socialOrient()), 0.5);
        ConflictInterpretation conflictMode = CONFLICT_INTERPRETATION.getOrDefault(lower(axes.conflictMode()), ConflictInterpretation.NEUTRAL);

        return new SocialCognitionDefaults(trustRate, conflictMode);
    }

    private static final Set<String> HOLISTIC_FUNCTIONS = Set.of("ni", "fe", "ne", "fi");

    /** Maps Jungian function distribution to inference style. Holistic (ni/fe/ne/fi) proportion drives connectiveBias; thresholds calibrated empirically. */
    static GraphStructureDefaults deriveGraphStructure(List<WeightedTerm> profile) {
        if (profile == null || profile.isEmpty()) return null;

        double holisticWeight = 0.0;
        double totalWeight = 0.0;
        for (WeightedTerm term : profile) {
            totalWeight += term.weight();
            if (HOLISTIC_FUNCTIONS.contains(term.term().toLowerCase())) {
                holisticWeight += term.weight();
            }
        }
        if (totalWeight == 0.0) return null;

        double connectiveBias = holisticWeight / totalWeight;
        GraphStructureDefaults.InferenceStyle style;
        if (connectiveBias > 0.6) {
            style = GraphStructureDefaults.InferenceStyle.CONNECTIVE;
        } else if (connectiveBias < 0.4) {
            style = GraphStructureDefaults.InferenceStyle.CATEGORICAL;
        } else {
            style = GraphStructureDefaults.InferenceStyle.BALANCED;
        }

        return new GraphStructureDefaults(style, connectiveBias);
    }

    private static final Set<String> ANALYTICAL_FUNCTIONS = Set.of("ni", "te", "ti", "si");
    private static final Set<String> EMPATHETIC_FUNCTIONS = Set.of("fe", "fi");

    /** Maps analytical/empathetic function proportions to extraction emphasis. Formula 0.8 + 0.6 * ratio calibrated empirically. */
    static ExtractionBiasDefaults deriveExtractionBias(List<WeightedTerm> profile) {
        if (profile == null || profile.isEmpty()) return null;

        double analyticalWeight = 0.0;
        double empatheticWeight = 0.0;
        double totalWeight = 0.0;
        for (WeightedTerm term : profile) {
            String fn = term.term().toLowerCase();
            totalWeight += term.weight();
            if (ANALYTICAL_FUNCTIONS.contains(fn)) analyticalWeight += term.weight();
            if (EMPATHETIC_FUNCTIONS.contains(fn)) empatheticWeight += term.weight();
        }
        if (totalWeight == 0.0) return null;

        double relationshipBias = 0.8 + 0.6 * (analyticalWeight / totalWeight);
        double affectSensitivity = 0.8 + 0.6 * (empatheticWeight / totalWeight);

        return new ExtractionBiasDefaults(relationshipBias, affectSensitivity);
    }

    // JPAF function → appraisal dimension contribution weights. Calibrated empirically
    // via JPAF→Big Five→appraisal chain. SCALE_FACTOR produces weights in [0,1] range.
    private static final double SCALE_FACTOR = 1.5;

    private static final Map<String, Double> URGENCY_CONTRIBUTION = Map.of(
        "te", 1.0,  "fe", 1.0,
        "ne", -1.0, "se", -1.0
    );

    private static final Map<String, Double> RELATIONSHIP_CONTRIBUTION = Map.of(
        "fe", 1.0,   "fi", 0.665,
        "ti", -1.0,  "te", -0.665
    );

    private static final Map<String, Double> FEAR_CONTRIBUTION = Map.of(
        "ni", -1.0,  "ne", -0.747,
        "si", 0.747,  "se", 1.0
    );

    /** Maps JPAF functions to appraisal weight contributions via Big Five intermediary. SCALE_FACTOR=1.5 calibrated to produce weights in [0,1]. */
    static AppraisalWeights deriveAppraisalWeights(List<WeightedTerm> profile, DispositionAxes axes) {
        if (profile == null || profile.isEmpty()) {return null;}

        double urgencyRaw  = 0.0, relRaw = 0.0, fearRaw = 0.0;
        double totalWeight = 0.0;

        for (WeightedTerm term : profile) {
            String fn = term.term().toLowerCase();
            double w  = term.weight();
            totalWeight += w;

            urgencyRaw += w * URGENCY_CONTRIBUTION.getOrDefault(fn, 0.0);
            relRaw += w * RELATIONSHIP_CONTRIBUTION.getOrDefault(fn, 0.0);
            fearRaw += w * FEAR_CONTRIBUTION.getOrDefault(fn, 0.0);
        }

        if (totalWeight == 0.0) {return null;}

        urgencyRaw /= totalWeight;
        relRaw /= totalWeight;
        fearRaw /= totalWeight;

        double selfStrictness  = 1.0;
        double otherStrictness = 1.0;

        if (axes != null) {
            if (axes.ruleFollowing() != null) {
                switch (axes.ruleFollowing()) {
                    case "strict" -> {selfStrictness  = 1.6;
                                      otherStrictness = 1.4;
                    }
                    case "flexible" -> {selfStrictness  = 0.7;
                                        otherStrictness = 0.6;
                    }
                    default -> {}
                }
            }
            if (axes.socialOrient() != null) {
                switch (axes.socialOrient()) {
                    case "cooperative" -> otherStrictness -= 0.2;
                    case "competitive" -> otherStrictness += 0.2;
                    default -> {}
                }
            }
        }

        return new AppraisalWeights(
                Math.max(1.0 + urgencyRaw * SCALE_FACTOR, 0.1),
                Math.max(1.0 + relRaw * SCALE_FACTOR, 0.1),
                Math.max(1.0 + fearRaw * SCALE_FACTOR, 0.1),
                Math.clamp(selfStrictness, 0.5, 2.0),
                Math.clamp(otherStrictness, 0.5, 2.0)
        );
    }

    /** Maps risk appetite and rule-following to habituation parameters. Calibrated empirically. */
    static HabituationConfig deriveHabituationConfig(DispositionAxes axes, List<String> goals) {
        if (axes == null) {return null;}

        double habRate = RISK_HABITUATION_RATE.getOrDefault(lower(axes.riskAppetite()), 0.2);
        double novelty = RISK_NOVELTY_THRESHOLD.getOrDefault(lower(axes.riskAppetite()), 0.3);
        double repTol  = RULE_REPETITION_TOLERANCE.getOrDefault(lower(axes.ruleFollowing()), 5.0);

        if ("high".equalsIgnoreCase(axes.autonomy())) {
            repTol -= 1.0;
        }

        Map<String, Double> domainMod = new HashMap<>();
        if (goals != null) {
            for (String goal : goals) {
                if (goal == null) {continue;}
                String lowerGoal = goal.toLowerCase();
                for (var entry : GOAL_SUBGRAPH_MAPPING.entrySet()) {
                    if (lowerGoal.contains(entry.getKey())) {
                        domainMod.put(entry.getValue(), 0.5);
                    }
                }
            }
        }

        return new HabituationConfig(habRate, novelty, Math.max(2.0, repTol), domainMod);
    }

    /** Maps 5 disposition axes to regulation strategy capacities. Grounded in Barańczuk (2019) meta-analysis of Big Five→regulation. Weight tables calibrated post-eval (Phase 2, #830). */
    public static RegulationDefaults deriveRegulationDefaults(DispositionAxes axes) {
        if (axes == null) {return null;}
        double reappraisal = 0.5
                             + REAPPRAISAL_CONFLICT.getOrDefault(lower(axes.conflictMode()), 0.0)
                             + REAPPRAISAL_AUTONOMY.getOrDefault(lower(axes.autonomy()), 0.0)
                             + REAPPRAISAL_RULES.getOrDefault(lower(axes.ruleFollowing()), 0.0)
                             + REAPPRAISAL_RISK.getOrDefault(lower(axes.riskAppetite()), 0.0)
                             + REAPPRAISAL_SOCIAL.getOrDefault(lower(axes.socialOrient()), 0.0);
        double suppression = 0.5
                             + SUPPRESSION_CONFLICT.getOrDefault(lower(axes.conflictMode()), 0.0)
                             + SUPPRESSION_SOCIAL.getOrDefault(lower(axes.socialOrient()), 0.0)
                             + SUPPRESSION_RISK.getOrDefault(lower(axes.riskAppetite()), 0.0)
                             + SUPPRESSION_AUTONOMY.getOrDefault(lower(axes.autonomy()), 0.0)
                             + SUPPRESSION_RULES.getOrDefault(lower(axes.ruleFollowing()), 0.0);
        double redirect = 0.3
                          + REDIRECT_CONFLICT.getOrDefault(lower(axes.conflictMode()), 0.0)
                          + REDIRECT_RISK.getOrDefault(lower(axes.riskAppetite()), 0.0)
                          + REDIRECT_AUTONOMY.getOrDefault(lower(axes.autonomy()), 0.0)
                          + REDIRECT_SOCIAL.getOrDefault(lower(axes.socialOrient()), 0.0)
                          + REDIRECT_RULES.getOrDefault(lower(axes.ruleFollowing()), 0.0);
        double social = 0.5
                        + SOCIAL_REG_SOCIAL.getOrDefault(lower(axes.socialOrient()), 0.0)
                        + SOCIAL_REG_CONFLICT.getOrDefault(lower(axes.conflictMode()), 0.0)
                        + SOCIAL_REG_AUTONOMY.getOrDefault(lower(axes.autonomy()), 0.0);
        return new RegulationDefaults(
                Math.max(0.0, Math.min(1.0, reappraisal)),
                Math.max(0.0, Math.min(1.0, suppression)),
                Math.max(0.0, Math.min(1.0, redirect)),
                Math.max(0.0, Math.min(1.0, social)));
    }

    static RecurringThoughtConfig deriveRecurringThoughtConfig(List<PersonalityFacet> facets, DispositionAxes axes) {
        if (facets == null || facets.isEmpty()) { return null; }

        var defaults = RecurringThoughtConfig.defaults();

        double anxiousness = facetScore(facets, PersonalityFacet.ANXIOUSNESS, 0.5);
        double emotionalLability = facetScore(facets, PersonalityFacet.EMOTIONAL_LABILITY, 0.5);
        double withdrawal = facetScore(facets, PersonalityFacet.WITHDRAWAL, 0.5);
        double impulsivity = facetScore(facets, PersonalityFacet.IMPULSIVITY, 0.5);
        double riskTaking = facetScore(facets, PersonalityFacet.RISK_TAKING, 0.5);
        double suspiciousness = facetScore(facets, PersonalityFacet.SUSPICIOUSNESS, 0.5);
        double intimacyAvoidance = facetScore(facets, PersonalityFacet.INTIMACY_AVOIDANCE, 0.5);

        double ruleFollowingNumeric = axes != null
                ? RULE_FOLLOWING_NUMERIC.getOrDefault(lower(axes.ruleFollowing()), 0.5) : 0.5;

        // Neuroticism (Anxiousness + Emotional Lability) -> lower fixation threshold
        // Eysenck (1967): neuroticism amplifies negative affect sensitivity
        // Continuous modulation: high anxiousness (0.9) lowers by 0.08, moderate (0.5) by 0.0
        double fixation = defaults.fixationThreshold()
                - (anxiousness - 0.5) * 0.2
                - (emotionalLability - 0.5) * 0.1;

        // Conscientiousness (ruleFollowing + low Impulsivity) -> higher obsession threshold
        // Roberts et al. (2014): self-regulation buffers against loss of control
        double conscientiousness = (ruleFollowingNumeric + 1.0 - impulsivity) / 2.0;
        double obsession = defaults.obsessionThreshold()
                + (conscientiousness - 0.5) * 0.15;

        // Withdrawal + Intimacy Avoidance -> lower rumination threshold
        // Nolen-Hoeksema (1991): social isolation amplifies circular thinking
        double isolation = (withdrawal + intimacyAvoidance) / 2.0;
        double rumination = defaults.ruminationThreshold()
                - (isolation - 0.5) * 0.15;

        // Openness proxy (low Withdrawal + low Suspiciousness + Risk Taking) -> broader window
        // DeYoung (2015): open individuals shift attention more fluidly
        double openness = (1.0 - withdrawal + 1.0 - suspiciousness + riskTaking) / 3.0;
        int recurrenceDays = (int) Math.round(7.0 + (openness - 0.5) * 4.0);

        // Symptom weights modulated by personality
        Map<String, Double> weights = new HashMap<>();
        if (anxiousness > 0.6) {
            weights.put("arousalTrajectory", 1.0 + (anxiousness - 0.5));
            weights.put("valence", 1.0 + (emotionalLability - 0.5));
        }
        if (conscientiousness > 0.6) {
            weights.put("attentionalCapture", 1.0 - (conscientiousness - 0.5) * 0.5);
        }

        return new RecurringThoughtConfig(
                weights,
                Math.max(0.1, fixation),
                Math.max(fixation + 0.1, obsession),
                Math.max(obsession + 0.05, rumination),
                java.time.Duration.ofDays(Math.max(3, recurrenceDays)),
                3);
    }


    /** Derives personality dominance from formation PAD summary (dominance-weighted reward ratio). Calibrated empirically. */
    static double derivePersonalityDominance(FormationPadSummary summary) {
        if (summary == null || summary.memoryCount() == 0 || summary.totalPositivePleasure() == 0.0) {
            return 0.5;
        }
        double ratio = summary.dominanceWeightedReward() / summary.totalPositivePleasure();
        return Math.clamp((ratio + 1.0) / 2.0, 0.0, 1.0);
    }

    static ExpressionStyleDefaults deriveExpressionStyles(List<PersonalityFacet> facets) {
        if (facets == null || facets.isEmpty()) {return null;}

        var styles = new HashMap<String, String>();

        // Triumph expression — Grandiosity × Callousness interaction
        // McAdams (2001) Level 2 characteristic adaptations: same trait produces
        // different behavioral signatures depending on co-occurring traits
        if (isHigh(facets, PersonalityFacet.GRANDIOSITY) && isHigh(facets, PersonalityFacet.CALLOUSNESS)) {
            styles.put("triumph", "public, theatrical, needs audience to witness");
        } else if (isHigh(facets, PersonalityFacet.GRANDIOSITY) && isLow(facets, PersonalityFacet.CALLOUSNESS)) {
            styles.put("triumph", "expansive, generous, wants to share the glory");
        } else if (isLow(facets, PersonalityFacet.GRANDIOSITY)) {
            styles.put("triumph", "private, quiet satisfaction, deflects credit");
        }

        // Gloating expression — Antagonism triad (Grandiosity + Callousness + Manipulativeness)
        // Paulhus & Williams (2002) Dark Triad: convergence of narcissism, psychopathy,
        // Machiavellianism predicts schadenfreude and dominance displays
        if (isHigh(facets, PersonalityFacet.GRANDIOSITY) && isHigh(facets, PersonalityFacet.CALLOUSNESS)
            && isHigh(facets, PersonalityFacet.MANIPULATIVENESS)) {
            styles.put("gloating", "savoring, deliberate, wants the other to know");
        } else if (isHigh(facets, PersonalityFacet.CALLOUSNESS) && !isHigh(facets, PersonalityFacet.GRANDIOSITY)) {
            styles.put("gloating", "cold satisfaction, indifferent rather than triumphant");
        } else if (isLow(facets, PersonalityFacet.CALLOUSNESS)) {
            styles.put("gloating", "uncomfortable with others' misfortune, suppresses it");
        }

        // Scheming expression — Manipulativeness × Suspiciousness
        // Mischel & Shoda (1995) CAPS: if-then profiles predict situation-specific strategy selection
        if (isHigh(facets, PersonalityFacet.MANIPULATIVENESS) && isHigh(facets, PersonalityFacet.SUSPICIOUSNESS)) {
            styles.put("scheming", "elaborate, multi-step, assumes others are doing the same");
        } else if (isHigh(facets, PersonalityFacet.MANIPULATIVENESS) && isLow(facets, PersonalityFacet.SUSPICIOUSNESS)) {
            styles.put("scheming", "opportunistic, charm-based, reads openings not threats");
        } else if (isLow(facets, PersonalityFacet.MANIPULATIVENESS)) {
            styles.put("scheming", "transparent, prefers direct negotiation");
        }

        // Proving-worth expression — Grandiosity × Hostility interaction
        // Krueger et al. (2012) AMPD: Grandiosity facet reflects both compensatory
        // and genuine self-enhancement depending on co-occurring Negative Affectivity
        if (isHigh(facets, PersonalityFacet.GRANDIOSITY) && isHigh(facets, PersonalityFacet.HOSTILITY)) {
            styles.put("proving-worth", "combative, proving through dominance and comparison");
        } else if (isHigh(facets, PersonalityFacet.GRANDIOSITY) && isLow(facets, PersonalityFacet.HOSTILITY)) {
            styles.put("proving-worth", "demonstrative, seeking admiration through competence display");
        } else if (isLow(facets, PersonalityFacet.GRANDIOSITY)) {
            styles.put("proving-worth", "quiet competence, lets work speak, uncomfortable with praise");
        }

        // Gallantry expression — inverse of Callousness × Suspiciousness
        // Costa & McCrae (1992) FFM: high Agreeableness (low AMPD Antagonism) predicts
        // prosocial helping motivated by empathic concern vs. reciprocity expectations
        if (isLow(facets, PersonalityFacet.CALLOUSNESS) && isLow(facets, PersonalityFacet.SUSPICIOUSNESS)) {
            styles.put("gallantry", "genuine, protective, feels others' vulnerability");
        } else if (isLow(facets, PersonalityFacet.CALLOUSNESS) && isHigh(facets, PersonalityFacet.SUSPICIOUSNESS)) {
            styles.put("gallantry", "cautious protectiveness, helps but watches for betrayal");
        } else if (isHigh(facets, PersonalityFacet.CALLOUSNESS)) {
            styles.put("gallantry", "instrumental, helps when it serves a purpose");
        }

        // Distrust expression — Suspiciousness × Hostility
        // Krueger et al. (2012): Suspiciousness loads on both Detachment and Psychoticism,
        // co-occurring Hostility shifts from avoidant vigilance to aggressive preemption
        if (isHigh(facets, PersonalityFacet.SUSPICIOUSNESS) && isHigh(facets, PersonalityFacet.HOSTILITY)) {
            styles.put("distrust", "aggressive vigilance, reads threats into neutral behavior");
        } else if (isHigh(facets, PersonalityFacet.SUSPICIOUSNESS) && isLow(facets, PersonalityFacet.HOSTILITY)) {
            styles.put("distrust", "watchful withdrawal, avoids rather than confronts");
        } else if (isLow(facets, PersonalityFacet.SUSPICIOUSNESS)) {
            styles.put("distrust", "trusts by default, slow to suspect");
        }

        // Territorial expression — Hostility × Risk Taking
        // Patrick et al. (2009) Triarchic: boldness + meanness interaction
        // determines whether resource defense is proactive or reactive
        if (isHigh(facets, PersonalityFacet.HOSTILITY) && isHigh(facets, PersonalityFacet.RISK_TAKING)) {
            styles.put("territorial", "aggressive, preemptive, expands boundaries");
        } else if (isHigh(facets, PersonalityFacet.HOSTILITY) && isLow(facets, PersonalityFacet.RISK_TAKING)) {
            styles.put("territorial", "defensive, reactive, guards existing ground");
        } else if (isLow(facets, PersonalityFacet.HOSTILITY)) {
            styles.put("territorial", "shares space easily, low boundary sensitivity");
        }

        return styles.isEmpty() ? null : new ExpressionStyleDefaults(styles);
    }

    // Ordinal ruleFollowing → numeric proxy for continuous derivation formulas.
    private static final Map<String, Double> RULE_FOLLOWING_NUMERIC = Map.of(
            "strict", 0.8, "moderate", 0.5, "flexible", 0.2
    );

    /**
     * Derives personality-shaped search budget defaults from PID-5 facets and
     * disposition axes. Each parameter mapping traces to published findings.
     * <p>
     * Breadth ← connectiveBias + Risk Taking (DeYoung 2015, Grajzel et al. 2023 r=0.20 DT).
     * Laterality ← low Withdrawal + low Intimacy Avoidance + low Suspiciousness (DeYoung 2015).
     * Depth ← ruleFollowing + low Impulsivity (Krueger et al. 2012 PID-5 dimensional alignment).
     * Convergence ← ruleFollowing modulated by Anxiousness + Emotional Lability (Davis & Nolen-Hoeksema 1998).
     *
     * @param facets         PID-5 personality facets with scores in [0,1]
     * @param axes           disposition axes (String values: strict/moderate/flexible etc.)
     * @param connectiveBias from GraphStructureDefaults (0-1), higher = broader associative network
     * @return personality-derived SearchBudgetConfig, or null if insufficient data
     */
    static SearchBudgetConfig deriveSearchBudgetDefaults(
            List<PersonalityFacet> facets, DispositionAxes axes, double connectiveBias) {
        if (facets == null || facets.isEmpty()) {return null;}

        var defaults = SearchBudgetConfig.defaults();

        double riskTaking        = facetScore(facets, PersonalityFacet.RISK_TAKING, 0.5);
        double impulsivity       = facetScore(facets, PersonalityFacet.IMPULSIVITY, 0.5);
        double withdrawal        = facetScore(facets, PersonalityFacet.WITHDRAWAL, 0.5);
        double intimacyAvoidance = facetScore(facets, PersonalityFacet.INTIMACY_AVOIDANCE, 0.5);
        double suspiciousness    = facetScore(facets, PersonalityFacet.SUSPICIOUSNESS, 0.5);
        double anxiousness       = facetScore(facets, PersonalityFacet.ANXIOUSNESS, 0.5);
        double emotionalLability = facetScore(facets, PersonalityFacet.EMOTIONAL_LABILITY, 0.5);

        double ruleFollowingNumeric = axes != null
                                      ? RULE_FOLLOWING_NUMERIC.getOrDefault(lower(axes.ruleFollowing()), 0.5) : 0.5;

        double breadthSteepness = defaults.breadthSteepness() + (connectiveBias - 0.5) * 4.0 + (riskTaking - 0.5) * 2.0;
        double breadthMidpoint  = defaults.breadthMidpoint() - (connectiveBias - 0.5) * 0.2;

        double openness           = (1.0 - withdrawal + 1.0 - intimacyAvoidance + 1.0 - suspiciousness) / 3.0;
        double lateralityMidpoint = defaults.lateralityMidpoint() - (openness - 0.5) * 0.3;

        double conscientiousness = (ruleFollowingNumeric + 1.0 - impulsivity) / 2.0;
        double depthMidpoint     = defaults.depthMidpoint() - (conscientiousness - 0.5) * 0.3;

        double convergenceMidpoint = defaults.convergenceMidpoint() - (conscientiousness - 0.5) * 0.2;

        double pathologicalThreshold = defaults.pathologicalThreshold()
                                       - (anxiousness - 0.5) * 0.6
                                       - (emotionalLability - 0.5) * 0.4;
        pathologicalThreshold = Math.max(0.5, pathologicalThreshold);

        var channelModulation = deriveChannelModulationDefaults(facets, axes);

        // Yerkes-Dodson inverted-U peak — personality-specific optimal activation. Calibrated empirically.
        double extremePeak = 0.85 - (anxiousness - 0.5) * 0.3 - (emotionalLability - 0.5) * 0.2;
        extremePeak = Math.max(0.5, Math.min(0.95, extremePeak));

        double depthPeak       = clampPeak(extremePeak - 0.05 + conscientiousness * 0.1);
        double breadthPeak     = clampPeak(extremePeak + openness * 0.05);
        double lateralityPeak  = clampPeak(extremePeak - 0.1 + openness * 0.08);
        double convergencePeak = clampPeak(extremePeak + conscientiousness * 0.08);

        // Easterbrook (1959) cue-utilization: extreme intensity narrows attention. Rates calibrated empirically.
        double depthDecay               = Math.max(0, (anxiousness - 0.3) * 2.0 - conscientiousness * 1.5);
        double breadthDecay             = 1.0 + (1.0 - openness) * 2.0;
        double lateralityDecay          = 1.5 + (1.0 - openness) * 1.5 + impulsivity * 1.0;
        double convergenceDecay         = Math.max(0, anxiousness * 1.5 - conscientiousness * 1.0);
        double depthAmplification       = conscientiousness * 1.0 - anxiousness * 0.5;
        double breadthAmplification     = -(1.0 - openness) * 1.5;
        double lateralityAmplification  = -(1.0 - openness) * 1.2;
        double convergenceAmplification = -(anxiousness * 2.0 - conscientiousness * 0.8);
        double amplificationThreshold = clampPeak(extremePeak - conscientiousness * 0.1);
        var extremeInteraction = new ExtremeInteractionConfig(depthDecay, breadthDecay,
                                                              lateralityDecay, convergenceDecay, depthAmplification, breadthAmplification,
                                                              lateralityAmplification, convergenceAmplification,
                                                              depthPeak, breadthPeak, lateralityPeak, convergencePeak,
                                                              amplificationThreshold);

        // Hockey (1997) compensatory control: fatigue depletes secondary-task resources. Rates calibrated empirically.
        double fatigueDepth    = Math.min(0.0, -0.08 * (1.0 - conscientiousness));
        double fatigueBreadth  = -(0.15 + (1.0 - openness) * 0.15);
        double fatigueLat      = -(0.1 + (1.0 - openness) * 0.1) + impulsivity * 0.15;
        double fatigueConv     = conscientiousness * 0.2 - anxiousness * 0.15;
        var fatigueEffects = new SearchFatigueEffects(fatigueDepth, fatigueBreadth, fatigueLat, fatigueConv);

        return new SearchBudgetConfig(
                Math.max(1.0, breadthSteepness),
                clamp01(breadthMidpoint),
                defaults.lateralitySteepness(),
                clamp01(lateralityMidpoint),
                defaults.lateralityThreatFloor(),
                defaults.depthSteepness(),
                clamp01(depthMidpoint),
                defaults.convergenceSteepness(),
                clamp01(convergenceMidpoint),
                pathologicalThreshold,
                channelModulation,
                extremePeak,
                extremeInteraction,
                fatigueEffects);
    }

    static CognitiveResourcePoolConfig derivePoolConfig(List<PersonalityFacet> facets, DispositionAxes axes) {
        if (facets == null || facets.isEmpty()) return null;
        double impulsivity = facetScore(facets, PersonalityFacet.IMPULSIVITY, 0.5);
        double ruleFollowingNumeric = axes != null
                ? RULE_FOLLOWING_NUMERIC.getOrDefault(lower(axes.ruleFollowing()), 0.5) : 0.5;
        double conscientiousness = (ruleFollowingNumeric + 1.0 - impulsivity) / 2.0;
        int    maxEffort         = (int) (6 + conscientiousness * 8);
        double recoveryRate      = 0.3 + conscientiousness * 0.4;
        return new CognitiveResourcePoolConfig(maxEffort, recoveryRate);
    }

    /** Derives personality-modulated channel modulation magnitudes. Coefficients calibrated empirically. */
    private static ChannelModulationConfig deriveChannelModulationDefaults(
            List<PersonalityFacet> facets, DispositionAxes axes) {
        double withdrawal = facetScore(facets, PersonalityFacet.WITHDRAWAL, 0.5);
        double intimacyAvoidance = facetScore(facets, PersonalityFacet.INTIMACY_AVOIDANCE, 0.5);
        double suspiciousness = facetScore(facets, PersonalityFacet.SUSPICIOUSNESS, 0.5);
        double impulsivity = facetScore(facets, PersonalityFacet.IMPULSIVITY, 0.5);
        double anxiousness = facetScore(facets, PersonalityFacet.ANXIOUSNESS, 0.5);

        double ruleFollowingNumeric = axes != null
                ? RULE_FOLLOWING_NUMERIC.getOrDefault(lower(axes.ruleFollowing()), 0.5) : 0.5;

        double openness = (1.0 - withdrawal + 1.0 - intimacyAvoidance + 1.0 - suspiciousness) / 3.0;
        double conscientiousness = (ruleFollowingNumeric + 1.0 - impulsivity) / 2.0;

        var d = ChannelModulationConfig.defaults();
        return new ChannelModulationConfig(
                d.specificDepthBoost() + (conscientiousness - 0.5) * 0.08,
                d.specificBreadthPenalty() + (conscientiousness - 0.5) * 0.06,
                d.diversiveBreadthBoost() + (openness - 0.5) * 0.10,
                d.diversiveLateralityBoost() + (openness - 0.5) * 0.08,
                d.diversiveConvergencePenalty() + (openness - 0.5) * 0.06,
                d.deprivationConvergenceBoost() + (anxiousness - 0.5) * 0.08,
                d.lowComprehensionLateralityBoost() + (openness - 0.5) * 0.08,
                d.highComprehensionConvergenceBoost() + (conscientiousness - 0.5) * 0.08);
    }

    private static double facetScore(List<PersonalityFacet> facets, String name, double defaultValue) {
        return facets.stream().filter(f -> name.equals(f.facet())).findFirst()
                .map(PersonalityFacet::score).orElse(defaultValue);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampPeak(double value) {
        return Math.max(0.5, Math.min(0.95, value));
    }


    private static boolean isHigh(List<PersonalityFacet> facets, String name) {
        return facets.stream().filter(f -> name.equals(f.facet())).findFirst()
                .map(PersonalityFacet::isHigh).orElse(false);
    }

    private static boolean isLow(List<PersonalityFacet> facets, String name) {
        return facets.stream().filter(f -> name.equals(f.facet())).findFirst()
                .map(PersonalityFacet::isLow).orElse(true);
    }


    private static String lower(String s) {
        return s != null ? s.toLowerCase() : "";
    }
}
