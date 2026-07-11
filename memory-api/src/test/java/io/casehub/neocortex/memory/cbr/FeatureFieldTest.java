package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // --- CategoricalList ---
    @Test
    void categoricalList_validName() {
        var f = FeatureField.categoricalList("phases");
        assertThat(f).isInstanceOf(FeatureField.CategoricalList.class);
        assertThat(f.name()).isEqualTo("phases");
    }

    @Test
    void categoricalList_nullNameRejected() {
        assertThatThrownBy(() -> FeatureField.categoricalList(null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- NestedObject ---
    @Test
    void nestedObject_validConstruction() {
        var f = FeatureField.nestedObject("economy",
                                          FeatureField.numeric("minute_3", 0, 100),
                                          FeatureField.categorical("tier"));
        assertThat(f).isInstanceOf(FeatureField.NestedObject.class);
        assertThat(f.name()).isEqualTo("economy");
        assertThat(((FeatureField.NestedObject) f).innerFields()).hasSize(2);
    }

    @Test
    void nestedObject_rejectsNestedInnerFields_categoricalList() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.categoricalList("nested_list")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CategoricalList");
    }

    @Test
    void nestedObject_rejectsNestedInnerFields_nestedObject() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.nestedObject("inner", FeatureField.categorical("c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NestedObject");
    }

    @Test
    void nestedObject_rejectsNestedInnerFields_objectList() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.objectList("inner", FeatureField.categorical("c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ObjectList");
    }

    @Test
    void nestedObject_rejectsSimilaritySpecOnInnerCategorical() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.categorical("cat", SimilaritySpec.categoricalTableBuilder()
                                                                                                         .add("A", "B", 0.5).build())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SimilaritySpec not supported");
    }

    @Test
    void nestedObject_rejectsSimilaritySpecOnInnerNumeric() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.numeric("num", 0, 100, new SimilaritySpec.GaussianDecay(0.3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SimilaritySpec not supported");
    }

    @Test
    void nestedObject_rejectsSemanticInnerText() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.semanticText("desc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic matching not supported");
    }

    @Test
    void nestedObject_rejectsDuplicateInnerFieldNames() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.categorical("name"),
                                                           FeatureField.numeric("name", 0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate inner field name");
    }

    @Test
    void nestedObject_innerFieldsDefensivelyCopied() {
        var inner = new java.util.ArrayList<>(java.util.List.of(
                FeatureField.categorical("type")));
        var f = new FeatureField.NestedObject("moments", inner);
        inner.add(FeatureField.numeric("extra", 0, 10));
        assertThat(f.innerFields()).hasSize(1);
    }

    // --- ObjectList ---
    @Test
    void objectList_validConstruction() {
        var f = FeatureField.objectList("moments",
                                        FeatureField.categorical("type"),
                                        FeatureField.numeric("minute", 0, 90));
        assertThat(f).isInstanceOf(FeatureField.ObjectList.class);
        assertThat(f.name()).isEqualTo("moments");
        assertThat(((FeatureField.ObjectList) f).innerFields()).hasSize(2);
    }

    @Test
    void objectList_rejectsNestedInnerFields() {
        assertThatThrownBy(() -> FeatureField.objectList("bad",
                                                         FeatureField.nestedObject("inner", FeatureField.categorical("c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NestedObject");
    }

    @Test
    void objectList_rejectsDuplicateInnerFieldNames() {
        assertThatThrownBy(() -> FeatureField.objectList("bad",
                                                         FeatureField.categorical("x"),
                                                         FeatureField.text("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate inner field name");
    }

    // --- TimeSeries ---
    @Test
    void timeSeries_validConstruction() {
        var f = FeatureField.timeSeries("economyCurve", "minute",
                                        FeatureField.numeric("minute", 0, 30),
                                        FeatureField.numeric("economy", 0, 500),
                                        FeatureField.categorical("posture"));
        assertThat(f).isInstanceOf(FeatureField.TimeSeries.class);
        assertThat(f.name()).isEqualTo("economyCurve");
        var ts = (FeatureField.TimeSeries) f;
        assertThat(ts.innerFields()).hasSize(3);
        assertThat(ts.timestampField()).isEqualTo("minute");
    }

    @Test
    void timeSeries_timestampFieldMustExist() {
        assertThatThrownBy(() -> FeatureField.timeSeries("ts", "nonexistent",
                                                         FeatureField.numeric("minute", 0, 30),
                                                         FeatureField.numeric("val", 0, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestampField");
    }

    @Test
    void timeSeries_timestampFieldMustBeNumeric() {
        assertThatThrownBy(() -> FeatureField.timeSeries("ts", "phase",
                                                         FeatureField.categorical("phase"),
                                                         FeatureField.numeric("val", 0, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Numeric");
    }

    @Test
    void timeSeries_requiresNonTimestampNumericField() {
        assertThatThrownBy(() -> FeatureField.timeSeries("ts", "minute",
                                                         FeatureField.numeric("minute", 0, 30),
                                                         FeatureField.categorical("phase")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-timestamp Numeric");
    }

    @Test
    void timeSeries_rejectsEmptyInnerFields() {
        assertThatThrownBy(() -> FeatureField.timeSeries("ts", "minute"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeSeries_nullNameRejected() {
        assertThatThrownBy(() -> FeatureField.timeSeries(null, "minute",
                                                         FeatureField.numeric("minute", 0, 30),
                                                         FeatureField.numeric("val", 0, 100)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void timeSeries_innerFieldsDefensivelyCopied() {
        var inner = new java.util.ArrayList<>(java.util.List.of(
                FeatureField.numeric("minute", 0, 30),
                FeatureField.numeric("val", 0, 100)));
        var f = new FeatureField.TimeSeries("ts", inner, "minute");
        inner.add(FeatureField.numeric("extra", 0, 10));
        assertThat(((FeatureField.TimeSeries) f).innerFields()).hasSize(2);
    }

    // --- DiscreteSequence ---
    @Test
    void discreteSequence_validConstruction() {
        var f = FeatureField.discreteSequence("phases");
        assertThat(f).isInstanceOf(FeatureField.DiscreteSequence.class);
        assertThat(f.name()).isEqualTo("phases");
    }

    @Test
    void discreteSequence_nullNameRejected() {
        assertThatThrownBy(() -> FeatureField.discreteSequence(null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- Temporal types rejected as inner fields ---
    @Test
    void nestedObject_rejectsTimeSeries() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.timeSeries("inner", "t",
                                                                                   FeatureField.numeric("t", 0, 10),
                                                                                   FeatureField.numeric("v", 0, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TimeSeries");
    }

    @Test
    void nestedObject_rejectsDiscreteSequence() {
        assertThatThrownBy(() -> FeatureField.nestedObject("bad",
                                                           FeatureField.discreteSequence("seq")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DiscreteSequence");
    }

    @Test
    void objectList_rejectsTimeSeries() {
        assertThatThrownBy(() -> FeatureField.objectList("bad",
                                                         FeatureField.timeSeries("inner", "t",
                                                                                 FeatureField.numeric("t", 0, 10),
                                                                                 FeatureField.numeric("v", 0, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TimeSeries");
    }

    @Test
    void timeSeries_rejectsTemporalInnerFields() {
        assertThatThrownBy(() -> FeatureField.timeSeries("outer", "t",
                                                         FeatureField.numeric("t", 0, 10),
                                                         FeatureField.numeric("v", 0, 100),
                                                         FeatureField.discreteSequence("nested")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DiscreteSequence");
    }
}
