package io.casehub.neocortex.cognitive.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests PID-5 facet modulations across all CDE derivation methods.
 * Category A: facets modulate axes-derived values.
 * Category B Tier 1: peer-reviewed modulations (Heydarloo 2024, Blair 2005, Bar-Haim 2007).
 * Category B Tier 2: FFM bridge extensions (McCrae & Costa 1989, Gore & Widiger 2013).
 */
class CognitiveDerivationEnginePid5FacetTest {

    private static final DispositionAxes DEFAULT_AXES =
            new DispositionAxes("collaborative", "principled", "measured", "semi-autonomous", "compromising");

    private static final List<WeightedTerm> BALANCED_PROFILE = List.of(
            new WeightedTerm("ni", 0.3), new WeightedTerm("te", 0.25),
            new WeightedTerm("fi", 0.25), new WeightedTerm("se", 0.2));

    private static List<PersonalityFacet> facetsOf(String name, double score) {
        return List.of(new PersonalityFacet(name, score, "test"));
    }

    private static DescriptorView descriptorWithFacets(List<PersonalityFacet> facets) {
        return new DescriptorView("test-agent", DEFAULT_AXES, BALANCED_PROFILE,
                List.of("investigate"), null, facets);
    }

    // --- Category A: deriveMoodBaseline ---

    @Test
    void moodBaseline_riskTakingModulatesPleasure() {
        var bold = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.9));
        var cautious = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.1));
        assertThat(bold.pleasure()).isGreaterThan(cautious.pleasure());
    }

    @Test
    void moodBaseline_emotionalLabilityModulatesArousal() {
        var labile = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.9));
        var stable = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.1));
        assertThat(labile.arousal()).isGreaterThan(stable.arousal());
    }

    @Test
    void moodBaseline_withdrawalModulatesDominance() {
        var withdrawn = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.WITHDRAWAL, 0.9));
        var engaged = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.WITHDRAWAL, 0.1));
        assertThat(withdrawn.dominance()).isLessThan(engaged.dominance());
    }

    @Test
    void moodBaseline_emptyFacetsMatchesAxesOnly() {
        var withFacets = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, List.of());
        var withoutFacets = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, null);
        assertThat(withFacets.pleasure()).isEqualTo(withoutFacets.pleasure());
    }

    // --- Category A: deriveCuriosity ---

    @Test
    void curiosity_withdrawalModulatesStructural() {
        var withdrawn = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.WITHDRAWAL, 0.9));
        var engaged = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.WITHDRAWAL, 0.1));
        assertThat(engaged.categoryWeight("STRUCTURAL")).isGreaterThan(withdrawn.categoryWeight("STRUCTURAL"));
    }

    @Test
    void curiosity_impulsivityModulatesQuality() {
        var impulsive = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.IMPULSIVITY, 0.9));
        var controlled = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.IMPULSIVITY, 0.1));
        assertThat(controlled.categoryWeight("QUALITY")).isGreaterThan(impulsive.categoryWeight("QUALITY"));
    }

    // --- Category A: deriveCbrStrategy ---

    @Test
    void cbrStrategy_impulsivityLowersMinSimilarity() {
        var impulsive = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.IMPULSIVITY, 0.9));
        var controlled = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.IMPULSIVITY, 0.1));
        assertThat(impulsive.minSimilarity()).isLessThan(controlled.minSimilarity());
    }

    @Test
    void cbrStrategy_riskTakingIncreasesDecayDays() {
        var bold = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.9));
        var cautious = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.1));
        assertThat(bold.temporalDecayDays()).isGreaterThan(cautious.temporalDecayDays());
    }

    // --- Category A: deriveSocialCognition ---

    @Test
    void socialCognition_suspiciousnessReducesTrustRate() {
        var suspicious = CognitiveDerivationEngine.deriveSocialCognition(DEFAULT_AXES, facetsOf(PersonalityFacet.SUSPICIOUSNESS, 0.9));
        var trusting = CognitiveDerivationEngine.deriveSocialCognition(DEFAULT_AXES, facetsOf(PersonalityFacet.SUSPICIOUSNESS, 0.1));
        assertThat(suspicious.trustFormationRate()).isLessThan(trusting.trustFormationRate());
    }

    // --- Category A: deriveHabituationConfig ---

    @Test
    void habituation_impulsivityIncreasesHabituationRate() {
        var impulsive = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.IMPULSIVITY, 0.9));
        var controlled = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.IMPULSIVITY, 0.1));
        assertThat(impulsive.habituationRate()).isGreaterThan(controlled.habituationRate());
    }

    @Test
    void habituation_riskTakingIncreasesNoveltyThreshold() {
        var bold = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RISK_TAKING, 0.9));
        var cautious = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RISK_TAKING, 0.1));
        assertThat(bold.noveltyThreshold()).isGreaterThan(cautious.noveltyThreshold());
    }

    // --- Category A: deriveRegulationDefaults ---

    @Test
    void regulation_emotionalLabilityReducesReappraisal() {
        var labile = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.9));
        var stable = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.1));
        assertThat(labile.reappraisalCapacity()).isLessThan(stable.reappraisalCapacity());
    }

    @Test
    void regulation_callousnessReducesSocialRegulation() {
        var callous = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.CALLOUSNESS, 0.9));
        var empathic = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.CALLOUSNESS, 0.1));
        assertThat(callous.socialRegulationTendency()).isLessThan(empathic.socialRegulationTendency());
    }

    // --- Category B Tier 1: deriveCapacity ---

    @Test
    void capacity_impulsivityDegradesWorkingMemory() {
        var impulsive = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.IMPULSIVITY, 0.9));
        var controlled = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.IMPULSIVITY, 0.1));
        assertThat(impulsive.workingMemorySpan()).isLessThan(controlled.workingMemorySpan());
    }

    // --- Category B Tier 1: deriveExtractionBias ---

    @Test
    void extractionBias_callousnessReducesRelationshipBias() {
        var callous = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.CALLOUSNESS, 0.9));
        var empathic = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.CALLOUSNESS, 0.1));
        assertThat(callous.relationshipBias()).isLessThan(empathic.relationshipBias());
    }

    // --- Category B Tier 1: deriveAppraisalWeights ---

    @Test
    void appraisalWeights_anxiousnessAmplifiesFear() {
        var anxious = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.ANXIOUSNESS, 0.9));
        var calm = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.ANXIOUSNESS, 0.1));
        assertThat(anxious.fearOnsetThreshold()).isLessThan(calm.fearOnsetThreshold());
    }

    // --- Category B Tier 2: deriveExtractionBias ---

    @Test
    void extractionBias_emotionalLabilityAmplifiesAffect() {
        var labile = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.9));
        var stable = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.EMOTIONAL_LABILITY, 0.1));
        assertThat(labile.affectSensitivity()).isGreaterThan(stable.affectSensitivity());
    }

    // --- Category B Tier 2: deriveAppraisalWeights ---

    @Test
    void appraisalWeights_riskTakingReducesFear() {
        var bold = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.9));
        var cautious = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.RISK_TAKING, 0.1));
        assertThat(bold.fearOnsetThreshold()).isLessThan(cautious.fearOnsetThreshold());
    }

    @Test
    void appraisalWeights_impulsivityAmplifiesUrgency() {
        var impulsive = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.IMPULSIVITY, 0.9));
        var deliberate = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.IMPULSIVITY, 0.1));
        assertThat(impulsive.urgencyWeight()).isGreaterThan(deliberate.urgencyWeight());
    }

    // --- Category B Tier 2: deriveCapacity ---

    @Test
    void capacity_anxiousnessNarrowsAssociativeReach() {
        var anxious = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.ANXIOUSNESS, 0.9));
        var calm = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.ANXIOUSNESS, 0.1));
        assertThat(anxious.associativeReach()).isLessThan(calm.associativeReach());
    }

    // --- Category B Tier 2: deriveGraphStructure ---

    @Test
    void graphStructure_withdrawalReducesConnectiveBias() {
        var withdrawn = CognitiveDerivationEngine.deriveGraphStructure(BALANCED_PROFILE, facetsOf(PersonalityFacet.WITHDRAWAL, 0.9));
        var engaged = CognitiveDerivationEngine.deriveGraphStructure(BALANCED_PROFILE, facetsOf(PersonalityFacet.WITHDRAWAL, 0.1));
        assertThat(withdrawn.connectiveBias()).isLessThan(engaged.connectiveBias());
    }

    // --- Extended PID-5 facets (Tier 1) ---

    @Test
    void moodBaseline_anhedoniaReducesPleasure() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.1));
        assertThat(high.pleasure()).isLessThan(low.pleasure());
    }

    @Test
    void moodBaseline_anhedoniaReducesArousal() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.1));
        assertThat(high.arousal()).isLessThan(low.arousal());
    }

    @Test
    void moodBaseline_depressivityReducesPleasure() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1));
        assertThat(high.pleasure()).isLessThan(low.pleasure());
    }

    @Test
    void moodBaseline_depressivityReducesDominance() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1));
        assertThat(high.dominance()).isLessThan(low.dominance());
    }

    @Test
    void moodBaseline_separationInsecurityIncreasesArousal() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.1));
        assertThat(high.arousal()).isGreaterThan(low.arousal());
    }

    @Test
    void moodBaseline_attentionSeekingIncreasesArousal() {
        var high = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ATTENTION_SEEKING, 0.9));
        var low = CognitiveDerivationEngine.deriveMoodBaseline(DEFAULT_AXES, facetsOf(PersonalityFacet.ATTENTION_SEEKING, 0.1));
        assertThat(high.arousal()).isGreaterThan(low.arousal());
    }

    @Test
    void recurringThought_depressivityLowersRumination() {
        var high = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1), DEFAULT_AXES);
        assertThat(high.ruminationThreshold()).isLessThan(low.ruminationThreshold());
    }

    @Test
    void recurringThought_perseverationLowersFixation() {
        var high = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.PERSEVERATION, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.PERSEVERATION, 0.1), DEFAULT_AXES);
        assertThat(high.fixationThreshold()).isLessThan(low.fixationThreshold());
    }

    @Test
    void recurringThought_rigidPerfectionismLowersFixation() {
        var high = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1), DEFAULT_AXES);
        assertThat(high.fixationThreshold()).isLessThan(low.fixationThreshold());
    }

    @Test
    void recurringThought_separationInsecurityLowersFixation() {
        var high = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.1), DEFAULT_AXES);
        assertThat(high.fixationThreshold()).isLessThan(low.fixationThreshold());
    }

    @Test
    void capacity_depressivityReducesWorkingMemory() {
        var high = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1));
        assertThat(high.workingMemorySpan()).isLessThan(low.workingMemorySpan());
    }

    @Test
    void capacity_distractibilityReducesWorkingMemory() {
        var high = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.9));
        var low = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.1));
        assertThat(high.workingMemorySpan()).isLessThan(low.workingMemorySpan());
    }

    @Test
    void capacity_perseverationReducesAssociativeReach() {
        var high = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.PERSEVERATION, 0.9));
        var low = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.PERSEVERATION, 0.1));
        assertThat(high.associativeReach()).isLessThan(low.associativeReach());
    }

    @Test
    void searchBudget_anhedoniaReducesDepth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ANHEDONIA, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ANHEDONIA, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.depthMidpoint()).isLessThan(low.depthMidpoint());
    }

    @Test
    void searchBudget_perseverationReducesLaterality() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.PERSEVERATION, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.PERSEVERATION, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.lateralityMidpoint()).isLessThan(low.lateralityMidpoint());
    }

    @Test
    void searchBudget_perseverationIncreasesConvergence() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.PERSEVERATION, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.PERSEVERATION, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.convergenceMidpoint()).isGreaterThan(low.convergenceMidpoint());
    }

    @Test
    void searchBudget_distractibilityReducesDepth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.depthMidpoint()).isLessThan(low.depthMidpoint());
    }

    @Test
    void searchBudget_distractibilityIncreasesBreadth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.breadthSteepness()).isGreaterThan(low.breadthSteepness());
    }

    @Test
    void pool_rigidPerfectionismIncreasesMaxEffort() {
        var high = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1), DEFAULT_AXES);
        assertThat(high.maxEffort()).isGreaterThan(low.maxEffort());
    }

    @Test
    void pool_anhedoniaReducesMaxEffort() {
        var high = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.ANHEDONIA, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.ANHEDONIA, 0.1), DEFAULT_AXES);
        assertThat(high.maxEffort()).isLessThan(low.maxEffort());
    }

    // --- Extended PID-5 facets (Tier 2) ---

    @Test
    void extractionBias_restrictedAffectivityReducesAffectSensitivity() {
        var high = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.RESTRICTED_AFFECTIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.RESTRICTED_AFFECTIVITY, 0.1));
        assertThat(high.affectSensitivity()).isLessThan(low.affectSensitivity());
    }

    @Test
    void extractionBias_separationInsecurityIncreasesRelationshipBias() {
        var high = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.9));
        var low = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.1));
        assertThat(high.relationshipBias()).isGreaterThan(low.relationshipBias());
    }

    @Test
    void pool_irresponsibilityReducesMaxEffort() {
        var high = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.1), DEFAULT_AXES);
        assertThat(high.maxEffort()).isLessThan(low.maxEffort());
    }

    @Test
    void searchBudget_eccentricityIncreasesLaterality() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ECCENTRICITY, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ECCENTRICITY, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.lateralityMidpoint()).isGreaterThan(low.lateralityMidpoint());
    }

    @Test
    void searchBudget_cogPerceptualDysregulationReducesDepth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.depthMidpoint()).isLessThan(low.depthMidpoint());
    }

    @Test
    void capacity_cogPerceptualDysregulationReducesWorkingMemory() {
        var high = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.9));
        var low = CognitiveDerivationEngine.deriveCapacity(BALANCED_PROFILE, facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.1));
        assertThat(high.workingMemorySpan()).isLessThan(low.workingMemorySpan());
    }

    @Test
    void regulation_depressivityReducesReappraisal() {
        var axes = new DispositionAxes("independent", "flexible", "measured", "autonomous", "confronting");
        var high = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1));
        assertThat(high.reappraisalCapacity()).isLessThan(low.reappraisalCapacity());
    }

    @Test
    void regulation_restrictedAffectivityReducesReappraisal() {
        var high = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.RESTRICTED_AFFECTIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveRegulationDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.RESTRICTED_AFFECTIVITY, 0.1));
        assertThat(high.reappraisalCapacity()).isLessThan(low.reappraisalCapacity());
    }

    // --- Missing directional tests (Refs #1242) ---

    @Test
    void curiosity_anhedoniaReducesCategoryWeights() {
        var high = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.ANHEDONIA, 0.9));
        var low = CognitiveDerivationEngine.deriveCuriosity(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.ANHEDONIA, 0.1));
        assertThat(high.categoryWeight("STRUCTURAL")).isLessThan(low.categoryWeight("STRUCTURAL"));
        assertThat(high.categoryWeight("QUALITY")).isLessThan(low.categoryWeight("QUALITY"));
        assertThat(high.categoryWeight("CENTRALITY")).isLessThan(low.categoryWeight("CENTRALITY"));
    }

    @Test
    void cbrStrategy_anhedoniaIncreasesTemporalDecay() {
        var high = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.9));
        var low = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.ANHEDONIA, 0.1));
        assertThat(high.temporalDecayDays()).isGreaterThanOrEqualTo(low.temporalDecayDays());
    }

    @Test
    void appraisalWeights_depressivityReducesFearThreshold() {
        var high = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9));
        var low = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1));
        assertThat(high.fearOnsetThreshold()).isLessThan(low.fearOnsetThreshold());
    }

    @Test
    void pool_depressivityReducesMaxEffort() {
        var high = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.DEPRESSIVITY, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.DEPRESSIVITY, 0.1), DEFAULT_AXES);
        assertThat(high.maxEffort()).isLessThan(low.maxEffort());
    }

    @Test
    void habituation_perseverationReducesHabituationRate() {
        var high = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.PERSEVERATION, 0.9));
        var low = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.PERSEVERATION, 0.1));
        assertThat(high.habituationRate()).isLessThan(low.habituationRate());
    }

    @Test
    void habituation_perseverationIncreasesRepetitionTolerance() {
        var high = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.PERSEVERATION, 0.9));
        var low = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.PERSEVERATION, 0.1));
        assertThat(high.repetitionTolerance()).isGreaterThan(low.repetitionTolerance());
    }

    @Test
    void searchBudget_rigidPerfectionismIncreasesConvergence() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.convergenceMidpoint()).isGreaterThan(low.convergenceMidpoint());
    }

    @Test
    void searchBudget_rigidPerfectionismReducesLaterality() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.lateralityMidpoint()).isLessThan(low.lateralityMidpoint());
    }

    @Test
    void habituation_rigidPerfectionismReducesHabituationRate() {
        var high = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9));
        var low = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1));
        assertThat(high.habituationRate()).isLessThan(low.habituationRate());
    }

    @Test
    void habituation_rigidPerfectionismIncreasesRepetitionTolerance() {
        var high = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9));
        var low = CognitiveDerivationEngine.deriveHabituationConfig(DEFAULT_AXES, List.of(), facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1));
        assertThat(high.repetitionTolerance()).isGreaterThan(low.repetitionTolerance());
    }

    @Test
    void regulation_rigidPerfectionismReducesReappraisal() {
        var axes = new DispositionAxes("independent", "flexible", "measured", "autonomous", "confronting");
        var high = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.9));
        var low = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.RIGID_PERFECTIONISM, 0.1));
        assertThat(high.reappraisalCapacity()).isLessThan(low.reappraisalCapacity());
    }

    @Test
    void pool_distractibilityReducesMaxEffort() {
        var high = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.9), DEFAULT_AXES);
        var low = CognitiveDerivationEngine.derivePoolConfig(facetsOf(PersonalityFacet.DISTRACTIBILITY, 0.1), DEFAULT_AXES);
        assertThat(high.maxEffort()).isLessThan(low.maxEffort());
    }

    @Test
    void appraisalWeights_separationInsecurityIncreasesRelevance() {
        var high = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.9));
        var low = CognitiveDerivationEngine.deriveAppraisalWeights(BALANCED_PROFILE, DEFAULT_AXES, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.1));
        assertThat(high.relationshipWeight()).isGreaterThan(low.relationshipWeight());
    }

    @Test
    void regulation_separationInsecurityReducesReappraisal() {
        var axes = new DispositionAxes("independent", "flexible", "measured", "autonomous", "confronting");
        var high = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.9));
        var low = CognitiveDerivationEngine.deriveRegulationDefaults(axes, facetsOf(PersonalityFacet.SEPARATION_INSECURITY, 0.1));
        assertThat(high.reappraisalCapacity()).isLessThan(low.reappraisalCapacity());
    }

    @Test
    void coping_irresponsibilityIncreasesDisengagement() {
        var high = CognitiveDerivationEngine.deriveCopingDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.9));
        var low = CognitiveDerivationEngine.deriveCopingDefaults(DEFAULT_AXES, facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.1));
        assertThat(high.disengagementTendency()).isGreaterThan(low.disengagementTendency());
    }

    @Test
    void cbrStrategy_irresponsibilityIncreasesTemporalDecay() {
        var high = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.9));
        var low = CognitiveDerivationEngine.deriveCbrStrategy(DEFAULT_AXES, facetsOf(PersonalityFacet.IRRESPONSIBILITY, 0.1));
        assertThat(high.temporalDecayDays()).isGreaterThanOrEqualTo(low.temporalDecayDays());
    }

    @Test
    void extractionBias_cogPerceptualDysregulationReducesAffectSensitivity() {
        var high = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.9));
        var low = CognitiveDerivationEngine.deriveExtractionBias(BALANCED_PROFILE, facetsOf(PersonalityFacet.COGNITIVE_PERCEPTUAL_DYSREGULATION, 0.1));
        assertThat(high.affectSensitivity()).isLessThan(low.affectSensitivity());
    }

    @Test
    void searchBudget_eccentricityIncreasesBreadth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ECCENTRICITY, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.ECCENTRICITY, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.breadthSteepness()).isGreaterThan(low.breadthSteepness());
    }

    @Test
    void searchBudget_deceitfulnessIncreasesDepth() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DECEITFULNESS, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DECEITFULNESS, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.depthMidpoint()).isGreaterThan(low.depthMidpoint());
    }

    // --- Backfill: Deceitfulness, Attention Seeking ---

    @Test
    void searchBudget_deceitfulnessIncreasesLaterality() {
        var high = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DECEITFULNESS, 0.9), DEFAULT_AXES, 0.5);
        var low = CognitiveDerivationEngine.deriveSearchBudgetDefaults(facetsOf(PersonalityFacet.DECEITFULNESS, 0.1), DEFAULT_AXES, 0.5);
        assertThat(high.lateralityMidpoint()).isGreaterThan(low.lateralityMidpoint());
    }

    // --- Multicollinearity guard ---

    @Test
    void multicollinearity_stackedNegativeAffectStaysBounded() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.DEPRESSIVITY, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.PERSEVERATION, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.SEPARATION_INSECURITY, 0.9, "test"));
        var config = CognitiveDerivationEngine.deriveRecurringThoughtConfig(facets, DEFAULT_AXES);
        assertThat(config.fixationThreshold()).as("stacked NA should not drive fixation below 0.1")
                .isGreaterThanOrEqualTo(0.1);
        assertThat(config.ruminationThreshold()).as("stacked NA should keep rumination positive")
                .isGreaterThan(0.0);
    }

    // --- Integration: full pipeline with facets ---


    @Test
    void expressionStyles_absentFacetsMatchNeutralScores() {
        // One irrelevant facet so the list is non-empty (deriveExpressionStyles
        // early-returns for empty lists). Expression-relevant facets are absent.
        var withAbsent = CognitiveDerivationEngine.deriveExpressionStyles(
                List.of(new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.5, "test")));

        // Explicit 0.5 for all expression-relevant facets — should produce identical result
        var withNeutral = CognitiveDerivationEngine.deriveExpressionStyles(List.of(
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.GRANDIOSITY, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test")));

        assertThat(withAbsent).isEqualTo(withNeutral);
    }

    @Test
    void fullPipeline_facetsModulateDerivedDefaults() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.80, "test"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.70, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.20, "test"));
        var descriptor = descriptorWithFacets(facets);

        var defaults = CognitiveDerivationEngine.derive(descriptor);

        assertThat(defaults.capacityConfig()).isNotNull();
        assertThat(defaults.extractionBias()).isNotNull();
        assertThat(defaults.appraisalWeights()).isNotNull();
        assertThat(defaults.moodBaseline()).isNotNull();
        assertThat(defaults.regulationDefaults()).isNotNull();
    }

    @Test
    void fullPipeline_noFacetsProducesSameAsAxesOnly() {
        var withFacets = descriptorWithFacets(List.of());
        var withoutFacets = new DescriptorView("test-agent", DEFAULT_AXES, BALANCED_PROFILE,
                List.of("investigate"), null);

        var dWith = CognitiveDerivationEngine.derive(withFacets);
        var dWithout = CognitiveDerivationEngine.derive(withoutFacets);

        assertThat(dWith.moodBaseline().pleasure()).isEqualTo(dWithout.moodBaseline().pleasure());
        assertThat(dWith.moodBaseline().arousal()).isEqualTo(dWithout.moodBaseline().arousal());
    }
}
