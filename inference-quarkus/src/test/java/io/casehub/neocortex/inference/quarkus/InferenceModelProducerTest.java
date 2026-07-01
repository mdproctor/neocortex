package io.casehub.neocortex.inference.quarkus;

import io.casehub.neocortex.inference.InferenceInput;
import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.InferenceOutput;

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
