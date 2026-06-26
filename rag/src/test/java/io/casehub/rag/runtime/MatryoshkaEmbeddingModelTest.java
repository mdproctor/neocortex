package io.casehub.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MatryoshkaEmbeddingModelTest {

    @Test
    void truncatesEmbeddingToTargetDimension() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        Embedding result = model.embed("test").content();

        assertThat(result.dimension()).isEqualTo(4);
    }

    @Test
    void outputIsL2Normalized() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        Embedding result = model.embed("test").content();

        double norm = 0;
        for (float f : result.vector()) norm += f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void dimensionReturnsTargetDimension() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        assertThat(model.dimension()).isEqualTo(4);
    }

    @Test
    void modelNameIncludesMatryoshkaSuffix() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        assertThat(model.modelName()).isEqualTo("unknown/matryoshka-4");
    }

    @Test
    void embedAllTruncatesEveryEmbedding() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        Response<List<Embedding>> response = model.embedAll(List.of(
            TextSegment.from("a"), TextSegment.from("b"), TextSegment.from("c")));

        assertThat(response.content()).hasSize(3);
        response.content().forEach(e -> assertThat(e.dimension()).isEqualTo(4));
    }

    @Test
    void rejectsTargetDimensionGreaterThanDelegate() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(4);

        assertThatThrownBy(() -> new MatryoshkaEmbeddingModel(delegate, 8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTargetDimensionZero() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);

        assertThatThrownBy(() -> new MatryoshkaEmbeddingModel(delegate, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTargetDimensionNegative() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(8);

        assertThatThrownBy(() -> new MatryoshkaEmbeddingModel(delegate, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalDimensionStillNormalizes() {
        EmbeddingModel delegate = new RagTestFixtures.StubEmbeddingModel(4);
        MatryoshkaEmbeddingModel model = new MatryoshkaEmbeddingModel(delegate, 4);

        Embedding result = model.embed("test").content();

        assertThat(result.dimension()).isEqualTo(4);
        double norm = 0;
        for (float f : result.vector()) norm += f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
    }
}
