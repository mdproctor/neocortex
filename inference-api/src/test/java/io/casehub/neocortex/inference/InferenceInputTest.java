package io.casehub.neocortex.inference;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class InferenceInputTest {

    // --- factory: of(String) ---

    @Test
    void of_single_text() {
        var input = InferenceInput.of("hello");
        assertThat(input.texts()).containsExactly("hello");
    }

    @Test
    void of_null_text_throws() {
        assertThatNullPointerException()
            .isThrownBy(() -> InferenceInput.of(null))
            .withMessageContaining("null");
    }

    // --- factory: pair(String, String) ---

    @Test
    void pair_two_texts() {
        var input = InferenceInput.pair("premise", "hypothesis");
        assertThat(input.texts()).containsExactly("premise", "hypothesis");
    }

    @Test
    void pair_null_first_throws() {
        assertThatNullPointerException()
            .isThrownBy(() -> InferenceInput.pair(null, "second"))
            .withMessageContaining("first");
    }

    @Test
    void pair_null_second_throws() {
        assertThatNullPointerException()
            .isThrownBy(() -> InferenceInput.pair("first", null))
            .withMessageContaining("second");
    }

    // --- validation: empty and oversized ---

    @Test
    void null_list_throws() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InferenceInput(null))
            .withMessageContaining("must not be empty");
    }

    @Test
    void empty_list_throws() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InferenceInput(List.of()))
            .withMessageContaining("must not be empty");
    }

    @Test
    void three_texts_throws() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new InferenceInput(List.of("a", "b", "c")))
            .withMessageContaining("at most 2");
    }

    // --- defensive copy ---

    @Test
    void constructor_makes_defensive_copy() {
        var mutable = new ArrayList<>(List.of("original"));
        var input = new InferenceInput(mutable);
        mutable.set(0, "mutated");
        assertThat(input.texts()).containsExactly("original");
    }

    @Test
    void texts_returns_unmodifiable_list() {
        var input = InferenceInput.of("hello");
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> input.texts().add("extra"));
    }
}
