package io.casehub.neocortex.memory.cbr.qdrant;

import dev.langchain4j.data.embedding.Embedding;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CbrPointBuilderTest {

    @Test
    void deterministicPointId() {
        UUID id1 = CbrPointBuilder.pointId("tenant-1", "starcraft-game", "case-42");
        UUID id2 = CbrPointBuilder.pointId("tenant-1", "starcraft-game", "case-42");
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void differentInputsProduceDifferentIds() {
        UUID id1 = CbrPointBuilder.pointId("tenant-1", "type-a", "case-1");
        UUID id2 = CbrPointBuilder.pointId("tenant-1", "type-a", "case-2");
        UUID id3 = CbrPointBuilder.pointId("tenant-2", "type-a", "case-1");
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
    }

    @Test
    void buildPoint_textualCase_payloadFields() {
        var cbrCase = new TextualCbrCase("problem text", "solution text", "WIN", 0.9);
        PointStruct point = CbrPointBuilder.buildPoint(cbrCase, "game",
            "entity-1", "cbr", "tenant-1", "case-1", null, "dense");

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload.get("tenantId").getStringValue()).isEqualTo("tenant-1");
        assertThat(payload.get("caseType").getStringValue()).isEqualTo("game");
        assertThat(payload.get("entityId").getStringValue()).isEqualTo("entity-1");
        assertThat(payload.get("domain").getStringValue()).isEqualTo("cbr");
        assertThat(payload.get("caseId").getStringValue()).isEqualTo("case-1");
        assertThat(payload.get("problem").getStringValue()).isEqualTo("problem text");
        assertThat(payload.get("solution").getStringValue()).isEqualTo("solution text");
        assertThat(payload.get("outcome").getStringValue()).isEqualTo("WIN");
        assertThat(payload.get("confidence").getDoubleValue()).isEqualTo(0.9);

        // Has vectors (placeholder when no embedding)
        assertThat(point.hasVectors()).isTrue();
    }

    @Test
    void buildPoint_featureVectorCase_withFeatures() {
        var cbrCase = new FeatureVectorCbrCase("Zerg rush", "early attack", null, null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.7));
        PointStruct point = CbrPointBuilder.buildPoint(cbrCase, "game",
            "entity-1", "cbr", "tenant-1", "case-2", null, "dense");

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload.get("f_opponent_race").getStringValue()).isEqualTo("Zerg");
        assertThat(payload.get("f_army_size_ratio").getDoubleValue()).isEqualTo(0.7);
        assertThat(payload.get("_features_json").getStringValue()).contains("opponent_race");
        assertThat(payload).doesNotContainKey("outcome");
        assertThat(payload).doesNotContainKey("confidence");
    }

    @Test
    void buildPoint_withEmbedding_hasVectors() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        float[] vector = {0.1f, 0.2f, 0.3f, 0.4f};
        Embedding embedding = Embedding.from(vector);
        PointStruct point = CbrPointBuilder.buildPoint(cbrCase, "type",
            "e1", "d1", "t1", "c1", embedding, "dense");

        assertThat(point.hasVectors()).isTrue();
        assertThat(point.hasId()).isTrue();
    }

    @Test
    void buildPoint_nullOptionalFields() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        PointStruct point = CbrPointBuilder.buildPoint(cbrCase, "type",
            "e1", "d1", "t1", "c1", null, "dense");

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload).doesNotContainKey("outcome");
        assertThat(payload).doesNotContainKey("confidence");
    }

    @Test
    void buildPoint_stores_cbr_type_discriminator() {
        var textual = new TextualCbrCase("problem", "solution", null, null);
        PointStruct point = CbrPointBuilder.buildPoint(textual, "game",
            "e1", "cbr", "t1", "c1", null, "dense");
        assertThat(point.getPayloadMap().get("_cbr_type").getStringValue()).isEqualTo("textual");
        assertThat(point.getPayloadMap()).doesNotContainKey("_case_class");

        var fv = new FeatureVectorCbrCase("p", "s", null, null, Map.of("race", "Zerg"));
        PointStruct fvPoint = CbrPointBuilder.buildPoint(fv, "game",
            "e1", "cbr", "t1", "c2", null, "dense");
        assertThat(fvPoint.getPayloadMap().get("_cbr_type").getStringValue()).isEqualTo("feature-vector");
    }

    @Test
    void buildPoint_featureVectorCase_emptyFeatures_still_writes_features_json() {
        var fv = new FeatureVectorCbrCase("p", "s", null, null, Map.of());
        PointStruct point = CbrPointBuilder.buildPoint(fv, "game",
            "e1", "cbr", "t1", "c1", null, "dense");
        assertThat(point.getPayloadMap().get("_features_json").getStringValue()).isEqualTo("{}");
        assertThat(point.getPayloadMap().get("_cbr_type").getStringValue()).isEqualTo("feature-vector");
    }

    @Test
    void buildPoint_includes_stored_at_timestamp() {
        var cbrCase = new TextualCbrCase("problem", "solution", null, null);
        PointStruct point = CbrPointBuilder.buildPoint(cbrCase, "type",
            "e1", "d1", "t1", "c1", null, "dense");

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload).containsKey("_stored_at");
        assertThat(payload.get("_stored_at").getIntegerValue()).isGreaterThan(0);
    }

    @Test
    void pointId_matchesBuildPointId() {
        UUID expected = CbrPointBuilder.pointId("tenant-1", "game", "case-1");
        String expectedIdInput = "tenant-1#game#case-1";
        UUID expectedUuid = UUID.nameUUIDFromBytes(expectedIdInput.getBytes(StandardCharsets.UTF_8));
        assertThat(expected).isEqualTo(expectedUuid);
    }
}
