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

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.mindmap.AppraisalWeights;
import io.casehub.neocortex.mindmap.CuriosityConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CognitiveDerivationEngineTest {

    private static final MemoryDomain EXPERIENCE = new MemoryDomain("experience");
    private static final MemoryDomain REFLECTION = new MemoryDomain("reflection");
    private static final MemoryDomain RELATIONSHIP = new MemoryDomain("relationship");
    private static final MemoryDomain ENGAGEMENT = new MemoryDomain("engagement");
    private static final MemoryDomain MOOD = new MemoryDomain("mood");

    @Test
    void niDominant_boostsReflection() {
        var descriptor = DescriptorView.of("agent-1", null,
            List.of(new WeightedTerm("ni", 0.6), new WeightedTerm("te", 0.4)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.personality()).isNotNull();
        assertThat(result.personality().getWeight(REFLECTION)).isGreaterThan(1.0);
        assertThat(result.personality().getWeight(EXPERIENCE)).isLessThan(1.2);
    }

    @Test
    void feDominant_boostsRelationshipAndEngagement() {
        var descriptor = DescriptorView.of("agent-2", null,
            List.of(new WeightedTerm("fe", 0.5), new WeightedTerm("si", 0.5)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.personality()).isNotNull();
        assertThat(result.personality().getWeight(RELATIONSHIP)).isGreaterThan(1.0);
        assertThat(result.personality().getWeight(ENGAGEMENT)).isGreaterThan(1.0);
    }

    @Test
    void seDominant_boostsExperience() {
        var descriptor = DescriptorView.of("agent-3", null,
            List.of(new WeightedTerm("se", 0.7), new WeightedTerm("fi", 0.3)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.personality()).isNotNull();
        assertThat(result.personality().getWeight(EXPERIENCE)).isGreaterThan(1.2);
    }

    @Test
    void emptyProfile_returnsNeutralWeights() {
        var descriptor = DescriptorView.of("agent-4", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.personality()).isNull();
    }

    @Test
    void conservativeRisk_lowerPleasureBaseline() {
        var axes = new DispositionAxes("independent", "strict", "conservative", "moderate", "analytical");
        var descriptor = DescriptorView.of("agent-5", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.moodBaseline()).isNotNull();
        assertThat(result.moodBaseline().pleasure()).isLessThan(0.0);
    }

    @Test
    void boldRisk_higherPleasureBaseline() {
        var axes = new DispositionAxes("cooperative", "flexible", "bold", "high", "cooperative");
        var descriptor = DescriptorView.of("agent-6", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.moodBaseline()).isNotNull();
        assertThat(result.moodBaseline().pleasure()).isGreaterThan(0.0);
    }

    @Test
    void highAutonomy_higherDominanceBaseline() {
        var axes = new DispositionAxes("independent", "moderate", "calculated", "high", "analytical");
        var descriptor = DescriptorView.of("agent-7", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.moodBaseline()).isNotNull();
        assertThat(result.moodBaseline().dominance()).isGreaterThan(0.2);
    }

    @Test
    void nullDisposition_noMoodBaseline() {
        var descriptor = DescriptorView.of("agent-8", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.moodBaseline()).isNull();
    }

    @Test
    void agentId_preserved() {
        var descriptor = DescriptorView.of("my-agent", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.agentId()).isEqualTo("my-agent");
    }

    @Test
    void fullDescriptor_derivesAllSections() {
        var axes = new DispositionAxes("independent", "moderate", "calculated", "high", "analytical");
        var descriptor = DescriptorView.of("analyst-01", axes,
            List.of(
                new WeightedTerm("ni", 0.35),
                new WeightedTerm("te", 0.30),
                new WeightedTerm("fi", 0.20),
                new WeightedTerm("se", 0.15)),
            List.of("identify strategic patterns"));

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.agentId()).isEqualTo("analyst-01");
        assertThat(result.personality()).isNotNull();
        assertThat(result.moodBaseline()).isNotNull();
        assertThat(result.curiosity()).isNotNull();
        assertThat(result.cbrStrategy()).isNotNull();
        assertThat(result.socialCognition()).isNotNull();
        assertThat(result.graphStructure()).isNotNull();
        assertThat(result.extractionBias()).isNotNull();
        // temporalFocus is null — "identify strategic patterns" has no subgraph-mapping keywords
        assertThat(result.temporalFocus()).isNull();
        assertThat(result.personality().getWeight(REFLECTION)).isGreaterThan(1.0);
    }

    @Test
    void highAutonomy_boostsStructuralCuriosity() {
        var axes       = new DispositionAxes("independent", "moderate", "calculated", "high", "analytical");
        var descriptor = DescriptorView.of("agent-c1", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.curiosity()).isNotNull();
        assertThat(result.curiosity().categoryWeight("STRUCTURAL")).isCloseTo(1.3, within(0.01));
        assertThat(result.curiosity().categoryWeight("QUALITY")).isCloseTo(1.0, within(0.01));
    }

    @Test
    void strictRuleFollowing_boostsQualityCuriosity() {
        var axes       = new DispositionAxes("cooperative", "strict", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-c2", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.curiosity()).isNotNull();
        assertThat(result.curiosity().categoryWeight("QUALITY")).isCloseTo(1.3, within(0.01));
    }

    @Test
    void cooperativeSocial_boostsCentralityCuriosity() {
        var axes       = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-c3", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.curiosity()).isNotNull();
        assertThat(result.curiosity().categoryWeight("CENTRALITY")).isCloseTo(1.2, within(0.01));
    }

    @Test
    void strategicGoal_boostsCentralityAndStructural() {
        var axes = new DispositionAxes("independent", "moderate", "calculated", "high", "analytical");
        var descriptor = DescriptorView.of("agent-c4", axes, List.of(),
                                            List.of("identify strategic patterns"));

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.curiosity()).isNotNull();
        assertThat(result.curiosity().categoryWeight("STRUCTURAL")).isCloseTo(1.5, within(0.01));
        assertThat(result.curiosity().categoryWeight("CENTRALITY")).isCloseTo(1.0, within(0.01));
    }

    @Test
    void nullDisposition_noCuriosityConfig() {
        var descriptor = DescriptorView.of("agent-c5", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.curiosity()).isNull();
    }

    @Test
    void deriveAndMerge_explicitCuriosityWins() {
        var axes              = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicitCuriosity = CuriosityConfig.defaults().withCategoryWeights(Map.of("QUALITY", 2.0));
        var explicit = CognitiveDefaults.empty("agent-c6")
                .withTenantId("t1")
                .withCuriosity(explicitCuriosity)
                .withDescriptor(DescriptorView.of("agent-c6", axes, List.of(), List.of()));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.curiosity().categoryWeight("QUALITY")).isCloseTo(2.0, within(0.01));
    }

    @Test
    void deriveAndMerge_fallsThroughToDerivedCuriosity() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicit = CognitiveDefaults.empty("agent-c7")
                .withTenantId("t1")
                .withDescriptor(DescriptorView.of("agent-c7", axes, List.of(), List.of()));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.curiosity()).isNotNull();
        assertThat(result.curiosity().categoryWeight("STRUCTURAL")).isCloseTo(1.3, within(0.01));
        assertThat(result.curiosity().categoryWeight("QUALITY")).isCloseTo(1.3, within(0.01));
    }

    @Test
    void careerGoal_boostsProjectProximity() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-t1", axes, List.of(),
                                            List.of("advance career"));

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.temporalFocus()).isNotNull();
        assertThat(result.temporalFocus().subgraphProximityWeight("PROJECT")).isGreaterThan(1.0);
    }

    @Test
    void familyGoal_boostsPersonProximity() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-t2", axes, List.of(),
                                            List.of("support family"));

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.temporalFocus()).isNotNull();
        assertThat(result.temporalFocus().subgraphProximityWeight("PERSON")).isGreaterThan(1.0);
    }

    @Test
    void noGoals_noTemporalFocus() {
        var axes       = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-t3", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.temporalFocus()).isNull();
    }

    @Test
    void deriveAndMerge_explicitTemporalFocusWins() {
        var axes          = new DispositionAxes("cooperative", "moderate", "bold", "moderate", "cooperative");
        var explicitFocus = TemporalFocusConfig.defaults().withSubgraphProximityWeights(Map.of("CONCEPT", 2.0));
        var explicit = CognitiveDefaults.empty("agent-t4")
                .withTenantId("t1")
                .withTemporalFocus(explicitFocus)
                .withDescriptor(DescriptorView.of("agent-t4", axes, List.of(), List.of("advance career")));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.temporalFocus().subgraphProximityWeight("CONCEPT")).isCloseTo(2.0, within(0.01));
    }

    @Test
    void strictRuleFollowing_stricterCbrStrategy() {
        var axes = new DispositionAxes("cooperative", "strict", "conservative", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-s1", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.cbrStrategy()).isNotNull();
        assertThat(result.cbrStrategy().minSimilarity()).isCloseTo(0.65, within(0.01));
        assertThat(result.cbrStrategy().retrievalMode()).isEqualTo(RetrievalMode.FEATURE_ONLY);
    }

    @Test
    void flexibleRuleFollowing_broaderCbrStrategy() {
        var axes = new DispositionAxes("cooperative", "flexible", "bold", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-s2", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.cbrStrategy()).isNotNull();
        assertThat(result.cbrStrategy().minSimilarity()).isCloseTo(0.35, within(0.01));
        assertThat(result.cbrStrategy().temporalDecayDays()).isEqualTo(180);
        assertThat(result.cbrStrategy().retrievalMode()).isEqualTo(RetrievalMode.SEMANTIC_ONLY);
    }

    @Test
    void nullDisposition_noCbrStrategy() {
        var descriptor = DescriptorView.of("agent-s3", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.cbrStrategy()).isNull();
    }

    @Test
    void cooperativeSocial_fasterTrustFormation() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-sc1", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.socialCognition()).isNotNull();
        assertThat(result.socialCognition().trustFormationRate()).isCloseTo(0.7, within(0.01));
        assertThat(result.socialCognition().conflictInterpretation()).isEqualTo(ConflictInterpretation.REPAIR);
    }

    @Test
    void competitiveConflict_informationInterpretation() {
        var axes = new DispositionAxes("independent", "strict", "bold", "high", "competitive");
        var descriptor = DescriptorView.of("agent-sc2", axes, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.socialCognition()).isNotNull();
        assertThat(result.socialCognition().trustFormationRate()).isCloseTo(0.3, within(0.01));
        assertThat(result.socialCognition().conflictInterpretation()).isEqualTo(ConflictInterpretation.INFORMATION);
    }

    @Test
    void nullDisposition_noSocialCognition() {
        var descriptor = DescriptorView.of("agent-sc3", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.socialCognition()).isNull();
    }

    @Test
    void niDominant_connectiveGraphStructure() {
        var descriptor = DescriptorView.of("agent-g1", null,
            List.of(new WeightedTerm("ni", 0.5), new WeightedTerm("fe", 0.3), new WeightedTerm("te", 0.2)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure()).isNotNull();
        assertThat(result.graphStructure().inferenceStyle()).isEqualTo(GraphStructureDefaults.InferenceStyle.CONNECTIVE);
        assertThat(result.graphStructure().connectiveBias()).isGreaterThan(0.6);
    }

    @Test
    void teDominant_categoricalGraphStructure() {
        var descriptor = DescriptorView.of("agent-g2", null,
            List.of(new WeightedTerm("te", 0.5), new WeightedTerm("si", 0.3), new WeightedTerm("fi", 0.2)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure()).isNotNull();
        assertThat(result.graphStructure().inferenceStyle()).isEqualTo(GraphStructureDefaults.InferenceStyle.CATEGORICAL);
        assertThat(result.graphStructure().connectiveBias()).isLessThan(0.4);
    }

    @Test
    void emptyProfile_noGraphStructure() {
        var descriptor = DescriptorView.of("agent-g3", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure()).isNull();
    }

    @Test
    void niTeDominant_highRelationshipBias() {
        var descriptor = DescriptorView.of("agent-e1", null,
            List.of(new WeightedTerm("ni", 0.4), new WeightedTerm("te", 0.4), new WeightedTerm("fe", 0.2)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.extractionBias()).isNotNull();
        assertThat(result.extractionBias().relationshipBias()).isGreaterThan(1.1);
    }

    @Test
    void feDominant_highAffectSensitivity() {
        var descriptor = DescriptorView.of("agent-e2", null,
            List.of(new WeightedTerm("fe", 0.5), new WeightedTerm("fi", 0.3), new WeightedTerm("se", 0.2)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.extractionBias()).isNotNull();
        assertThat(result.extractionBias().affectSensitivity()).isGreaterThan(1.2);
    }

    @Test
    void emptyProfile_noExtractionBias() {
        var descriptor = DescriptorView.of("agent-e3", null, List.of(), List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.extractionBias()).isNull();
    }

    @Test
    void graphStructureBoundary_exactlyBalanced() {
        var descriptor = DescriptorView.of("agent-gb1", null,
            List.of(new WeightedTerm("ni", 0.5), new WeightedTerm("te", 0.5)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure()).isNotNull();
        assertThat(result.graphStructure().connectiveBias()).isCloseTo(0.5, within(0.01));
        assertThat(result.graphStructure().inferenceStyle())
            .isEqualTo(GraphStructureDefaults.InferenceStyle.BALANCED);
    }

    @Test
    void graphStructureBoundary_justAboveConnective() {
        var descriptor = DescriptorView.of("agent-gb2", null,
            List.of(new WeightedTerm("ni", 0.61), new WeightedTerm("te", 0.39)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure().inferenceStyle())
            .isEqualTo(GraphStructureDefaults.InferenceStyle.CONNECTIVE);
    }

    @Test
    void graphStructureBoundary_justBelowCategorical() {
        var descriptor = DescriptorView.of("agent-gb3", null,
            List.of(new WeightedTerm("ni", 0.39), new WeightedTerm("te", 0.61)),
            List.of());

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.graphStructure().inferenceStyle())
            .isEqualTo(GraphStructureDefaults.InferenceStyle.CATEGORICAL);
    }

    @Test
    void multiKeywordGoal_accumulatesSubgraphWeights() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var descriptor = DescriptorView.of("agent-mk1", axes, List.of(),
            List.of("advance career in professional research"));

        var result = CognitiveDerivationEngine.derive(descriptor);

        assertThat(result.temporalFocus()).isNotNull();
        assertThat(result.temporalFocus().subgraphProximityWeight("PROJECT")).isGreaterThan(1.5);
        assertThat(result.temporalFocus().subgraphProximityWeight("RESEARCH_AREA")).isGreaterThan(1.0);
    }

    @Test
    void deriveAndMerge_explicitCbrStrategyWins() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicitCbr = new CbrStrategyDefaults(0.9, 30, RetrievalMode.FEATURE_ONLY);
        var explicit = CognitiveDefaults.empty("agent-m1")
                .withTenantId("t1")
                .withCbrStrategy(explicitCbr);

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.cbrStrategy().minSimilarity()).isCloseTo(0.9, within(0.01));
    }

    @Test
    void deriveAndMerge_explicitSocialCognitionWins() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicitSocial = new SocialCognitionDefaults(0.9, ConflictInterpretation.DISENGAGE);
        var explicit = CognitiveDefaults.empty("agent-m2")
                .withTenantId("t1")
                .withSocialCognition(explicitSocial)
                .withDescriptor(DescriptorView.of("agent-m2", axes, List.of(), List.of()));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.socialCognition().trustFormationRate()).isCloseTo(0.9, within(0.01));
        assertThat(result.socialCognition().conflictInterpretation()).isEqualTo(ConflictInterpretation.DISENGAGE);
    }

    @Test
    void deriveAndMerge_explicitGraphStructureWins() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicitGraph = new GraphStructureDefaults(GraphStructureDefaults.InferenceStyle.CATEGORICAL, 0.2);
        var explicit = CognitiveDefaults.empty("agent-m3")
                .withTenantId("t1")
                .withGraphStructure(explicitGraph)
                .withDescriptor(DescriptorView.of("agent-m3", axes,
                    List.of(new WeightedTerm("ni", 0.8), new WeightedTerm("fe", 0.2)), List.of()));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.graphStructure().inferenceStyle())
            .isEqualTo(GraphStructureDefaults.InferenceStyle.CATEGORICAL);
        assertThat(result.graphStructure().connectiveBias()).isCloseTo(0.2, within(0.01));
    }

    @Test
    void deriveAndMerge_explicitExtractionBiasWins() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "cooperative");
        var explicitBias = new ExtractionBiasDefaults(2.0, 0.5);
        var explicit = CognitiveDefaults.empty("agent-m4")
                .withTenantId("t1")
                .withExtractionBias(explicitBias)
                .withDescriptor(DescriptorView.of("agent-m4", axes,
                    List.of(new WeightedTerm("fe", 0.6), new WeightedTerm("fi", 0.4)), List.of()));

        var result = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(result.extractionBias().relationshipBias()).isCloseTo(2.0, within(0.01));
        assertThat(result.extractionBias().affectSensitivity()).isCloseTo(0.5, within(0.01));
    }

    // --- Appraisal Weights ---

    @Test
    void deriveAppraisalWeights_nullProfile_returnsNull() {
        assertThat(CognitiveDerivationEngine.deriveAppraisalWeights(null, null)).isNull();
    }

    @Test
    void deriveAppraisalWeights_emptyProfile_returnsNull() {
        assertThat(CognitiveDerivationEngine.deriveAppraisalWeights(List.of(), null)).isNull();
    }

    @Test
    void deriveAppraisalWeights_infj() {
        var profile = List.of(
            new WeightedTerm("ni", 0.35), new WeightedTerm("fe", 0.25),
            new WeightedTerm("ti", 0.22), new WeightedTerm("se", 0.18));
        var w = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);

        assertThat(w).isNotNull();
        assertThat(w.urgencyWeight()).isCloseTo(1.105, within(0.01));
        assertThat(w.relationshipWeight()).isCloseTo(1.045, within(0.01));
        assertThat(w.fearOnsetThreshold()).isCloseTo(0.745, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_estp() {
        var profile = List.of(
            new WeightedTerm("se", 0.35), new WeightedTerm("ti", 0.25),
            new WeightedTerm("fe", 0.22), new WeightedTerm("ni", 0.18));
        var w = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);

        assertThat(w).isNotNull();
        assertThat(w.urgencyWeight()).isCloseTo(0.805, within(0.01));
        assertThat(w.relationshipWeight()).isCloseTo(0.955, within(0.01));
        assertThat(w.fearOnsetThreshold()).isCloseTo(1.255, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_entj() {
        var profile = List.of(
            new WeightedTerm("te", 0.35), new WeightedTerm("ni", 0.25),
            new WeightedTerm("se", 0.22), new WeightedTerm("fi", 0.18));
        var w = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);

        assertThat(w).isNotNull();
        assertThat(w.urgencyWeight()).isCloseTo(1.195, within(0.01));
        assertThat(w.relationshipWeight()).isCloseTo(0.831, within(0.02));
        assertThat(w.fearOnsetThreshold()).isCloseTo(0.955, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_singleFunction_clampsToFloor() {
        var profile = List.of(new WeightedTerm("ni", 1.0));
        var w = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);

        assertThat(w).isNotNull();
        assertThat(w.urgencyWeight()).isCloseTo(1.0, within(0.001));
        assertThat(w.relationshipWeight()).isCloseTo(1.0, within(0.001));
        assertThat(w.fearOnsetThreshold()).isCloseTo(0.1, within(0.001));
    }

    @Test
    void deriveAppraisalWeights_balanced_nearNeutral() {
        var profile = List.of(
            new WeightedTerm("ni", 0.125), new WeightedTerm("ne", 0.125),
            new WeightedTerm("si", 0.125), new WeightedTerm("se", 0.125),
            new WeightedTerm("ti", 0.125), new WeightedTerm("te", 0.125),
            new WeightedTerm("fi", 0.125), new WeightedTerm("fe", 0.125));
        var w = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);

        assertThat(w).isNotNull();
        assertThat(w.urgencyWeight()).isCloseTo(1.0, within(0.001));
        assertThat(w.relationshipWeight()).isCloseTo(1.0, within(0.001));
        assertThat(w.fearOnsetThreshold()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void deriveAndMerge_explicitAppraisalWeightsWins() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var explicit = CognitiveDefaults.empty("test")
                .withAppraisalWeights(new AppraisalWeights(1.2, 0.9, 0.8, 1.0, 1.0))
                .withDescriptor(DescriptorView.of("test", axes,
                    List.of(new WeightedTerm("ni", 0.35), new WeightedTerm("fe", 0.25),
                            new WeightedTerm("ti", 0.22), new WeightedTerm("se", 0.18)),
                    List.of()));
        var merged = CognitiveDerivationEngine.deriveAndMerge(explicit);

        assertThat(merged.appraisalWeights().urgencyWeight()).isCloseTo(1.2, within(0.001));
        assertThat(merged.appraisalWeights().relationshipWeight()).isCloseTo(0.9, within(0.001));
        assertThat(merged.appraisalWeights().fearOnsetThreshold()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void deriveAppraisalWeights_strictRuleFollowing_highStrictness() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var axes    = new DispositionAxes("balanced", "strict", "moderate", "balanced", "cooperative");
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, axes);
        assertThat(w.selfStandardsStrictness()).isCloseTo(1.6, within(0.01));
        assertThat(w.otherStandardsStrictness()).isCloseTo(1.4, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_flexibleRuleFollowing_lowStrictness() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var axes    = new DispositionAxes("balanced", "flexible", "moderate", "balanced", "cooperative");
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, axes);
        assertThat(w.selfStandardsStrictness()).isCloseTo(0.7, within(0.01));
        assertThat(w.otherStandardsStrictness()).isCloseTo(0.6, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_cooperativeSocialOrient_reducesOtherStrictness() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var axes    = new DispositionAxes("cooperative", "strict", "moderate", "balanced", "cooperative");
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, axes);
        assertThat(w.selfStandardsStrictness()).isCloseTo(1.6, within(0.01));
        assertThat(w.otherStandardsStrictness()).isCloseTo(1.2, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_competitiveSocialOrient_increasesOtherStrictness() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var axes    = new DispositionAxes("competitive", "moderate", "moderate", "balanced", "cooperative");
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, axes);
        assertThat(w.otherStandardsStrictness()).isCloseTo(1.2, within(0.01));
    }

    @Test
    void deriveAppraisalWeights_nullAxes_defaultsStrictness() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, null);
        assertThat(w.selfStandardsStrictness()).isEqualTo(1.0);
        assertThat(w.otherStandardsStrictness()).isEqualTo(1.0);
    }

    @Test
    void deriveAppraisalWeights_strictCompetitive_clampsToMax() {
        var profile = List.of(new WeightedTerm("fi", 1.0));
        var axes    = new DispositionAxes("competitive", "strict", "moderate", "balanced", "cooperative");
        var w       = CognitiveDerivationEngine.deriveAppraisalWeights(profile, axes);
        assertThat(w.otherStandardsStrictness()).isCloseTo(1.6, within(0.01));
        assertThat(w.otherStandardsStrictness()).isLessThanOrEqualTo(2.0);
    }
}
