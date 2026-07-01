package io.casehub.neocortex.inference.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SoftmaxTest {

    @Test
    @DisplayName("standard logits produce valid probabilities summing to 1")
    void standardLogits() {
        float[] result = Softmax.apply(new float[]{2.0f, 1.0f, 0.5f});
        assertThat(result).hasSize(3);
        float sum = result[0] + result[1] + result[2];
        assertThat(sum).isCloseTo(1.0f, within(1e-6f));
        assertThat(result[0]).isGreaterThan(result[1]);
        assertThat(result[1]).isGreaterThan(result[2]);
    }

    @Test
    @DisplayName("large logits do not overflow — numeric stability")
    void numericStability() {
        float[] result = Softmax.apply(new float[]{1000f, 1001f, 1002f});
        assertThat(result).hasSize(3);
        float sum = result[0] + result[1] + result[2];
        assertThat(sum).isCloseTo(1.0f, within(1e-6f));
        for (float v : result) {
            assertThat(v).isFinite();
            assertThat(v).isGreaterThan(0f);
        }
    }

    @Test
    @DisplayName("single element produces [1.0]")
    void singleElement() {
        float[] result = Softmax.apply(new float[]{42.0f});
        assertThat(result).containsExactly(1.0f);
    }

    @Test
    @DisplayName("uniform input produces uniform distribution")
    void uniformInput() {
        float[] result = Softmax.apply(new float[]{1.0f, 1.0f, 1.0f});
        assertThat(result[0]).isCloseTo(1.0f / 3, within(1e-6f));
        assertThat(result[1]).isCloseTo(1.0f / 3, within(1e-6f));
        assertThat(result[2]).isCloseTo(1.0f / 3, within(1e-6f));
    }

    @Test
    @DisplayName("does not mutate input array")
    void doesNotMutateInput() {
        float[] input = {1.0f, 2.0f, 3.0f};
        float[] copy = input.clone();
        Softmax.apply(input);
        assertThat(input).containsExactly(copy);
    }
}
