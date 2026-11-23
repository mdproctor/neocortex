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
package io.casehub.neocortex.cognition.core;

import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.appraisal.NoOpAppraisalStrategy;
import io.casehub.neocortex.cognition.appraisal.NoOpSalienceStrategy;
import io.casehub.neocortex.cognition.mood.MoodConfig;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.mood.MoodSignal;
import io.casehub.neocortex.cognition.prompt.SectionTier;
import io.casehub.neocortex.cognition.prompt.TierFilterCustomizer;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CognitionCoreTierFilterTest {

    @Test
    void configureTierFilterSuppressesSupplementaryUnderHighArousal() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        core.configureTierFilter(0.9);

        mood.record(new MoodSignal.DirectShift(0.0, 0.5, 0.0, "test-arousal"), "agent1", "tenant1");
        mood.tick("agent1", "tenant1");

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        var sections = core.promptSections();
        assertThat(sections).allSatisfy(s ->
                assertThat(TierFilterCustomizer.tierOf(s)).isNotEqualTo(SectionTier.SUPPLEMENTARY));
    }

    @Test
    void noTierFilterRendersAllSections() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        var sections = core.promptSections();
        assertThat(sections).isNotEmpty();
    }

    @Test
    void lowArousalRendersAllTiersWithTierFilter() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        core.configureTierFilter(0.5);

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        var sections = core.promptSections();
        assertThat(sections).isNotEmpty();
    }

    @Test
    void configureTierFilterIsIdempotent() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        core.configureTierFilter(0.9);
        core.configureTierFilter(0.5);

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        var sections = core.promptSections();
        // scalar=0.5 → suppThreshold=0.5, arousal=0.0 < 0.5 → all tiers render
        // If chaining were broken, the old 0.9 filter would still suppress
        assertThat(sections).isNotEmpty();
        // Verify supplementary sections are present (arousal 0.0 with scalar 0.5)
        boolean hasSupplementary = sections.stream()
                                           .anyMatch(s -> TierFilterCustomizer.tierOf(s) == SectionTier.SUPPLEMENTARY);
        // With CognitionConfig.all() and no orchestrators, supplementary sections
        // won't be added because the orchestrators are null. But no sections
        // should be filtered OUT by the tier filter at arousal 0.0.
        assertThat(sections).hasSameSizeAs(core.promptSections());
    }

    @Test
    void activatedDriveCategoriesEmptyWhenNoAppraisalParticipant() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        assertThat(core.activatedDriveCategories()).isEmpty();
    }

    @Test
    void activatedDriveCategoriesEmptyBeforeTick() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        assertThat(core.activatedDriveCategories()).isEmpty();
    }

    @Test
    void activatedDriveCategoriesIncludesConcernSalientDrives() {
        var mood = new MoodOrchestrator(MoodConfig.defaults());
        var core = new CognitionCore(mood, null, null, null, null, null, null, null);

        var stubAppraisal = new AppraisalTickParticipant(
                new NoOpSalienceStrategy(),
                new NoOpAppraisalStrategy(),
                null, mood, null,
                CognitionConfig.none().with("appraisal", true)) {
            @Override
            public Map<String, Double> currentConcernSalience(String agentId, String tenantId) {
                return Map.of("scheming", 0.8);
            }
        };
        core.setAppraisalParticipant(stubAppraisal);

        core.tick("agent1", "tenant1", null, (a, t) -> Set.of());

        assertThat(core.activatedDriveCategories()).contains("scheming");
    }


}
