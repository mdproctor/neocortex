package io.casehub.neocortex.memory.cbr.qdrant;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.MemoryDomain;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrQueryTranslatorTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    private final CbrFeatureSchema schema = CbrFeatureSchema.of("starcraft-game",
        FeatureField.categorical("opponent_race"),
        FeatureField.numeric("army_size_ratio", 0.0, 3.0),
        FeatureField.text("notes"));

    @Test
    void toFilter_includesBaseMustConditions() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game", Map.of(), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        assertThat(filter.getMustCount()).isEqualTo(3);
        assertKeywordCondition(filter.getMust(0), "tenantId", "tenant-1");
        assertKeywordCondition(filter.getMust(1), "domain", "cbr");
        assertKeywordCondition(filter.getMust(2), "caseType", "starcraft-game");
    }

    @Test
    void toFilter_categoricalFeatureAddsKeywordMatch() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        // 3 base + 1 feature = 4
        assertThat(filter.getMustCount()).isEqualTo(4);
        assertKeywordCondition(filter.getMust(3), "f_opponent_race", "Zerg");
    }

    @Test
    void toFilter_unknownFieldsIgnored() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("unknown_field", "value"), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        // Only base conditions
        assertThat(filter.getMustCount()).isEqualTo(3);
    }

    @Test
    void toFilter_numericFeatureAddsRangeMatch() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("army_size_ratio", 0.7), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        assertThat(filter.getMustCount()).isEqualTo(4);
        Condition numericCondition = filter.getMust(3);
        assertThat(numericCondition.getField().getKey()).isEqualTo("f_army_size_ratio");
    }

    @Test
    void validateQueryFeatures_categoricalRequiresString() {
        assertThatThrownBy(() -> CbrQueryTranslator.validateQueryFeatures(
            Map.of("opponent_race", 42), schema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Categorical")
            .hasMessageContaining("String");
    }

    @Test
    void validateQueryFeatures_numericRequiresNumber() {
        assertThatThrownBy(() -> CbrQueryTranslator.validateQueryFeatures(
            Map.of("army_size_ratio", "high"), schema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Numeric")
            .hasMessageContaining("Number");
    }

    @Test
    void validateQueryFeatures_textRequiresString() {
        assertThatThrownBy(() -> CbrQueryTranslator.validateQueryFeatures(
            Map.of("notes", 123), schema))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Text")
            .hasMessageContaining("String");
    }

    @Test
    void validateQueryFeatures_unknownFieldsDoNotThrow() {
        // Should not throw
        CbrQueryTranslator.validateQueryFeatures(
            Map.of("totally_unknown", "anything"), schema);
    }

    @Test
    void toFilter_notBefore_addsStoredAtRangeCondition() {
        Instant notBefore = Instant.parse("2025-01-01T00:00:00Z");
        var query = new CbrQuery("tenant-1", CBR, "starcraft-game",
            Map.of(), Map.of(), Map.of(), 5, 0.0, notBefore, null, 0.5,
            RetrievalMode.HYBRID, FusionStrategy.RRF);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        assertThat(filter.getMustCount()).isEqualTo(4);
        Condition storedAtCondition = filter.getMust(3);
        assertThat(storedAtCondition.getField().getKey()).isEqualTo("_stored_at");
    }

    @Test
    void toFilter_numericRange_addsRangeCondition() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("army_size_ratio", NumericRange.within(0.7, 0.1)), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        assertThat(filter.getMustCount()).isEqualTo(4);
        Condition rangeCondition = filter.getMust(3);
        assertThat(rangeCondition.getField().getKey()).isEqualTo("f_army_size_ratio");
    }

    @Test
    void validateQueryFeatures_numericAcceptsNumericRange() {
        CbrQueryTranslator.validateQueryFeatures(
            Map.of("army_size_ratio", NumericRange.of(0.5, 1.0)), schema);
    }

    @Test
    void toFilter_multipleFeatures() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.5), 5);
        Filter filter = CbrQueryTranslator.toFilter(query, schema);

        // 3 base + 2 features = 5
        assertThat(filter.getMustCount()).isEqualTo(5);
    }

    @Test
    void toIdentityFilter_excludesFeatures() {
        var query = CbrQuery.of("tenant-1", CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.7), 5);
        Filter filter = CbrQueryTranslator.toIdentityFilter(query);

        // Only 3 identity conditions — no feature filters
        assertThat(filter.getMustCount()).isEqualTo(3);
        assertKeywordCondition(filter.getMust(0), "tenantId", "tenant-1");
        assertKeywordCondition(filter.getMust(1), "domain", "cbr");
        assertKeywordCondition(filter.getMust(2), "caseType", "starcraft-game");
    }

    @Test
    void toIdentityFilter_includesNotBefore() {
        Instant notBefore = Instant.parse("2025-01-01T00:00:00Z");
        var query = new CbrQuery("tenant-1", CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), Map.of(), Map.of(), 5, 0.0, notBefore, null, 0.5,
            RetrievalMode.HYBRID, FusionStrategy.RRF);
        Filter filter = CbrQueryTranslator.toIdentityFilter(query);

        // 3 identity + 1 notBefore
        assertThat(filter.getMustCount()).isEqualTo(4);
        Condition storedAtCondition = filter.getMust(3);
        assertThat(storedAtCondition.getField().getKey()).isEqualTo("_stored_at");
    }

    private void assertKeywordCondition(Condition condition, String expectedField, String expectedValue) {
        assertThat(condition.getField().getKey()).isEqualTo(expectedField);
        assertThat(condition.getField().getMatch().getKeyword()).isEqualTo(expectedValue);
    }
}
