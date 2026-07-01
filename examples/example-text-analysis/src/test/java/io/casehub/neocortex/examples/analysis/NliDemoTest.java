package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class NliDemoTest {

    @Test
    void allDomainsProduceResults() {
        var model = InMemoryInferenceModel.returning(0.1f, 0.2f, 0.7f);
        var nli = new NliClassifier(model, 0, 1, 2);

        List<NliDemo.Result> results = NliDemo.run(nli);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.domain()).isNotBlank();
            assertThat(r.premise()).isNotBlank();
            assertThat(r.hypothesis()).isNotBlank();
            assertThat(r.result().predicted()).isNotNull();
            assertThat(r.result().entailment() + r.result().neutral() + r.result().contradiction())
                .isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-5f));
        });
    }

    @Test
    void coversAllThreeDomains() {
        var model = InMemoryInferenceModel.returning(0.1f, 0.2f, 0.7f);
        var nli = new NliClassifier(model, 0, 1, 2);

        List<NliDemo.Result> results = NliDemo.run(nli);

        var domains = results.stream().map(NliDemo.Result::domain).distinct().toList();
        assertThat(domains).containsExactlyInAnyOrder("tech", "news", "legal");
    }

    @Test
    void includesEntailmentAndContradictionPairs() {
        var model = InMemoryInferenceModel.returning(0.1f, 0.2f, 0.7f);
        var nli = new NliClassifier(model, 0, 1, 2);

        List<NliDemo.Result> results = NliDemo.run(nli);

        assertThat(results.size()).isGreaterThanOrEqualTo(6);
    }
}
