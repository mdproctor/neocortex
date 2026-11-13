package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchBudgetCapacityScenarioTest {

    private static final String TENANT = "test-tenant";

    @Test
    void analytical_agent_depth_capped_by_capacity() {
        var config = new SearchBudgetConfig(
                6.0, 0.5, 6.0, 0.5, 0.7,
                6.0, 0.05, 6.0, 0.5, 1.4,
                null, 0.85, null, null);
        var capacity = new CognitiveCapacityConfig(0.10, null, null);

        var harness = CognitionTestHarness.builder()
                .agentFromDefaults("analytical",
                        CognitiveDefaults.empty("analytical")
                                .withSearchBudgetConfig(config)
                                .withCapacityConfig(capacity))
                .build();

        harness.observe("analytical", TENANT, "complex problem requiring deep analysis");
        harness.tick("analytical", TENANT);

        var budget = harness.searchBudget("analytical", TENANT);
        assertThat(budget).as("analytical agent should have a search budget").isPresent();
        assertThat(budget.get().depth())
                .as("depth should be capped at relational complexity ceiling")
                .isLessThanOrEqualTo(0.10);
        assertThat(budget.get().capacityConstrained())
                .as("should be capacity constrained when depth exceeds ceiling")
                .isTrue();
        assertThat(budget.get().depthGap())
                .as("depth gap should exceed noise threshold")
                .isGreaterThan(0.05);
    }

    @Test
    void exploratory_agent_laterality_uncapped() {
        var config = new SearchBudgetConfig(
                6.0, 0.5, 6.0, 0.3, 0.7,
                6.0, 0.5, 6.0, 0.5, 1.4,
                null, 0.85, null, null);
        var capacity = new CognitiveCapacityConfig(null, 0.95, null);

        var harness = CognitionTestHarness.builder()
                .agentFromDefaults("exploratory",
                        CognitiveDefaults.empty("exploratory")
                                .withSearchBudgetConfig(config)
                                .withCapacityConfig(capacity))
                .build();

        harness.observe("exploratory", TENANT, "interesting tangent worth exploring");
        harness.tick("exploratory", TENANT);

        var budget = harness.searchBudget("exploratory", TENANT);
        assertThat(budget).as("exploratory agent should have a search budget").isPresent();
        assertThat(budget.get().capacityConstrained())
                .as("should NOT be capacity constrained — ceiling above activation")
                .isFalse();
        assertThat(budget.get().lateralityGap())
                .as("no laterality gap when capacity exceeds activation")
                .isEqualTo(0.0);
    }

    @Test
    void default_agent_null_capacity_backward_compatible() {
        var config = SearchBudgetConfig.defaults();

        var harness = CognitionTestHarness.builder()
                .agentFromDefaults("default-agent",
                        CognitiveDefaults.empty("default-agent")
                                .withSearchBudgetConfig(config))
                .build();

        harness.observe("default-agent", TENANT, "routine observation");
        harness.tick("default-agent", TENANT);

        var budget = harness.searchBudget("default-agent", TENANT);
        assertThat(budget).as("default agent should have a search budget").isPresent();
        assertThat(budget.get().capacityConstrained())
                .as("null capacity should never constrain")
                .isFalse();
        assertThat(budget.get().depthGap()).isEqualTo(0.0);
        assertThat(budget.get().breadthGap()).isEqualTo(0.0);
        assertThat(budget.get().lateralityGap()).isEqualTo(0.0);

        for (var param : new double[]{
                budget.get().breadth(), budget.get().laterality(),
                budget.get().depth(), budget.get().convergence()}) {
            assertThat(param).isBetween(0.0, 1.0);
        }
    }

    @Test
    void capacity_prose_composition_with_crystallised_descriptions() {
        var store = new InMemoryMindMapStore();

        var config = new SearchBudgetConfig(
                6.0, 0.5, 6.0, 0.5, 0.7,
                6.0, 0.05, 6.0, 0.5, 1.4,
                null, 0.85, null, null);
        var capacity = new CognitiveCapacityConfig(0.10, null, null);

        var harness = CognitionTestHarness.builder()
                .withMindMapStore(store)
                .agentFromDefaults("capped",
                        CognitiveDefaults.empty("capped")
                                .withSearchBudgetConfig(config)
                                .withCapacityConfig(capacity))
                .build();

        var sgId = store.createSubgraph(
                new SubgraphInput("cognitive", SubgraphTypes.COGNITIVE, null), TENANT);
        store.addNode(
                NodeInput.of("search-budget-capped", sgId)
                        .withTraits(Set.of("SearchBudgetGenerated"))
                        .withProperties(Map.of(
                                "agent-id", "capped",
                                "description", "Base exploration description.",
                                "constrained-description", "Your reach exceeds your grasp.")),
                TENANT);

        harness.observe("capped", TENANT, "deep analysis request");
        harness.tick("capped", TENANT);

        String prompt = harness.renderPrompt("capped", TENANT);
        assertThat(prompt)
                .as("constrained prose should prepend to base prose")
                .contains("Your reach exceeds your grasp.")
                .contains("Base exploration description.");

        int constrainedIdx = prompt.indexOf("Your reach exceeds your grasp.");
        int baseIdx = prompt.indexOf("Base exploration description.");
        assertThat(constrainedIdx)
                .as("constrained prose should appear before base prose")
                .isLessThan(baseIdx);
    }
}
