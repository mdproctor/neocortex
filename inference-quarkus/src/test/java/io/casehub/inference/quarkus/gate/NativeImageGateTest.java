package io.casehub.inference.quarkus.gate;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JVM mode test — directly invokes NativeImageGateCommand#run().
 * NOT using @QuarkusMainTest because we just need to verify the JNI libraries load.
 */
public class NativeImageGateTest {

    @Test
    void nativeImageGatePassesInJvmMode() throws Exception {
        Path modelDir = Path.of("target/test-models/nli-deberta-v3-xsmall").toAbsolutePath();

        // Capture stdout
        ByteArrayOutputStream outCapture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outCapture));

        NativeImageGateCommand command = new NativeImageGateCommand();
        int exitCode;
        try {
            exitCode = command.run(modelDir.toString());
        } finally {
            System.setOut(originalOut);
        }

        String output = outCapture.toString();
        System.out.println(output);  // Print to console for debugging

        assertThat(exitCode).isEqualTo(0);
        assertThat(output)
            .contains("PASS: ONNX Runtime JNI + DJL Tokenizer JNI loaded")
            .contains("PASS: End-to-end inference completed");
    }
}
