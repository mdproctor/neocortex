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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopingDefaultsDerivationTest {

    @Test
    void highConscientiousness_highPlanfulTendency() {
        var axes = new DispositionAxes("cooperative", "strict", "conservative", "moderate", "analytical");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.2, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.planfulTendency()).isGreaterThan(0.7);
        assertThat(cd.avoidanceTendency()).isLessThan(0.3);
    }

    @Test
    void highNeuroticism_highAvoidanceTendency() {
        var axes = new DispositionAxes("independent", "flexible", "conservative", "low", "avoidant");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.85, "test"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.7, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.8, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.7, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.7, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.3, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.avoidanceTendency()).isGreaterThan(0.7);
        assertThat(cd.planfulTendency()).isLessThan(0.4);
        assertThat(cd.acceptanceTendency()).isLessThan(0.3);
    }

    @Test
    void highOpenness_highReinterpretationTendency() {
        var axes = new DispositionAxes("cooperative", "flexible", "bold", "high", "analytical");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.9, "test"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.3, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.reinterpretationTendency()).isGreaterThan(0.7);
    }

    @Test
    void highAgreeablenessAndExtraversion_highSupportSeekingTendency() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "cooperative");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.4, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.4, "test"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.4, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.2, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.supportSeekingTendency()).isGreaterThan(0.7);
    }

    @Test
    void lowNeuroticism_highAcceptanceTendency() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.3, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.acceptanceTendency()).isGreaterThan(0.8);
    }

    @Test
    void nullFacets_returnsNull() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");
        assertThat(CognitiveDerivationEngine.deriveCopingDefaults(axes, null)).isNull();
    }

    @Test
    void emptyFacets_returnsNull() {
        var axes = new DispositionAxes("cooperative", "moderate", "calculated", "moderate", "analytical");
        assertThat(CognitiveDerivationEngine.deriveCopingDefaults(axes, List.of())).isNull();
    }

    @Test
    void nullAxes_stillDerives() {
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.1, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.3, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(null, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.planfulTendency()).isBetween(0.0, 1.0);
        assertThat(cd.avoidanceTendency()).isBetween(0.0, 1.0);
    }

    @Test
    void allValues_clampedToUnitInterval() {
        var axes = new DispositionAxes("cooperative", "strict", "bold", "high", "analytical");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 1.0, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 1.0, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 1.0, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.INTIMACY_AVOIDANCE, 0.0, "test"),
                new PersonalityFacet(PersonalityFacet.MANIPULATIVENESS, 0.0, "test")
        );
        var cd = CognitiveDerivationEngine.deriveCopingDefaults(axes, facets);
        assertThat(cd).isNotNull();
        assertThat(cd.planfulTendency()).isBetween(0.0, 1.0);
        assertThat(cd.reinterpretationTendency()).isBetween(0.0, 1.0);
        assertThat(cd.avoidanceTendency()).isBetween(0.0, 1.0);
        assertThat(cd.supportSeekingTendency()).isBetween(0.0, 1.0);
        assertThat(cd.acceptanceTendency()).isBetween(0.0, 1.0);
    }

    @Test
    void derivePipeline_wiresCopingDefaults() {
        var axes = new DispositionAxes("cooperative", "strict", "conservative", "moderate", "analytical");
        var facets = List.of(
                new PersonalityFacet(PersonalityFacet.IMPULSIVITY, 0.2, "test"),
                new PersonalityFacet(PersonalityFacet.ANXIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.EMOTIONAL_LABILITY, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.WITHDRAWAL, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.RISK_TAKING, 0.5, "test"),
                new PersonalityFacet(PersonalityFacet.SUSPICIOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.CALLOUSNESS, 0.3, "test"),
                new PersonalityFacet(PersonalityFacet.HOSTILITY, 0.3, "test")
        );
        var descriptor = new DescriptorView("agent-coping", axes,
                List.of(new WeightedTerm("te", 0.6), new WeightedTerm("si", 0.4)),
                List.of(), null, facets);
        var result = CognitiveDerivationEngine.derive(descriptor);
        assertThat(result.copingDefaults()).isNotNull();
        assertThat(result.copingDefaults().planfulTendency()).isGreaterThan(0.5);
    }
}
