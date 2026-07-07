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

    @Test
    void textDefaultIsNotSemantic() {
        var f = FeatureField.text("desc");
        assertThat(f).isInstanceOf(FeatureField.Text.class);
        assertThat(((FeatureField.Text) f).semantic()).isFalse();
    }

    @Test
    void semanticTextIsSemantic() {
        var f = FeatureField.semanticText("desc");
        assertThat(f).isInstanceOf(FeatureField.Text.class);
        assertThat(((FeatureField.Text) f).semantic()).isTrue();
    }

    @Test
    void textAndSemanticTextWithSameNameAreNotEqual() {
        var exact = FeatureField.text("desc");
        var semantic = FeatureField.semanticText("desc");
        assertThat(exact).isNotEqualTo(semantic);
    }

    // --- SimilaritySpec on Categorical ---
    @Test
    void categorical_withCategoricalTable() {
        var table = SimilaritySpec.categoricalTableBuilder().add("a", "b", 0.5).build();
        var f = FeatureField.categorical("type", table);
        assertThat(f).isInstanceOf(FeatureField.Categorical.class);
        assertThat(((FeatureField.Categorical) f).similaritySpec()).isEqualTo(table);
    }

    @Test
    void categorical_withGaussianDecay_throws() {
        assertThatThrownBy(() -> FeatureField.categorical("type", new SimilaritySpec.GaussianDecay(0.5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categorical_withNull_accepted() {
        var f = new FeatureField.Categorical("type", null);
        assertThat(f.similaritySpec()).isNull();
    }

    @Test
    void categorical_backwardCompat_noSpec() {
        var f = FeatureField.categorical("type");
        assertThat(((FeatureField.Categorical) f).similaritySpec()).isNull();
    }

    // --- SimilaritySpec on Numeric ---
    @Test
    void numeric_withGaussianDecay() {
        var f = FeatureField.numeric("score", 0, 100, new SimilaritySpec.GaussianDecay(0.3));
        assertThat(((FeatureField.Numeric) f).similaritySpec())
            .isEqualTo(new SimilaritySpec.GaussianDecay(0.3));
    }

    @Test
    void numeric_withStepDecay() {
        var f = FeatureField.numeric("score", 0, 100, new SimilaritySpec.StepDecay(0.1));
        assertThat(((FeatureField.Numeric) f).similaritySpec())
            .isInstanceOf(SimilaritySpec.StepDecay.class);
    }

    @Test
    void numeric_withExponentialDecay() {
        var f = FeatureField.numeric("score", 0, 100, new SimilaritySpec.ExponentialDecay(2.0));
        assertThat(((FeatureField.Numeric) f).similaritySpec())
            .isInstanceOf(SimilaritySpec.ExponentialDecay.class);
    }

    @Test
    void numeric_withCategoricalTable_throws() {
        var table = SimilaritySpec.categoricalTableBuilder().add("a", "b", 0.5).build();
        assertThatThrownBy(() -> FeatureField.numeric("score", 0, 100, table))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numeric_backwardCompat_noSpec() {
        var f = FeatureField.numeric("score", 0, 100);
        assertThat(((FeatureField.Numeric) f).similaritySpec()).isNull();
    }

    // --- Text unchanged ---
    @Test
    void text_noSimilaritySpec() {
        var f = FeatureField.text("desc");
        assertThat(f).isInstanceOf(FeatureField.Text.class);
    }

    // --- Value semantics preserved ---
    @Test
    void categorical_withSpec_equals() {
        var table = SimilaritySpec.categoricalTableBuilder().add("a", "b", 0.5).build();
        var f1 = FeatureField.categorical("type", table);
        var f2 = FeatureField.categorical("type", table);
        assertThat(f1).isEqualTo(f2);
    }

    @Test
    void numeric_withSpec_equals() {
        var f1 = FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.3));
        var f2 = FeatureField.numeric("s", 0, 100, new SimilaritySpec.GaussianDecay(0.3));
        assertThat(f1).isEqualTo(f2);
    }
}
