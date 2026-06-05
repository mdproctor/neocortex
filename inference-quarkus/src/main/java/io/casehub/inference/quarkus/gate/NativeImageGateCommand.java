package io.casehub.inference.quarkus.gate;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.casehub.inference.runtime.OnnxSessionLoader;
import io.casehub.inference.runtime.RawInference;
import io.casehub.inference.runtime.TokenizerLoader;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.nio.file.Path;

@QuarkusMain
public class NativeImageGateCommand implements QuarkusApplication {

    @Override
    public int run(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("FAIL: model directory argument required");
            return 1;
        }
        Path modelDir = Path.of(args[0]);
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        Path modelPath = modelDir.resolve("model.onnx");

        // Phase 1 — DJL Tokenizer JNI (independent)
        HuggingFaceTokenizer tokenizer;
        try {
            tokenizer = TokenizerLoader.load(tokenizerPath);
            Encoding encoding = tokenizer.encode("hello world");
            if (encoding.getIds().length == 0) {
                System.err.println("FAIL: DJL Tokenizer JNI — tokenization returned empty IDs");
                return 1;
            }
            System.out.println("PASS: DJL Tokenizer JNI loaded and executed");
        } catch (Exception e) {
            System.err.println("FAIL: DJL Tokenizer JNI — " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }

        // Phase 2 — ONNX Runtime JNI (independent)
        OrtEnvironment env;
        OrtSession session;
        try {
            env = OnnxSessionLoader.createEnvironment();
            session = OnnxSessionLoader.createSession(env, modelPath);
            System.out.println("Model inputs: " + session.getInputNames());
            System.out.println("Model outputs: " + session.getOutputNames());
            System.out.println("PASS: ONNX Runtime JNI loaded and session created");
        } catch (Exception e) {
            System.err.println("FAIL: ONNX Runtime JNI — " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }

        // Phase 3 — End-to-end inference
        try {
            float[] logits = RawInference.classifyPair(
                env, session, tokenizer,
                "The weather is sunny", "It is raining"
            );
            System.out.printf("Logits: contradiction=%.4f, entailment=%.4f, neutral=%.4f%n",
                logits[0], logits[1], logits[2]);
            if (logits[0] <= logits[1] || logits[0] <= logits[2]) {
                System.err.println("FAIL: End-to-end inference — contradiction score not highest");
                return 1;
            }
            System.out.println("PASS: End-to-end inference completed");
        } catch (Exception e) {
            System.err.println("FAIL: End-to-end inference — " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        } finally {
            session.close();
            env.close();
            tokenizer.close();
        }

        return 0;
    }
}
