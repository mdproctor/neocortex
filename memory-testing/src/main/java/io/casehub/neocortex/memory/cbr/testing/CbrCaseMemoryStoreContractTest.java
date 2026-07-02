package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

public abstract class CbrCaseMemoryStoreContractTest {

    protected static final MemoryDomain CBR = new MemoryDomain("cbr");
    protected static final String TENANT = "test-tenant";
    protected static final String ENTITY = "test-entity";

    protected abstract CbrCaseMemoryStore store();

    @BeforeEach
    void registerDefaultSchema() {
        store().registerSchema(CbrFeatureSchema.of("starcraft-game",
            FeatureField.categorical("opponent_race"),
            FeatureField.categorical("detected_build"),
            FeatureField.numeric("army_size_ratio", 0.0, 3.0)));
    }

    @Test
    void store_returnsNonBlankId() {
        var c = new TextualCbrCase("Zerg roach rush", "early pressure", "WIN", 0.9);
        String id = store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        assertThat(id).isNotBlank();
    }

    @Test
    void retrieveSimilar_emptyWhenNoCases() {
        var q = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, CbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_findsStoredCase() {
        var c = new FeatureVectorCbrCase("Zerg roach rush", "early pressure", "WIN", 0.9,
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH", "army_size_ratio", 0.7));
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().features()).containsEntry("opponent_race", "Zerg");
    }

    @Test
    void retrieveSimilar_filtersByCaseType() {
        var c = new FeatureVectorCbrCase("Zerg game", "rush", "WIN", null,
            Map.of("opponent_race", "Zerg"));
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        var q = CbrQuery.of(TENANT, CBR, "aml-investigation", Map.of(), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_filtersByTenant() {
        var c = new FeatureVectorCbrCase("problem", "solution", "WIN", null,
            Map.of("opponent_race", "Zerg"));
        store().store(c, "starcraft-game", ENTITY, CBR, "other-tenant", "case-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_categoricalExactMatch() {
        store().store(new FeatureVectorCbrCase("Zerg game", "rush", "WIN", null,
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        store().store(new FeatureVectorCbrCase("Protoss game", "expand", "LOSS", null,
            Map.of("opponent_race", "Protoss", "detected_build", "ZEALOT_RUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-2");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().features()).containsEntry("opponent_race", "Zerg");
    }

    @Test
    void retrieveSimilar_respectsTopK() {
        for (int i = 0; i < 10; i++) {
            store().store(new FeatureVectorCbrCase("game " + i, "strat", "WIN", null,
                Map.of("opponent_race", "Zerg")),
                "starcraft-game", ENTITY, CBR, TENANT, "case-" + i);
        }

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 3);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void erase_removesMatchingCases() {
        store().store(new TextualCbrCase("problem", "solution", "WIN", null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        int erased = store().erase(new EraseRequest(ENTITY, CBR, TENANT, "case-1"));
        assertThat(erased).isGreaterThanOrEqualTo(0);
    }

    @Test
    void eraseEntity_removesAllEntityCases() {
        store().store(new TextualCbrCase("p1", "s1", "WIN", null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        store().store(new TextualCbrCase("p2", "s2", "LOSS", null),
            "starcraft-game", ENTITY, CBR, TENANT, "case-2");
        int erased = store().eraseEntity(ENTITY, TENANT);
        assertThat(erased).isGreaterThanOrEqualTo(0);
    }

    // --- PlanCbrCase tests ---

    @Test
    void planCbrCase_storeAndRetrieve() {
        var trace = new PlanTrace("scout", "reconnaissance", "drone-scout", "SUCCESS", 1, Map.of());
        var c = new PlanCbrCase("Zerg roach rush", "early pressure", "WIN", 0.85,
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH"),
            List.of(trace));
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "plan-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, PlanCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().cbrType()).isEqualTo("plan");
    }

    @Test
    void planCbrCase_featureMatchFilters() {
        var trace = new PlanTrace("b", "c", "w", "OK", 1, Map.of());
        store().store(new PlanCbrCase("Zerg game", "rush", "WIN", null,
            Map.of("opponent_race", "Zerg"), List.of(trace)),
            "starcraft-game", ENTITY, CBR, TENANT, "plan-1");
        store().store(new PlanCbrCase("Protoss game", "expand", "LOSS", null,
            Map.of("opponent_race", "Protoss"), List.of(trace)),
            "starcraft-game", ENTITY, CBR, TENANT, "plan-2");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, PlanCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().features()).containsEntry("opponent_race", "Zerg");
    }

    @Test
    void planCbrCase_planTraceRoundTrip() {
        var trace1 = new PlanTrace("scout", "reconnaissance", "drone-scout", "SUCCESS", 1,
            Map.of("duration", 30));
        var trace2 = new PlanTrace("attack", "aggression", "roach-push", "SUCCESS", 2,
            Map.of("supply", 44));
        var c = new PlanCbrCase("Zerg game", "rush", "WIN", 0.9,
            Map.of("opponent_race", "Zerg"),
            List.of(trace1, trace2));
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "plan-1");

        var results = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 5),
            PlanCbrCase.class);
        assertThat(results).hasSize(1);
        var retrieved = results.getFirst();
        assertThat(retrieved.planTrace()).hasSize(2);
        assertThat(retrieved.planTrace().get(0).bindingName()).isEqualTo("scout");
        assertThat(retrieved.planTrace().get(0).capabilityName()).isEqualTo("reconnaissance");
        assertThat(retrieved.planTrace().get(1).bindingName()).isEqualTo("attack");
        assertThat(retrieved.planTrace().get(1).parameters()).containsEntry("supply", 44);
    }

    @Test
    void planCbrCase_coexistsWithFeatureVector() {
        store().store(new FeatureVectorCbrCase("FV game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "fv-1");
        store().store(new PlanCbrCase("Plan game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg"),
            List.of(new PlanTrace("b", "c", "w", "OK", 1, Map.of()))),
            "starcraft-game", ENTITY, CBR, TENANT, "plan-1");

        var fvResults = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 10),
            FeatureVectorCbrCase.class);
        assertThat(fvResults).hasSize(1);
        assertThat(fvResults.getFirst().problem()).isEqualTo("FV game");

        var planResults = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 10),
            PlanCbrCase.class);
        assertThat(planResults).hasSize(1);
        assertThat(planResults.getFirst().problem()).isEqualTo("Plan game");

        var allResults = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 10),
            CbrCase.class);
        assertThat(allResults).hasSize(2);
    }

    @Test
    void schemaValidation_categoricalFieldRequiresString() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        assertThatThrownBy(() -> store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", 42), 5),
            FeatureVectorCbrCase.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaValidation_numericFieldRequiresNumber() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
            Map.of("army_size_ratio", 0.7)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        assertThatThrownBy(() -> store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("army_size_ratio", "high"), 5),
            FeatureVectorCbrCase.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaValidation_unknownFieldsIgnored() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "unknown_field", "value"), 5);
        assertThatCode(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
            .doesNotThrowAnyException();
    }
}
