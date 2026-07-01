package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.ClassificationResult;
import io.casehub.neocortex.inference.tasks.TextClassifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ScoringDemo {

    public record ScoredText(String text, String label, float score) {}

    static final List<String> SENTIMENT_TEXTS = List.of(
        "The service was absolutely terrible, I waited two hours and nobody came.",
        "The food was okay, nothing special but not bad either.",
        "The new version fixes every issue I had. Excellent work.",
        "Revenue declined 12% year over year in the third quarter."
    );

    static final List<String> TOXICITY_TEXTS = List.of(
        "I disagree with the proposed changes to the codebase.",
        "This is the worst code I have ever seen in my life.",
        "Can someone explain why this test is failing on CI?",
        "Anyone who writes code like this should be fired immediately."
    );

    public static List<ScoredText> runSentiment(TextClassifier classifier) {
        return classify(classifier, SENTIMENT_TEXTS);
    }

    public static List<ScoredText> runToxicity(TextClassifier classifier) {
        return classify(classifier, TOXICITY_TEXTS);
    }

    private static List<ScoredText> classify(TextClassifier classifier, List<String> texts) {
        List<ScoredText> results = new ArrayList<>();
        for (String text : texts) {
            ClassificationResult cr = classifier.classify(text);
            results.add(new ScoredText(text, cr.label(), cr.confidence()));
        }
        return results;
    }

    public static void main(String[] args) {
        Path sentimentDir = Path.of("target/models/distilbert-sst2");
        try (InferenceModel model = new OnnxInferenceModel(
                new ModelConfig(sentimentDir.resolve("model.onnx"), sentimentDir.resolve("tokenizer.json")))) {
            var classifier = new TextClassifier(model, List.of("negative", "positive"));

            System.out.println("=== Sentiment Scoring ===");
            printResults(runSentiment(classifier));
            System.out.println("\nNote: runToxicity() requires a separate toxicity model (not bundled).");
        }
    }

    static void printResults(List<ScoredText> results) {
        System.out.printf("%-70s %-12s %6s%n", "Text", "Label", "Score");
        System.out.println("-".repeat(92));
        for (ScoredText r : results) {
            System.out.printf("%-70s %-12s %6.3f%n",
                truncate(r.text(), 68), r.label(), r.score());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
