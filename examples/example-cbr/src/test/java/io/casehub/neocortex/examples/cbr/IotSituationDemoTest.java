package io.casehub.neocortex.examples.cbr;

import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class IotSituationDemoTest {

    @Test
    void temperatureAnomalyKitchenQueryReturnsMatchingCases() {
        var store = new InMemoryCbrCaseMemoryStore();
        var results = IotSituationDemo.run(store);

        assertThat(results).isNotEmpty();
        // With graded similarity, matching cases score highest (1.0), non-matching cases score lower
        // Check that the top 5 results are the matching cases with score 1.0
        var topResults = results.stream().limit(5).toList();
        assertThat(topResults).allSatisfy(r -> {
            assertThat(r.scored().score()).isEqualTo(1.0);
            assertThat(r.scored().cbrCase()).isInstanceOf(FeatureVectorCbrCase.class);
            var c = (FeatureVectorCbrCase) r.scored().cbrCase();
            assertThat(c.problem()).isNotBlank();
            assertThat(c.solution()).isNotBlank();
            assertThat(c.features().get("situation_type")).isEqualTo(string("TEMPERATURE_ANOMALY"));
            assertThat(c.features().get("room_type")).isEqualTo(string("KITCHEN"));
        });
    }

    @Test
    void resultCountMatchesSeedData() {
        var store = new InMemoryCbrCaseMemoryStore();
        var results = IotSituationDemo.run(store);
        // With graded similarity, all cases are returned (filtered by identity: tenant, domain, caseType)
        // The query returns all 10 seed cases, with matching cases scoring highest
        assertThat(results).hasSize(10);
        // Verify that 5 cases have perfect match scores (situation_type=TEMPERATURE_ANOMALY + room_type=KITCHEN)
        var perfectMatches = results.stream().filter(r -> r.scored().score() == 1.0).toList();
        assertThat(perfectMatches).hasSize(5);
    }

    @Test
    void outcomesIncludeDismissedAndWorkItemCreated() {
        var store = new InMemoryCbrCaseMemoryStore();
        var results = IotSituationDemo.run(store);
        var outcomes = results.stream()
            .map(r -> ((FeatureVectorCbrCase) r.scored().cbrCase()).outcome())
            .toList();
        assertThat(outcomes).contains("OPERATOR_DISMISSED", "WORK_ITEM_CREATED");
    }
}
