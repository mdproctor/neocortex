package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;

import java.nio.file.Path;
import java.util.List;

public final class RerankingDemo {

    public record RerankResult(String query, List<String> candidates, List<RankedResult> ranked) {}

    static final String QUERY = "How does ONNX inference work on the JVM?";

    static final List<String> CANDIDATES = List.of(
        "ONNX Runtime executes models directly inside the JVM via JNI, providing deterministic inference.",
        "CDI performs dependency injection at runtime using reflection and proxy generation.",
        "The Federal Reserve held interest rates steady at the latest policy meeting.",
        "Cross-encoder models process query-document pairs through every transformer layer for precise relevance scoring.",
        "Early termination of a lease requires 90 days written notice to the landlord.",
        "HuggingFace Tokenizers JNI provides fast tokenization for transformer models on the JVM.",
        "Global temperatures rose by 1.2 degrees Celsius in 2025 according to climate scientists.",
        "SPLADE uses masked language modeling to generate sparse learned vectors with implicit term expansion.",
        "The data processor shall implement appropriate technical and organisational security measures.",
        "Quarkus supports both imperative and reactive programming models for building cloud-native applications."
    );

    public static RerankResult run(CrossEncoderReranker reranker) {
        List<RankedResult> ranked = reranker.rerank(QUERY, CANDIDATES);
        return new RerankResult(QUERY, CANDIDATES, ranked);
    }

    public static void main(String[] args) {
        Path modelDir = Path.of("target/models/ms-marco-MiniLM-L-6-v2");
        try (InferenceModel model = new OnnxInferenceModel(
                new ModelConfig(modelDir.resolve("model.onnx"), modelDir.resolve("tokenizer.json")))) {
            var reranker = new CrossEncoderReranker(model);
            RerankResult result = run(reranker);
            printResults(result);
        }
    }

    static void printResults(RerankResult result) {
        System.out.printf("Query: %s%n%n", result.query());
        System.out.printf("%-4s %6s  %-4s  %s%n", "Rank", "Score", "Orig", "Candidate");
        System.out.println("-".repeat(120));
        for (int i = 0; i < result.ranked().size(); i++) {
            RankedResult r = result.ranked().get(i);
            System.out.printf("%-4d %6.3f  [%2d]  %s%n",
                i + 1, r.score(), r.originalIndex(), truncate(r.text(), 95));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
