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
            new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");

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
        assertThat(anxious.fearOnsetThreshold()).isGreaterThan(calm.fearOnsetThreshold());
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

    // --- Integration: full pipeline with facets ---

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
