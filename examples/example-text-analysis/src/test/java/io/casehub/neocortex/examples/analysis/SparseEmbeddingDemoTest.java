package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.splade.SparseEmbedder;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("smoke")
class SparseEmbeddingDemoTest {

    private static final int VOCAB_SIZE = 100;

    @Test
    void allTextsProduceSparseVectors() {
        float[] output = new float[VOCAB_SIZE];
        output[0] = 2.0f;
        output[5] = 1.5f;
        output[42] = 0.8f;
        var model = InMemoryInferenceModel.returning(output);
        var embedder = new SparseEmbedder(model);

        List<SparseEmbeddingDemo.EmbeddingResult> results = SparseEmbeddingDemo.run(embedder);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.text()).isNotBlank();
            assertThat(r.sparseVector()).isNotEmpty();
            assertThat(r.sparseVector().values()).allSatisfy(v ->
                assertThat(v).isGreaterThan(0.0f));
        });
    }

    @Test
    void coversMultipleDomains() {
        float[] output = new float[VOCAB_SIZE];
        output[0] = 1.0f;
        var model = InMemoryInferenceModel.returning(output);
        var embedder = new SparseEmbedder(model);

        List<SparseEmbeddingDemo.EmbeddingResult> results = SparseEmbeddingDemo.run(embedder);

        var domains = results.stream().map(SparseEmbeddingDemo.EmbeddingResult::domain).distinct().toList();
        assertThat(domains).containsExactlyInAnyOrder("tech", "news", "legal");
    }
}
