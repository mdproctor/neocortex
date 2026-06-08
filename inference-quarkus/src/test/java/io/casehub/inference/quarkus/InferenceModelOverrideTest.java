package io.casehub.inference.quarkus;

import io.casehub.inference.InferenceInput;
import io.casehub.inference.InferenceModel;
import io.casehub.inference.InferenceOutput;
import io.casehub.inference.inmem.InMemoryInferenceModel;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@QuarkusTest
@TestProfile(InferenceModelOverrideTest.StubProfile.class)
class InferenceModelOverrideTest {

    @Inject
    @Inference("nli")
    InferenceModel nliModel;

    @Test
    void stubModelOverridesProduction() {
        InferenceOutput output = nliModel.run(
            InferenceInput.pair("premise", "hypothesis"));
        assertThat(output.values()).hasSize(3);
        assertThat(output.values()[0]).isCloseTo(0.1f, within(1e-6f));
        assertThat(output.values()[1]).isCloseTo(0.2f, within(1e-6f));
        assertThat(output.values()[2]).isCloseTo(0.7f, within(1e-6f));
    }

    public static class StubProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(StubProducer.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class StubProducer {

        @Produces
        @Inference("")
        InferenceModel produce(InjectionPoint ip) {
            return InMemoryInferenceModel.returning(0.1f, 0.2f, 0.7f);
        }
    }
}
