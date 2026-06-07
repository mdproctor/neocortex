package io.casehub.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InferenceOutputTest {

    // --- null rejection ---

    @Test
    void null_values_throws() {
        assertThatNullPointerException()
            .isThrownBy(() -> new InferenceOutput(null))
            .withMessageContaining("null");
    }

    // --- defensive copy on construction ---

    @Test
    void constructor_clones_array() {
        float[] original = {1.0f, 2.0f, 3.0f};
        var output = new InferenceOutput(original);
        original[0] = 99.0f;
        assertThat(output.values()[0]).isEqualTo(1.0f);
    }

    // --- defensive copy on access ---

    @Test
    void values_returns_clone() {
        var output = new InferenceOutput(new float[]{1.0f, 2.0f});
        float[] first = output.values();
        float[] second = output.values();
        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);

        first[0] = 99.0f;
        assertThat(output.values()[0]).isEqualTo(1.0f);
    }

    // --- equality and hashCode ---

    @Test
    void equal_values_are_equal() {
        var a = new InferenceOutput(new float[]{1.0f, 2.0f});
        var b = new InferenceOutput(new float[]{1.0f, 2.0f});
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void different_values_are_not_equal() {
        var a = new InferenceOutput(new float[]{1.0f, 2.0f});
        var b = new InferenceOutput(new float[]{1.0f, 3.0f});
        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void toString_short_array() {
        var output = new InferenceOutput(new float[]{0.1f, 0.9f});
        assertThat(output.toString()).contains("0.1").contains("0.9");
    }

    @Test
    void toString_truncates_long_array() {
        var output = new InferenceOutput(new float[]{1, 2, 3, 4, 5, 6, 7, 8});
        String s = output.toString();
        // Should show first 3 values and total count, not all 8
        assertThat(s).contains("8 values");
        assertThat(s).contains("...");
    }

    @Test
    void toString_five_elements_not_truncated() {
        var output = new InferenceOutput(new float[]{1, 2, 3, 4, 5});
        String s = output.toString();
        assertThat(s).doesNotContain("...");
    }
}
