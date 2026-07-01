package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

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
}
