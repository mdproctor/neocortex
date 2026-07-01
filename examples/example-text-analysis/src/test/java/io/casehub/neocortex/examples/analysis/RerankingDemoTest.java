package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class RerankingDemoTest {

    @Test
    void rerankProducesOrderedResults() {
        var model = InMemoryInferenceModel.withFunction(1, input -> {
            String candidate = input.texts().get(1);
            if (candidate.contains("ONNX")) return new float[]{0.95f};
            if (candidate.contains("inference")) return new float[]{0.7f};
            return new float[]{0.1f};
        });
        var reranker = new CrossEncoderReranker(model);

        RerankingDemo.RerankResult result = RerankingDemo.run(reranker);

        assertThat(result.query()).isNotBlank();
        assertThat(result.ranked()).isNotEmpty();
        assertThat(result.ranked().get(0).score())
            .isGreaterThanOrEqualTo(result.ranked().get(result.ranked().size() - 1).score());
    }

    @Test
    void candidatesSpanMultipleDomains() {
        var model = InMemoryInferenceModel.returning(0.5f);
        var reranker = new CrossEncoderReranker(model);

        RerankingDemo.RerankResult result = RerankingDemo.run(reranker);

        assertThat(result.candidates()).hasSizeGreaterThanOrEqualTo(6);
    }
}
