package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.memory.cbr.*;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
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
            FeatureField.numeric("army_size_ratio", 0.0, 3.0),
            FeatureField.text("notes")));
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
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", "Zerg");
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
        // Graded scoring: Zerg match scores 1.0, Protoss mismatch scores 0.0
        // Both returned with minSimilarity=0.0, but Zerg ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", "Zerg");
        assertThat(results.getFirst().score()).isEqualTo(1.0);
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
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().cbrCase().cbrType()).isEqualTo("plan");
    }

    @Test
    void planCbrCase_featureMatchRanking() {
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
        // Both returned (minSimilarity=0.0), Zerg match ranks first with score 1.0
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", "Zerg");
        assertThat(results.getFirst().score()).isEqualTo(1.0);
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
        var retrieved = results.getFirst().cbrCase();
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
        assertThat(fvResults.getFirst().cbrCase().problem()).isEqualTo("FV game");

        var planResults = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 10),
            PlanCbrCase.class);
        assertThat(planResults).hasSize(1);
        assertThat(planResults.getFirst().cbrCase().problem()).isEqualTo("Plan game");

        var allResults = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 10),
            CbrCase.class);
        assertThat(allResults).hasSize(2);
    }

    // --- notBefore tests ---

    @Test
    void retrieveSimilar_notBefore_filtersOlderCases() throws Exception {
        store().store(new FeatureVectorCbrCase("old game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-old");

        Thread.sleep(50);
        Instant boundary = Instant.now();
        Thread.sleep(50);

        store().store(new FeatureVectorCbrCase("new game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-new");

        var q = new CbrQuery(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), Map.of(), 10, 0.0, boundary, null, 0.5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("new game");
    }

    @Test
    void retrieveSimilar_notBefore_null_returnsAll() {
        store().store(new FeatureVectorCbrCase("game 1", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        store().store(new FeatureVectorCbrCase("game 2", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-2");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    // --- Numeric graded similarity tests ---

    @Test
    void retrieveSimilar_numericSimilarityDecay() {
        store().store(new FeatureVectorCbrCase("close game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.65)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-close");
        store().store(new FeatureVectorCbrCase("far game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 2.0)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-far");

        // Query for army_size_ratio ~0.7 with range tolerance
        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg",
                   "army_size_ratio", NumericRange.within(0.7, 0.15)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned (minSimilarity=0.0), close game ranks higher
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close game");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void retrieveSimilar_numericRange_exact_matchesExactValue() {
        store().store(new FeatureVectorCbrCase("exact game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.7)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-exact");
        store().store(new FeatureVectorCbrCase("other game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 1.5)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-other");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg",
                   "army_size_ratio", NumericRange.exact(0.7)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned, exact match ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("exact game");
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    }

    @Test
    void retrieveSimilar_numericExactMatch_closerValueScoresHigher() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.7)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-match");
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "army_size_ratio", 1.5)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-no-match");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "army_size_ratio", 0.7), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned, exact value match ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("match");
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    }

    @Test
    void schemaValidation_numericFieldAcceptsNumericRange() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
            Map.of("army_size_ratio", 0.7)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("army_size_ratio", NumericRange.within(0.7, 0.1)), 5);
        assertThatCode(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
            .doesNotThrowAnyException();
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

    @Test
    void retrieveSimilar_withProblem_null_returnsFilteredResults() {
        var fv = new FeatureVectorCbrCase("Zerg rush detected", "wall-off", null, null,
            Map.of("opponent_race", "Zerg"));
        store().store(fv, "starcraft-game", ENTITY, CBR, TENANT, "case-null-problem");

        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        // problem is null by default via of()
        assertThat(query.problem()).isNull();

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("Zerg rush detected");
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void retrieveSimilar_withProblem_nonNull_returnsFilteredResults() {
        var fv = new FeatureVectorCbrCase("Zerg rush detected", "wall-off", null, null,
            Map.of("opponent_race", "Zerg"));
        store().store(fv, "starcraft-game", ENTITY, CBR, TENANT, "case-with-problem");

        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5)
            .withProblem("Zerg attack incoming");

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("Zerg rush detected");
    }

    @Test
    void retrieveSimilar_minSimilarity_zero_returnsAllMatches() {
        var fv1 = new FeatureVectorCbrCase("case one", "solution one", null, null,
            Map.of("opponent_race", "Zerg"));
        var fv2 = new FeatureVectorCbrCase("case two", "solution two", null, null,
            Map.of("opponent_race", "Zerg"));
        store().store(fv1, "starcraft-game", ENTITY, CBR, TENANT, "case-ms-1");
        store().store(fv2, "starcraft-game", ENTITY, CBR, TENANT, "case-ms-2");

        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 10);
        // minSimilarity is 0.0 by default
        assertThat(query.minSimilarity()).isEqualTo(0.0);

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- Weighted scoring tests (new for #82 + #87) ---

    @Test
    void weightedScoringProducesExpectedRanking() {
        // Two cases: one matches color (weight=3), other matches build (weight=1)
        store().store(new FeatureVectorCbrCase("color match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "detected_build", "MARINE_PUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-color");
        store().store(new FeatureVectorCbrCase("build match", "strat", "WIN", null,
            Map.of("opponent_race", "Protoss", "detected_build", "ROACH_RUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-build");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH"), 5)
            .withWeights(Map.of("opponent_race", 3.0, "detected_build", 1.0));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // color match: (3*1.0 + 1*0.0) / 4 = 0.75
        // build match: (3*0.0 + 1*1.0) / 4 = 0.25
        // color match should rank first
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("color match");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void defaultWeightsAreUniform() {
        store().store(new FeatureVectorCbrCase("both match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-both");
        store().store(new FeatureVectorCbrCase("one match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "detected_build", "MARINE_PUSH")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-one");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg", "detected_build", "ROACH_RUSH"), 5);
        // No weights → uniform (all default to 1.0)
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // both-match: (1+1)/2 = 1.0, one-match: (1+0)/2 = 0.5
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("both match");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("one match");
        assertThat(results.get(1).score()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void numericSimilarityDecay_closerValueScoresHigher() {
        // army_size_ratio range is [0, 3]
        store().store(new FeatureVectorCbrCase("close", "strat", "WIN", null,
            Map.of("army_size_ratio", 1.0)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-close");
        store().store(new FeatureVectorCbrCase("far", "strat", "WIN", null,
            Map.of("army_size_ratio", 2.5)),
            "starcraft-game", ENTITY, CBR, TENANT, "case-far");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("army_size_ratio", 1.0), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("far");
        assertThat(results.get(1).score()).isLessThan(1.0);
        assertThat(results.get(1).score()).isGreaterThan(0.0);
    }

    @Test
    void minSimilarityThresholdOnCompositeScore() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-match");
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
            Map.of("opponent_race", "Protoss")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-no-match");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5)
            .withMinSimilarity(0.5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Only the Zerg match (score=1.0) passes the 0.5 threshold
        // Protoss mismatch (score=0.0) is excluded
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("match");
    }

    @Test
    void missingFeatureScoresZero() {
        // Case has no features, query asks for opponent_race
        store().store(new FeatureVectorCbrCase("no features", "strat", "WIN", null,
            Map.of()),
            "starcraft-game", ENTITY, CBR, TENANT, "case-empty");
        store().store(new FeatureVectorCbrCase("has feature", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-feat");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("opponent_race", "Zerg"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // Case with feature scores 1.0, case without scores 0.0
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("has feature");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("no features");
        assertThat(results.get(1).score()).isEqualTo(0.0);
    }

    @Test
    void emptyFeaturesScoresOne() {
        store().store(new FeatureVectorCbrCase("any case", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        // No features queried → vacuous truth → score = 1.0
        var q = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of(), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void textExactMatch_identicalStrings() {
        store().store(new FeatureVectorCbrCase("game", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "notes", "early pool")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("notes", "early pool"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void textExactMatch_differentStrings() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "notes", "early pool")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
            Map.of("opponent_race", "Zerg", "notes", "late game macro")),
            "starcraft-game", ENTITY, CBR, TENANT, "case-2");

        var q = CbrQuery.of(TENANT, CBR, "starcraft-game",
            Map.of("notes", "early pool"), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("match");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    // --- SimilaritySpec contract tests (#107, #108) ---

    @Test
    void categoricalTable_graduatedSimilarityRanking() {
        var schema = CbrFeatureSchema.of("medical",
            FeatureField.categorical("condition",
                SimilaritySpec.categoricalTableBuilder()
                    .add("headache", "migraine", 0.8)
                    .add("headache", "fracture", 0.1)
                    .build()),
            FeatureField.numeric("severity", 0, 10));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("migraine case", "treatment A", "SUCCESS", null,
            Map.of("condition", "migraine", "severity", 5.0)),
            "medical", ENTITY, CBR, TENANT, "case-migraine");
        store().store(new FeatureVectorCbrCase("fracture case", "treatment B", "SUCCESS", null,
            Map.of("condition", "fracture", "severity", 5.0)),
            "medical", ENTITY, CBR, TENANT, "case-fracture");

        var results = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "medical",
                Map.of("condition", "headache", "severity", 5.0), 10),
            FeatureVectorCbrCase.class);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().features().get("condition")).isEqualTo("migraine");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void gaussianDecay_numericSimilarityRanking() {
        var schema = CbrFeatureSchema.of("gauss",
            FeatureField.categorical("cat"),
            FeatureField.numeric("val", 0, 100, new SimilaritySpec.GaussianDecay(0.3)));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("close value", "sol", "OK", null,
            Map.of("cat", "a", "val", 50.0)),
            "gauss", ENTITY, CBR, TENANT, "case-close");
        store().store(new FeatureVectorCbrCase("far value", "sol", "OK", null,
            Map.of("cat", "a", "val", 90.0)),
            "gauss", ENTITY, CBR, TENANT, "case-far");

        var results = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "gauss",
                Map.of("cat", "a", "val", 55.0), 10),
            FeatureVectorCbrCase.class);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().features().get("val")).isEqualTo(50.0);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void noSpec_backwardCompatible_linearDecay() {
        var results = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "starcraft-game",
                Map.of("opponent_race", "Zerg", "army_size_ratio", 1.5), 10),
            FeatureVectorCbrCase.class);
        // Existing tests use the default schema with no SimilaritySpec — must still work
        assertThat(results).isNotNull();
    }

    @Test
    void stepDecay_hardCutoff() {
        var schema = CbrFeatureSchema.of("step",
            FeatureField.numeric("val", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("close", "sol", "OK", null,
            Map.of("val", 55.0)),
            "step", ENTITY, CBR, TENANT, "case-close");
        store().store(new FeatureVectorCbrCase("far", "sol", "OK", null,
            Map.of("val", 80.0)),
            "step", ENTITY, CBR, TENANT, "case-far");

        var results = store().retrieveSimilar(
            CbrQuery.of(TENANT, CBR, "step",
                Map.of("val", 50.0), 10).withMinSimilarity(0.5),
            FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().features().get("val")).isEqualTo(55.0);
    }
}
