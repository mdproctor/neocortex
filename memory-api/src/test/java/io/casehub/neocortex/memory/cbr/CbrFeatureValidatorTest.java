package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CbrFeatureValidatorTest {

    private static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("test",
        FeatureField.categorical("posture"),
        FeatureField.numeric("score", 0, 100),
        FeatureField.text("description"),
        FeatureField.categoricalList("phases"),
        FeatureField.nestedObject("economy",
            FeatureField.numeric("minute_3", 0, 100),
            FeatureField.categorical("tier")),
        FeatureField.objectList("moments",
            FeatureField.categorical("type"),
            FeatureField.numeric("minute", 0, 90)));

    // --- validateStoreFeatures ---

    @Test
    void validateStoreFeatures_validFlatFeatures() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("posture", "AGGRESSIVE", "score", 85), SCHEMA);
    }

    @Test
    void validateStoreFeatures_validCategoricalList() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", List.of("EARLY", "MID")), SCHEMA);
    }

    @Test
    void validateStoreFeatures_categoricalListRequiresList() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", "not_a_list"), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateStoreFeatures_categoricalListRequiresStringElements() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", List.of(1, 2, 3)), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateStoreFeatures_validNestedObject() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("economy", Map.of("minute_3", 45, "tier", "gold")), SCHEMA);
    }

    @Test
    void validateStoreFeatures_nestedObjectRequiresMap() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("economy", "not_a_map"), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateStoreFeatures_validObjectList() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("moments", List.of(
                Map.of("type", "FIRST_CONTACT", "minute", 3.2),
                Map.of("type", "BATTLE_WON", "minute", 5.1))), SCHEMA);
    }

    @Test
    void validateStoreFeatures_objectListRequiresListOfMaps() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("moments", List.of("not", "maps")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateStoreFeatures_unknownFieldsIgnored() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("unknown_field", "any_value"), SCHEMA);
    }

    // --- validateQueryFeatures ---

    @Test
    void validateQueryFeatures_rejectsStructuredFieldInFeatures() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
            Map.of("phases", List.of("A")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be queried via filters");
    }

    @Test
    void validateQueryFeatures_rejectsNestedObjectInFeatures() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
            Map.of("economy", Map.of("x", 1)), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be queried via filters");
    }

    @Test
    void validateQueryFeatures_rejectsObjectListInFeatures() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
            Map.of("moments", List.of(Map.of("x", "y"))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be queried via filters");
    }

    @Test
    void validateQueryFeatures_acceptsFlatFeatures() {
        CbrFeatureValidator.validateQueryFeatures(
            Map.of("posture", "AGGRESSIVE"), SCHEMA);
    }

    // --- validateFilters ---

    @Test
    void validateFilters_containsOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.contains("A")), SCHEMA);
    }

    @Test
    void validateFilters_containsAllOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.containsAll(List.of("A", "B"))), SCHEMA);
    }

    @Test
    void validateFilters_containsAnyOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.containsAny(List.of("A", "B"))), SCHEMA);
    }

    @Test
    void validateFilters_containsOnNonListFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("posture", CbrFilter.contains("A")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateFilters_hasMatchOnObjectList() {
        CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("type", "X"))), SCHEMA);
    }

    @Test
    void validateFilters_hasMatchOnNestedObject() {
        CbrFeatureValidator.validateFilters(
            Map.of("economy", CbrFilter.hasMatch(Map.of("tier", "gold"))), SCHEMA);
    }

    @Test
    void validateFilters_hasMatchOnCategoricalListRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.hasMatch(Map.of("x", "y"))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateFilters_unknownFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("nonexistent", CbrFilter.contains("A")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateFilters_hasMatchUnknownSubFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("nonexistent", "val"))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found in inner schema");
    }

    @Test
    void validateFilters_hasMatchWrongSubFieldTypeRejected_numericFieldGotString() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("minute", "not_a_number"))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires Number or NumericRange");
    }

    @Test
    void validateFilters_hasMatchWrongSubFieldTypeRejected_categoricalFieldGotNumber() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("economy", CbrFilter.hasMatch(Map.of("tier", 42))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires String");
    }

    @Test
    void validateFilters_hasMatchAcceptsNumericRange() {
        CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("minute", NumericRange.of(0, 10)))), SCHEMA);
    }
}
