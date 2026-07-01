package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;
import io.casehub.neocortex.inference.tasks.NliResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ZeroShotClassificationDemo {

    record TextSample(String domain, String text) {}

    public record Result(String domain, String text, String topLabel, Map<String, Float> scores) {}

    static final List<String> CANDIDATE_LABELS = List.of(
        "technology", "finance", "law", "healthcare", "politics"
    );

    static final List<TextSample> SAMPLES = List.of(
        new TextSample("tech",  "Dependency injection decouples component creation from usage, allowing the container to manage lifecycle and scoping."),
        new TextSample("news",  "The Federal Reserve held interest rates steady, citing persistent inflation concerns and a resilient labor market."),
        new TextSample("legal", "Notwithstanding the provisions of subsection (b), the lessee shall not terminate prior to the expiration of the initial term.")
    );

    public static List<Result> run(NliClassifier nli) {
        List<Result> results = new ArrayList<>();
        for (TextSample sample : SAMPLES) {
            Map<String, Float> scores = new LinkedHashMap<>();
            for (String label : CANDIDATE_LABELS) {
                NliResult nliResult = nli.classify(sample.text(), "This text is about " + label + ".");
                scores.put(label, nliResult.entailment());
            }
            String topLabel = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
            results.add(new Result(sample.domain(), sample.text(), topLabel, Map.copyOf(scores)));
        }
        return results;
    }

    public static void main(String[] args) {
        Path modelDir = Path.of("target/models/nli-deberta-v3-xsmall");
        try (InferenceModel model = new OnnxInferenceModel(
                new ModelConfig(modelDir.resolve("model.onnx"), modelDir.resolve("tokenizer.json")))) {
            var nli = new NliClassifier(model, 0, 1, 2);
            List<Result> results = run(nli);
            printResults(results);
        }
    }

    static void printResults(List<Result> results) {
        for (Result r : results) {
            System.out.printf("%n[%s] %s%n", r.domain(), truncate(r.text(), 100));
            System.out.printf("  Top label: %s%n", r.topLabel());
            r.scores().entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .forEach(e -> System.out.printf("    %-15s %.3f%n", e.getKey(), e.getValue()));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
