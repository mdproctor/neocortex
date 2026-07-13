package io.casehub.neocortex.memory.cbr.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;

import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static org.assertj.core.api.Assertions.*;

class EmbeddingTextSimilarityTest {

    static EmbeddingModel stubModel() {
        return new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                return Response.from(vectorFor(segment.text()));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream()
                    .map(s -> vectorFor(s.text())).toList());
            }

            @Override
            public int dimension() { return 3; }

            private Embedding vectorFor(String text) {
                return switch (text) {
                    case "hello" -> Embedding.from(new float[]{1.0f, 0.0f, 0.0f});
                    case "hi" -> Embedding.from(new float[]{0.9f, 0.1f, 0.0f});
                    case "goodbye" -> Embedding.from(new float[]{0.0f, 1.0f, 0.0f});
                    default -> Embedding.from(new float[]{0.0f, 0.0f, 1.0f});
                };
            }
        };
    }

    @Test
    void identicalTextsScoreOne() {
        var sim = new EmbeddingTextSimilarity(stubModel());
        assertThat(sim.compute(string("hello"), string("hello"))).isEqualTo(1.0);
    }

    @Test
    void similarTextsScoreHighButNotOne() {
        var sim = new EmbeddingTextSimilarity(stubModel());
        double score = sim.compute(string("hello"), string("hi"));
        // cos(hello, hi) = (0.9)/sqrt(1*0.82) ≈ 0.9938
        assertThat(score).isGreaterThan(0.9);
        assertThat(score).isLessThan(1.0);
    }

    @Test
    void dissimilarTextsScoreLow() {
        var sim = new EmbeddingTextSimilarity(stubModel());
        double score = sim.compute(string("hello"), string("goodbye"));
        // cos([1,0,0], [0,1,0]) = 0.0
        assertThat(score).isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void negativeCosineClampedToZero() {
        EmbeddingModel negModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                if (segment.text().equals("a")) return Response.from(Embedding.from(new float[]{1.0f, 0.0f}));
                return Response.from(Embedding.from(new float[]{-1.0f, 0.0f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream()
                    .map(s -> embed(s).content()).toList());
            }

            @Override
            public int dimension() { return 2; }
        };
        var sim = new EmbeddingTextSimilarity(negModel);
        assertThat(sim.compute(string("a"), string("b"))).isEqualTo(0.0);
    }

    @Test
    void embedFailurePropagates() {
        EmbeddingModel failingModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                throw new RuntimeException("Embedding service unavailable");
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new RuntimeException("Embedding service unavailable");
            }

            @Override
            public int dimension() { return 3; }
        };
        var sim = new EmbeddingTextSimilarity(failingModel);
        assertThatThrownBy(() -> sim.compute(string("a"), string("b")))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void queryEmbeddingCachedAcrossCalls() {
        int[] callCount = {0};
        EmbeddingModel countingModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                callCount[0]++;
                return Response.from(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream()
                    .map(s -> embed(s).content()).toList());
            }

            @Override
            public int dimension() { return 3; }
        };
        var sim = new EmbeddingTextSimilarity(countingModel);
        sim.compute(string("query"), string("case1"));
        sim.compute(string("query"), string("case2"));
        // "query" embedded once (cached), "case1" once, "case2" once = 3 total
        assertThat(callCount[0]).isEqualTo(3);
    }

    @Test
    void precomputeBatchEmbedsAndWarmsCache() {
        int[] embedAllCalls = {0};
        EmbeddingModel batchModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                return Response.from(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                embedAllCalls[0]++;
                return Response.from(segments.stream()
                    .map(s -> Embedding.from(new float[]{1.0f, 0.0f, 0.0f})).toList());
            }

            @Override
            public int dimension() { return 3; }
        };
        var sim = new EmbeddingTextSimilarity(batchModel);
        sim.precompute(List.of("a", "b", "c"));
        assertThat(embedAllCalls[0]).isEqualTo(1);

        // compute() should hit warm cache — no additional embed calls
        sim.compute(string("a"), string("b"));
        assertThat(embedAllCalls[0]).isEqualTo(1);
    }

    @Test
    void precomputeSkipsAlreadyCached() {
        int[] embedAllCalls = {0};
        EmbeddingModel batchModel = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(TextSegment segment) {
                return Response.from(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}));
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                embedAllCalls[0]++;
                return Response.from(segments.stream()
                    .map(s -> Embedding.from(new float[]{1.0f, 0.0f, 0.0f})).toList());
            }

            @Override
            public int dimension() { return 3; }
        };
        var sim = new EmbeddingTextSimilarity(batchModel);
        sim.precompute(List.of("a", "b"));
        sim.precompute(List.of("a", "c"));
        // Second call should only embed "c"
        assertThat(embedAllCalls[0]).isEqualTo(2);
    }

    private static org.assertj.core.data.Offset<Double> offset(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
