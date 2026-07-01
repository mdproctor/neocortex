package io.casehub.neocortex.examples.analysis;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.casehub.neocortex.inference.tasks.NliClassifier;
import io.casehub.neocortex.inference.tasks.NliResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NliDemo {

    public record Claim(String domain, String premise, String hypothesis) {}

    public record Result(String domain, String premise, String hypothesis, NliResult result) {}

    static final List<Claim> CLAIMS = List.of(
        new Claim("tech",  "CDI performs dependency injection at runtime using reflection.",
                           "CDI uses compile-time injection."),
        new Claim("tech",  "Quarkus supports both imperative and reactive programming models.",
                           "Quarkus is a framework for building Java applications."),
        new Claim("news",  "Interest rates were held steady at the latest policy meeting.",
                           "The central bank raised interest rates."),
        new Claim("news",  "Global temperatures rose by 1.2 degrees in 2025.",
                           "Climate change is accelerating."),
        new Claim("legal", "Early termination requires 90 days written notice.",
                           "The tenant may terminate the lease at will."),
        new Claim("legal", "The data processor shall implement appropriate technical measures.",
                           "Data protection requires security safeguards.")
    );

    public static List<Result> run(NliClassifier nli) {
        List<Result> results = new ArrayList<>();
        for (Claim claim : CLAIMS) {
            NliResult nliResult = nli.classify(claim.premise(), claim.hypothesis());
            results.add(new Result(claim.domain(), claim.premise(), claim.hypothesis(), nliResult));
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
        System.out.printf("%-6s %-55s %-55s %-14s %6s %6s %6s%n",
            "Domain", "Premise", "Hypothesis", "Predicted", "Ent", "Neu", "Con");
        System.out.println("-".repeat(200));
        for (Result r : results) {
            System.out.printf("%-6s %-55s %-55s %-14s %6.3f %6.3f %6.3f%n",
                r.domain(),
                truncate(r.premise(), 53),
                truncate(r.hypothesis(), 53),
                r.result().predicted(),
                r.result().entailment(),
                r.result().neutral(),
                r.result().contradiction());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
