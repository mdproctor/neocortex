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
package io.casehub.neocortex.cognitive.eidos;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.DescriptorView;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.WeightedTerm;
import io.casehub.neocortex.memory.mood.MoodBaseline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EidosCognitiveProfileBridgeTest {

    @Test
    void toDescriptorViewExtractsAxesAndProfile() {
        var descriptor = AgentDescriptor.builder()
                                        .agentId("dick-dastardly")
                                        .name("Dick Dastardly")
                                        .slot("villain")
                                        .tenancyId("manor")
                                        .disposition(AgentDisposition.builder()
                                                                     .socialOrient("competitive")
                                                                     .ruleFollowing("flexible")
                                                                     .riskAppetite("bold")
                                                                     .autonomy("high")
                                                                     .conflictMode("competing")
                                                                     .dispositionProfile(
                                                                             new DispositionValue("Te", 0.8),
                                                                             new DispositionValue("Se", 0.6))
                                                                     .build())
                                        .goals(List.of(
                                                new AgentGoal("win race", "win at all costs",
                                                              GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), null),
                                                new AgentGoal("cheat", "use underhanded tactics",
                                                              GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), null)))
                                        .build();

        var view = EidosCognitiveProfileBridge.toDescriptorView(descriptor);

        assertEquals("dick-dastardly", view.agentId());
        assertEquals("competitive", view.disposition().socialOrient());
        assertEquals("flexible", view.disposition().ruleFollowing());
        assertEquals("bold", view.disposition().riskAppetite());
        assertEquals("high", view.disposition().autonomy());
        assertEquals("competing", view.disposition().conflictMode());
        assertEquals(2, view.dispositionProfile().size());
        assertEquals("Te", view.dispositionProfile().get(0).term());
        assertEquals(0.8, view.dispositionProfile().get(0).weight(), 0.001);
        assertEquals(List.of("win race", "cheat"), view.goals());
    }

    @Test
    void derivationFromViewProducesAllPathways() {
        var view = DescriptorView.of(
                "muttley",
                new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                List.of(new WeightedTerm("Se", 0.8), new WeightedTerm("Te", 0.6)),
                List.of("cause chaos", "avoid punishment"));

        var derived = io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(view);

        assertEquals("muttley", derived.agentId());
        assertNotNull(derived.personalityProfile());
        assertNotNull(derived.moodBaseline());
        assertNotNull(derived.curiosity());
        assertNotNull(derived.cbrStrategy());
        assertNotNull(derived.socialCognition());
        assertNotNull(derived.graphStructure());
        assertNotNull(derived.extractionBias());
        assertNotNull(derived.appraisalWeights());
        assertNotNull(derived.habituationConfig());
    }

    @Test
    void differentDispositionsProduceDifferentProfiles() {
        var muttleyView = DescriptorView.of(
                "muttley",
                new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                List.of(new WeightedTerm("Se", 0.8), new WeightedTerm("Te", 0.6)),
                List.of("cause chaos"));
        var penelopeView = DescriptorView.of(
                "penelope",
                new DispositionAxes("cooperative", "strict", "conservative", "moderate", "cooperative"),
                List.of(new WeightedTerm("Fe", 0.9), new WeightedTerm("Fi", 0.7)),
                List.of("help others", "stay safe"));

        var m = io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(muttleyView);
        var p = io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(penelopeView);

        assertNotEquals(m.personalityProfile().personality(), p.personalityProfile().personality());
        assertNotEquals(m.moodBaseline(), p.moodBaseline());
        assertNotEquals(m.cbrStrategy(), p.cbrStrategy());
        assertNotEquals(m.socialCognition(), p.socialCognition());
    }

    @Test
    void explicitMergePreservesYamlValues() {
        var yamlMood = new MoodBaseline(0.9, -0.5, 0.3);
        var explicit = CognitiveDefaults.empty("muttley").withMoodBaseline(yamlMood);
        var view = DescriptorView.of(
                "muttley",
                new DispositionAxes("competitive", "flexible", "bold", "high", "competitive"),
                List.of(new WeightedTerm("Se", 0.8)),
                List.of("cause chaos"));
        var derived = io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(view);

        var merged = io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults.merge(explicit, derived);

        assertEquals(yamlMood, merged.moodBaseline(), "YAML mood baseline should be preserved");
        assertNotNull(merged.cbrStrategy(), "derived CBR strategy should fill the gap");
        assertNotNull(merged.socialCognition(), "derived social cognition should fill the gap");
    }

    @Test
    void emptyDispositionProfileProducesNullGraphStructure() {
        var view = DescriptorView.of(
                "no-profile",
                new DispositionAxes("cooperative", "strict", "conservative", "moderate", "cooperative"),
                List.of(),
                List.of("survive"));

        var derived = io.casehub.neocortex.cognitive.index.CognitiveDerivationEngine.derive(view);

        assertNotNull(derived.moodBaseline());
        assertNull(derived.graphStructure(), "empty disposition profile should produce null graph structure");
    }

    @Test
    void composeFacetsClampsNegativeToZero() {
        var disposition = AgentDisposition.builder()
                                          .socialOrient("cooperative")
                                          .ruleFollowing("moderate")
                                          .riskAppetite("moderate")
                                          .autonomy("moderate")
                                          .conflictMode("cooperative")
                                          .ampdFacets(List.of(
                                                  new io.casehub.eidos.api.AmpdFacet("Anxiousness", 20)))
                                          .build();

        var snapshot = new io.casehub.neocortex.cognitive.index.PersonalityStateSnapshot(
                java.util.Map.of("Anxiousness",
                                 new io.casehub.neocortex.cognitive.index.PersonalityStateSnapshot.FacetDisplacement(
                                         -0.5, 0.0, 0.0)),
                1L);

        var composed = EidosCognitiveProfileBridge.composeFacets(disposition, snapshot);
        var anxFacet = composed.ampdFacets().stream()
                               .filter(f -> "Anxiousness".equals(f.facet()))
                               .findFirst().orElseThrow();
        assertEquals(0, anxFacet.score(), "score pushed below zero by homeOffset should clamp to 0");
    }

    @Test
    void composeFacetsClampsAboveHundredToHundred() {
        var disposition = AgentDisposition.builder()
                                          .socialOrient("cooperative")
                                          .ruleFollowing("moderate")
                                          .riskAppetite("moderate")
                                          .autonomy("moderate")
                                          .conflictMode("cooperative")
                                          .ampdFacets(List.of(
                                                  new io.casehub.eidos.api.AmpdFacet("Anxiousness", 80)))
                                          .build();

        var snapshot = new io.casehub.neocortex.cognitive.index.PersonalityStateSnapshot(
                java.util.Map.of("Anxiousness",
                                 new io.casehub.neocortex.cognitive.index.PersonalityStateSnapshot.FacetDisplacement(
                                         0.5, 0.0, 0.0)),
                1L);

        var composed = EidosCognitiveProfileBridge.composeFacets(disposition, snapshot);
        var anxFacet = composed.ampdFacets().stream()
                               .filter(f -> "Anxiousness".equals(f.facet()))
                               .findFirst().orElseThrow();
        assertEquals(100, anxFacet.score(), "score pushed above 100 by homeOffset should clamp to 100");
    }

}
