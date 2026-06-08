package io.casehub.inference.tasks;

import io.casehub.inference.InferenceException;
import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;
import io.casehub.inference.inmem.InMemoryInferenceModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossEncoderRerankerTest {

    @Nested
    @DisplayName("score()")
    class Score {

        @Test
        void returnsRawScoreFromModel() {
            var model = InMemoryInferenceModel.returning(0.85f);
            var reranker = new CrossEncoderReranker(model);
            assertThat(reranker.score("query", "candidate")).isEqualTo(0.85f);
        }
    }

    @Nested
    @DisplayName("rerank()")
    class Rerank {

        @Test
        void sortsByDescendingScore() {
            var model = InMemoryInferenceModel.withFunction(1, input -> {
                String candidate = input.texts().get(1);
                return switch (candidate) {
                    case "low" -> new float[]{0.1f};
                    case "mid" -> new float[]{0.5f};
                    case "high" -> new float[]{0.9f};
                    default -> new float[]{0.0f};
                };
            });
            var reranker = new CrossEncoderReranker(model);
            List<RankedResult> results = reranker.rerank("query", List.of("low", "mid", "high"));
            assertThat(results).hasSize(3);
            assertThat(results.get(0).text()).isEqualTo("high");
            assertThat(results.get(0).score()).isEqualTo(0.9f);
            assertThat(results.get(1).text()).isEqualTo("mid");
            assertThat(results.get(2).text()).isEqualTo("low");
        }

        @Test
        void preservesOriginalIndices() {
            var model = InMemoryInferenceModel.withFunction(1, input -> {
                String candidate = input.texts().get(1);
                return switch (candidate) {
                    case "a" -> new float[]{0.3f};
                    case "b" -> new float[]{0.9f};
                    case "c" -> new float[]{0.1f};
                    default -> new float[]{0.0f};
                };
            });
            var reranker = new CrossEncoderReranker(model);
            List<RankedResult> results = reranker.rerank("q", List.of("a", "b", "c"));
            assertThat(results.get(0).originalIndex()).isEqualTo(1);
            assertThat(results.get(1).originalIndex()).isEqualTo(0);
            assertThat(results.get(2).originalIndex()).isEqualTo(2);
        }

        @Test
        void singleCandidateWorks() {
            var model = InMemoryInferenceModel.returning(0.7f);
            var reranker = new CrossEncoderReranker(model);
            List<RankedResult> results = reranker.rerank("q", List.of("only"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).text()).isEqualTo("only");
            assertThat(results.get(0).originalIndex()).isEqualTo(0);
        }

        @Test
        void resultListIsUnmodifiable() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            List<RankedResult> results = reranker.rerank("q", List.of("a"));
            assertThatThrownBy(() -> results.add(new RankedResult("x", 0f, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void duplicateCandidatesScoredIndependently() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            List<RankedResult> results = reranker.rerank("q", List.of("same", "same"));
            assertThat(results).hasSize(2);
            assertThat(results.get(0).originalIndex())
                .isNotEqualTo(results.get(1).originalIndex());
        }
    }

    @Nested
    @DisplayName("construction validation")
    class ConstructionValidation {

        @Test
        void rejectsOutputSizeMismatch() {
            var model = InMemoryInferenceModel.returning(1.0f, 2.0f);
            assertThatThrownBy(() -> new CrossEncoderReranker(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("2");
        }

        @Test
        void rejectsNullModel() {
            assertThatThrownBy(() -> new CrossEncoderReranker(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        void scoreRejectsNullQuery() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.score(null, "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        }

        @Test
        void scoreRejectsNullCandidate() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.score("q", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
        }

        @Test
        void rerankRejectsNullQuery() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.rerank(null, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        }

        @Test
        void rerankRejectsNullCandidates() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.rerank("q", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidates");
        }

        @Test
        void rerankRejectsEmptyCandidates() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.rerank("q", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidates");
        }

        @Test
        void rerankRejectsNullElements() {
            var model = InMemoryInferenceModel.returning(0.5f);
            var reranker = new CrossEncoderReranker(model);
            var candidates = new ArrayList<String>();
            candidates.add("a");
            candidates.add(null);
            assertThatThrownBy(() -> reranker.rerank("q", candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidates[1]");
        }
    }

    @Nested
    @DisplayName("runtime output-length guard")
    class RuntimeGuard {

        @Test
        void scoreThrowsOnMultiElementOutput() {
            var model = InMemoryInferenceModel.withFunction(1, input -> new float[]{1.0f, 2.0f});
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.score("q", "c"))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("2");
        }

        @Test
        void rerankThrowsOnMultiElementOutput() {
            var model = InMemoryInferenceModel.withFunction(1, input -> new float[]{1.0f, 2.0f});
            var reranker = new CrossEncoderReranker(model);
            assertThatThrownBy(() -> reranker.rerank("q", List.of("a")))
                .isInstanceOf(InferenceException.class);
        }
    }
}
