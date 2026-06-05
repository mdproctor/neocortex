package io.casehub.inference.quarkus.gate;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusMainIntegrationTest
public class NativeImageGateIT {

    @Test
    @Launch(value = {"target/test-models/nli-deberta-v3-xsmall"}, exitCode = 0)
    void nativeImageGatePasses(LaunchResult result) {
        assertThat(result.getOutput())
            .contains("PASS: DJL Tokenizer JNI loaded and executed")
            .contains("PASS: ONNX Runtime JNI loaded and session created")
            .contains("PASS: End-to-end inference completed");
    }
}
