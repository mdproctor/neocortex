package io.casehub.neocortex.inference.quarkus.gate;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceOutput;
import io.casehub.neocortex.inference.runtime.ModelConfig;
import io.casehub.neocortex.inference.runtime.OnnxInferenceModel;
import io.quarkus.runtime.QuarkusApplication;
import java.nio.file.Path;

public class NativeImageGateCommand implements QuarkusApplication {

    @Override
    public int run(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("FAIL: model directory argument required");
            return 1;
        }
        Path modelDir = Path.of(args[0]);

        try (var model = new OnnxInferenceModel(new ModelConfig(
                modelDir.resolve("model.onnx"),
                modelDir.resolve("tokenizer.json")))) {

            System.out.println("PASS: ONNX Runtime JNI + DJL Tokenizer JNI loaded");
            System.out.println("Output size: " + model.outputSize());

            InferenceOutput output = model.run(InferenceInput.pair(
                "The weather is sunny", "It is raining"));
            float[] logits = output.values();

            System.out.printf("Logits: contradiction=%.4f, entailment=%.4f, neutral=%.4f%n",
                logits[0], logits[1], logits[2]);

            if (logits[0] <= logits[1] || logits[0] <= logits[2]) {
                System.err.println("FAIL: End-to-end inference — contradiction score not highest");
                return 1;
            }
            System.out.println("PASS: End-to-end inference completed");
        } catch (Exception e) {
            System.err.println("FAIL: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }

        return 0;
    }
}
