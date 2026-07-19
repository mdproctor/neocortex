package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrFeatureSchemaTest {

    @Test
    void of_createsSchema() {
        var schema = CbrFeatureSchema.of("starcraft-game",
            FeatureField.categorical("race"),
            FeatureField.numeric("ratio", 0.0, 3.0));
        assertThat(schema.caseType()).isEqualTo("starcraft-game");
        assertThat(schema.fields()).hasSize(2);
    }

    @Test
    void nullCaseTypeRejected() {
        assertThatThrownBy(() -> CbrFeatureSchema.of(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankCaseTypeRejected() {
        assertThatThrownBy(() -> CbrFeatureSchema.of("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldsDefensivelyCopied() {
        var fields = new java.util.ArrayList<FeatureField>();
        fields.add(FeatureField.categorical("race"));
        var schema = new CbrFeatureSchema("type", fields);
        fields.add(FeatureField.text("extra"));
        assertThat(schema.fields()).hasSize(1);
    }

    @Test
    void duplicateFieldNamesRejected() {
        assertThatThrownBy(() -> CbrFeatureSchema.of("test",
                                                     FeatureField.categorical("name"),
                                                     FeatureField.numeric("name", 0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate field name: 'name'");
    }

    @Test
    void duplicateFieldNamesAcrossTypes() {
        assertThatThrownBy(() -> CbrFeatureSchema.of("test",
                                                     FeatureField.categorical("field"),
                                                     FeatureField.categoricalList("field")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate field name");
    }

    @Test
    void learningRate_defaultsToNull() {
        var schema = CbrFeatureSchema.of("test", FeatureField.categorical("cat"));
        assertThat(schema.learningRate()).isNull();
    }

    @Test
    void learningRate_customValue() {
        var schema = new CbrFeatureSchema("test",
                                          java.util.List.of(FeatureField.categorical("cat")), 0.5);
        assertThat(schema.learningRate()).isEqualTo(0.5);
    }

    @Test
    void learningRate_rejectsOutOfRange() {
        assertThatThrownBy(() -> new CbrFeatureSchema("test",
                                                      java.util.List.of(FeatureField.categorical("cat")), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CbrFeatureSchema("test",
                                                      java.util.List.of(FeatureField.categorical("cat")), -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
