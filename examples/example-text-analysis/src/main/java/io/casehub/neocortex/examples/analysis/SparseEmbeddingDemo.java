package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.splade.SparseEmbedder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SparseEmbeddingDemo {

    record TextSample(String domain, String text) {}

    public record EmbeddingResult(String domain, String text, Map<Integer, Float> sparseVector) {}

    static final List<TextSample> SAMPLES = List.of(
        new TextSample("tech",  "How does dependency injection work in Quarkus applications?"),
        new TextSample("tech",  "ONNX Runtime provides cross-platform model execution via JNI."),
        new TextSample("news",  "The central bank announced a rate cut to stimulate economic growth."),
        new TextSample("news",  "AI regulation proposals are being debated across major economies."),
        new TextSample("legal", "The lessee may terminate the agreement with 30 days written notice."),
        new TextSample("legal", "Personal data must be processed in accordance with GDPR principles.")
    );

    public static List<EmbeddingResult> run(SparseEmbedder embedder) {
        List<EmbeddingResult> results = new ArrayList<>();
        for (TextSample sample : SAMPLES) {
            Map<Integer, Float> sparse = embedder.embed(sample.text());
            results.add(new EmbeddingResult(sample.domain(), sample.text(), sparse));
        }
        return results;
    }

    public static void main(String[] args) {
        Path modelDir = Path.of("target/models/splade");
        try (InferenceModel model = new OnnxInferenceModel(
                new ModelConfig(modelDir.resolve("model.onnx"), modelDir.resolve("tokenizer.json")))) {
            var embedder = new SparseEmbedder(model);
            List<EmbeddingResult> results = run(embedder);
            printResults(results);
        }
    }

    static void printResults(List<EmbeddingResult> results) {
        for (EmbeddingResult r : results) {
            System.out.printf("%n[%s] %s%n", r.domain(), r.text());
            System.out.printf("  Sparse vector: %d non-zero entries%n", r.sparseVector().size());
            System.out.printf("  Top-10 token indices by weight:%n");
            r.sparseVector().entrySet().stream()
                .sorted(Map.Entry.<Integer, Float>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.printf("    token[%5d] = %.4f%n", e.getKey(), e.getValue()));
        }
    }
}
