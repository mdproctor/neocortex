package io.casehub.neocortex.examples.cbr;

import io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class CbrDemoRunnerTest {

    @Test
    void allSixDemosRunWithoutError() {
        var store = new InMemoryCbrCaseMemoryStore();
        // Should not throw — all six demos register schemas, store, and query
        var allResults = CbrDemoRunner.run(store);
        assertThat(allResults).hasSize(6);
        assertThat(allResults.values()).allSatisfy(
            results -> assertThat(results).isNotEmpty());
    }
}
