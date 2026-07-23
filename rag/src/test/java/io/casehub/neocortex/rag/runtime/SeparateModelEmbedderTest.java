package io.casehub.neocortex.rag.runtime;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.neocortex.inference.EmbeddingMode;
import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;
import io.casehub.neocortex.inference.MultiModalEmbedding;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeparateModelEmbedderTest {

    @Test
    void denseOnlyEmbed() {
        EmbeddingModel denseModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[]{1f, 2f, 3f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<Embedding> embeddings = segments.stream()
                                                     .map(seg -> Embedding.from(new float[]{1f, 2f, 3f}))
                                                     .toList();
                return Response.from(embeddings);
            }

            @Override
            public int dimension() {
                return 3;
            }
        };
        SeparateModelEmbedder embedder = new SeparateModelEmbedder(denseModel, 512);

        MultiModalEmbedding result = embedder.embed("test");

        assertArrayEquals(new float[]{1f, 2f, 3f}, result.dense(), 1e-5f);
        assertNull(result.sparse());
        assertNull(result.colbert());
        assertEquals(Set.of(EmbeddingMode.DENSE), embedder.supportedModes());
        assertEquals(3, embedder.denseDimension());
        assertTrue(embedder.colbertDimension().isEmpty());
    }

    @Test
    void denseAndSparseEmbed() {
        EmbeddingModel denseModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[]{1f, 2f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<Embedding> embeddings = segments.stream()
                                                     .map(seg -> Embedding.from(new float[]{1f, 2f}))
                                                     .toList();
                return Response.from(embeddings);
            }

            @Override
            public int dimension() {
                return 2;
            }
        };
        InferenceModel sparseModel = rank2StubModel(new float[]{0f, 0.5f, 0f, 2.0f});
        SparseEmbedder sparse      = new SparseEmbedder(sparseModel);

        SeparateModelEmbedder embedder = new SeparateModelEmbedder(denseModel, sparse, 512);

        MultiModalEmbedding result = embedder.embed("test");
        assertArrayEquals(new float[]{1f, 2f}, result.dense(), 1e-5f);
        assertNotNull(result.sparse());
        assertEquals(Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE), embedder.supportedModes());
    }

    @Test
    void embedBatch() {
        EmbeddingModel denseModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[]{1f, 2f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<Embedding> embeddings = segments.stream()
                                                     .map(seg -> Embedding.from(new float[]{1f, 2f}))
                                                     .toList();
                return Response.from(embeddings);
            }

            @Override
            public int dimension() {
                return 2;
            }
        };

        SeparateModelEmbedder embedder = new SeparateModelEmbedder(denseModel, 512);

        List<MultiModalEmbedding> results = embedder.embedBatch(List.of("test1", "test2"));
        assertEquals(2, results.size());
        assertArrayEquals(new float[]{1f, 2f}, results.get(0).dense(), 1e-5f);
        assertArrayEquals(new float[]{1f, 2f}, results.get(1).dense(), 1e-5f);
    }

    @Test
    void embedMapRoutesDenseAndSparseToSeparateModels() {
        List<String> denseTexts = new ArrayList<>();
        EmbeddingModel denseModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                denseTexts.add(text);
                return Response.from(Embedding.from(new float[]{1f, 2f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream()
                                             .map(s -> {
                                                 denseTexts.add(s.text());
                                                 return Embedding.from(new float[]{1f, 2f});
                                             })
                                             .toList());
            }

            @Override
            public int dimension() {return 2;}
        };

        List<String> sparseTexts = new ArrayList<>();
        InferenceModel sparseModel = new InferenceModel() {
            @Override
            public InferenceOutput run(InferenceInput input) {
                sparseTexts.add(input.texts().get(0));
                return InferenceOutput.of(new float[]{0f, 0.5f, 0f, 2.0f});
            }

            @Override
            public List<InferenceOutput> runBatch(List<InferenceInput> inputs) {
                return inputs.stream().map(this::run).toList();
            }

            @Override
            public void close() {}
        };
        SparseEmbedder        sparse   = new SparseEmbedder(sparseModel);
        SeparateModelEmbedder embedder = new SeparateModelEmbedder(denseModel, sparse, 512);

        Map<EmbeddingMode, String> textsByMode = Map.of(
                EmbeddingMode.DENSE, "dense-text",
                EmbeddingMode.SPARSE, "sparse-text");
        MultiModalEmbedding result = embedder.embed(textsByMode);

        assertArrayEquals(new float[]{1f, 2f}, result.dense(), 1e-5f);
        assertNotNull(result.sparse());
        assertEquals(List.of("dense-text"), denseTexts);
        assertEquals(List.of("sparse-text"), sparseTexts);
    }


    private InferenceModel rank2StubModel(float[] values) {
        return new InferenceModel() {
            @Override
            public InferenceOutput run(InferenceInput input) {
                return InferenceOutput.of(values);
            }

            @Override
            public List<InferenceOutput> runBatch(List<InferenceInput> inputs) {
                return inputs.stream()
                             .map(this::run)
                             .toList();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}
