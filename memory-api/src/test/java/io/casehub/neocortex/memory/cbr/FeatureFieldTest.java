package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FeatureFieldTest {

    @Test
    void categorical() {
        var f = FeatureField.categorical("race");
        assertThat(f).isInstanceOf(FeatureField.Categorical.class);
        assertThat(f.name()).isEqualTo("race");
    }

    @Test
    void numeric() {
        var f = FeatureField.numeric("ratio", 0.0, 3.0);
        assertThat(f).isInstanceOf(FeatureField.Numeric.class);
        var n = (FeatureField.Numeric) f;
        assertThat(n.min()).isEqualTo(0.0);
        assertThat(n.max()).isEqualTo(3.0);
    }

    @Test
    void numeric_minGreaterThanMax() {
        assertThatThrownBy(() -> FeatureField.numeric("x", 5.0, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void text() {
        var f = FeatureField.text("description");
        assertThat(f).isInstanceOf(FeatureField.Text.class);
    }

    @Test
    void nullNameRejected() {
        assertThatThrownBy(() -> FeatureField.categorical(null))
            .isInstanceOf(NullPointerException.class);
    }
}
