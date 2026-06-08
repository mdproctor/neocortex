package io.casehub.inference.quarkus;

import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class InferenceModelProducerTest {

    @Inject
    @Inference("nli")
    InferenceModel nliModel;

    @Test
    void injectsConfiguredModel() {
        assertThat(nliModel).isNotNull();
    }

    @Test
    void injectedModelProducesOutput() {
        InferenceOutput output = nliModel.run(
            InferenceInput.pair("The cat is on the mat.", "The animal is on the mat."));
        assertThat(output.values()).hasSize(3);
    }
}
