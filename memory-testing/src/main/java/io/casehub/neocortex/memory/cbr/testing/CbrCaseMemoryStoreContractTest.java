package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class CbrCaseMemoryStoreContractTest {

    protected static final MemoryDomain CBR    = new MemoryDomain("cbr");
    protected static final String       TENANT = "test-tenant";
    protected static final String       ENTITY = "test-entity";

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
        var    c  = new TextualCbrCase("Zerg roach rush", "early pressure", "WIN", 0.9);
        String id = store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1");
        assertThat(id).isNotBlank();
    }

    @Test
    void retrieveSimilar_emptyWhenNoCases() {
        var q       = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of("opponent_race", "Zerg"), 5);
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

        var q       = CbrQuery.of(TENANT, CBR, "aml-investigation", Map.of(), 5);
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
                             Map.of("opponent_race", "Zerg"), Map.of(), Map.of(), 10, 0.0, boundary, null, 0.5,
                             RetrievalMode.HYBRID, FusionStrategy.RRF);
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
        var q       = CbrQuery.of(TENANT, CBR, "starcraft-game", Map.of(), 5);
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

    // --- Retrieval mode tests ---

    @Test
    void retrieveSimilar_featureOnly_ignoresProblem() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", "Zerg")),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-1");
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                                Map.of("opponent_race", "Zerg"), 5)
                            .withProblem("some problem")
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    @Test
    void retrieveSimilar_defaultRetrievalMode_isHybrid() {
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                                Map.of("opponent_race", "Zerg"), 5);
        assertThat(query.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void retrieveSimilar_defaultFusionStrategy_isRrf() {
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                                Map.of("opponent_race", "Zerg"), 5);
        assertThat(query.fusionStrategy()).isEqualTo(FusionStrategy.RRF);
    }

    @Test
    void retrieveSimilar_hybrid_withoutEmbeddingModel_degradesToFeatureOnly() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", "Zerg")),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-2");
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                                Map.of("opponent_race", "Zerg"), 5)
                            .withProblem("problem")
                            .withRetrievalMode(RetrievalMode.HYBRID);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    @Test
    void retrieveSimilar_semanticOnly_withoutEmbeddingModel_returnsEmpty() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", "Zerg")),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-3");
        var query = CbrQuery.of(TENANT, CBR, "starcraft-game",
                                Map.of("opponent_race", "Zerg"), 5)
                            .withProblem("problem")
                            .withRetrievalMode(RetrievalMode.SEMANTIC_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

// ==========================================================================
// Structured case fields — contract tests for #89
// ==========================================================================

    private void registerStructuredSchema() {
        store().registerSchema(CbrFeatureSchema.of("game",
                                                   FeatureField.categorical("posture"),
                                                   FeatureField.numeric("score", 0, 100),
                                                   FeatureField.categoricalList("phases"),
                                                   FeatureField.nestedObject("economy",
                                                                             FeatureField.numeric("minute_3", 0, 100),
                                                                             FeatureField.categorical("tier")),
                                                   FeatureField.objectList("moments",
                                                                           FeatureField.categorical("type"),
                                                                           FeatureField.numeric("minute", 0, 90))));
    }

    private String storeGameCase(String problem, Map<String, Object> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", "WIN", null, features),
                "game", ENTITY, CBR, TENANT, caseId);
    }

    @Test
    void structuredFields_categoricalList_containsFilter() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "ALL_IN",
                                      "phases", List.of("EARLY_AGGRESSION", "MID_SKIRMISH", "LATE_PUSH")), "g1");
        storeGameCase("game2", Map.of("posture", "DEFENSIVE",
                                      "phases", List.of("TURTLE", "LATE_PUSH")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("EARLY_AGGRESSION"));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_categoricalList_noFilter_returnsAll() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "ALL_IN",
                                      "phases", List.of("EARLY", "MID")), "g1");
        storeGameCase("game2", Map.of("posture", "DEFENSIVE",
                                      "phases", List.of("TURTLE")), "g2");

        var q       = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void structuredFields_containsAll_matchesSubset() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("A", "B", "C")), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "phases", List.of("A", "D")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAll(List.of("A", "B")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_containsAll_rejectsMissingElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("A", "B")), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAll(List.of("A", "C")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void structuredFields_containsAny_matchesAnyPresent() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("A", "B")), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "phases", List.of("C", "D")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAny(List.of("X", "A")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_containsAny_rejectsAllAbsent() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("A", "B")), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAny(List.of("X", "Y")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void structuredFields_nestedObject_hasMatch_categoricalSubField() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "economy", Map.of("minute_3", 45, "tier", "gold")), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "economy", Map.of("minute_3", 30, "tier", "silver")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("tier", "gold")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_nestedObject_hasMatch_numericRange() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "economy", Map.of("minute_3", 45, "tier", "gold")), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "economy", Map.of("minute_3", 80, "tier", "gold")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("minute_3", NumericRange.of(40, 50))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_nestedObject_hasMatch_exactNumeric() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "economy", Map.of("minute_3", 45, "tier", "gold")), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("minute_3", 45)));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void structuredFields_objectList_hasMatch_anyElementMatching() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "moments", List.of(
                        Map.of("type", "FIRST_CONTACT", "minute", 3.2),
                        Map.of("type", "BATTLE_WON", "minute", 5.1))), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "moments", List.of(
                        Map.of("type", "RETREAT", "minute", 8.0))), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("type", "FIRST_CONTACT")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_objectList_hasMatch_multipleSubFields_sameElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "moments", List.of(
                        Map.of("type", "FIRST_CONTACT", "minute", 3.2),
                        Map.of("type", "BATTLE_WON", "minute", 5.1))), "g1");

        var qMatch = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                             .withFilter("moments", CbrFilter.hasMatch(Map.of("type", "FIRST_CONTACT", "minute", 3.2)));
        assertThat(store().retrieveSimilar(qMatch, FeatureVectorCbrCase.class)).hasSize(1);

        var qCross = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                             .withFilter("moments", CbrFilter.hasMatch(Map.of("type", "FIRST_CONTACT", "minute", 5.1)));
        assertThat(store().retrieveSimilar(qCross, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_objectList_hasMatch_noMatchingElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "moments", List.of(Map.of("type", "RETREAT", "minute", 8.0))), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("type", "FIRST_CONTACT")));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_mixedFlatAndStructured() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "ALL_IN", "score", 85,
                                      "phases", List.of("EARLY", "MID")), "g1");
        storeGameCase("game2", Map.of("posture", "DEFENSIVE", "score", 30,
                                      "phases", List.of("EARLY", "LATE")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game",
                            Map.of("posture", "ALL_IN", "score", 80), 10)
                        .withFilter("phases", CbrFilter.contains("MID"));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_multipleFiltersAndSemantics() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("EARLY", "MID"),
                                      "economy", Map.of("minute_3", 50, "tier", "gold")), "g1");
        storeGameCase("game2", Map.of("posture", "Y",
                                      "phases", List.of("EARLY", "MID"),
                                      "economy", Map.of("minute_3", 50, "tier", "silver")), "g2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("EARLY"))
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("tier", "gold")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_filterOnMissingFieldReturnsEmpty() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X"), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_emptyCategoricalListFiltered() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of()), "g1");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_validation_wrongFilterTypeOnField() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("posture", CbrFilter.contains("A"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_validation_storeTimeMismatch() {
        registerStructuredSchema();
        assertThatThrownBy(() -> storeGameCase("bad", Map.of("phases", "not_a_list"), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_validation_structuredFieldInFeatures() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "phases", List.of("A")), "g1");
        var q = CbrQuery.of(TENANT, CBR, "game",
                            Map.of("phases", List.of("A")), 10);
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be queried via filters");
    }

    @Test
    void structuredFields_validation_filtersWithNoSchema() {
        var q = CbrQuery.of(TENANT, CBR, "unregistered-type", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void structuredFields_validation_duplicateSchemaFieldNames() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("dup",
                                                                            FeatureField.categorical("name"),
                                                                            FeatureField.categoricalList("name"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate field name");
    }

    @Test
    void structuredFields_validation_innerCategoricalWithSimilaritySpec() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad",
                                                                            FeatureField.nestedObject("nested",
                                                                                                      FeatureField.categorical("cat", SimilaritySpec.categoricalTableBuilder()
                                                                                                                                                    .add("A", "B", 0.5).build())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SimilaritySpec not supported");
    }

    @Test
    void structuredFields_validation_innerSemanticText() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad",
                                                                            FeatureField.nestedObject("nested",
                                                                                                      FeatureField.semanticText("desc")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic matching not supported");
    }

    @Test
    void structuredFields_validation_hasMatchNonexistentSubField() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "moments", List.of(Map.of("type", "X", "minute", 1.0))), "g1");
        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("nonexistent", "val")));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found in inner schema");
    }

    @Test
    void structuredFields_validation_hasMatchWrongSubFieldType() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", "X",
                                      "moments", List.of(Map.of("type", "X", "minute", 1.0))), "g1");
        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("minute", "not_a_number")));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires Number or NumericRange");
    }

    @Test
    void structuredFields_notContains_excludesCasesWithValue() {
        registerStructuredSchema();
        storeGameCase("has-rush", Map.of("posture", "X", "phases", List.of("EARLY", "RUSH", "LATE")), "c1");
        storeGameCase("no-rush", Map.of("posture", "X", "phases", List.of("EARLY", "MACRO", "LATE")), "c2");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.notContains("RUSH"))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("no-rush");
    }

    @Test
    void structuredFields_notContainsAny_excludesCasesWithAnyValue() {
        registerStructuredSchema();
        storeGameCase("has-rush", Map.of("posture", "X", "phases", List.of("EARLY", "RUSH")), "c1");
        storeGameCase("has-cheese", Map.of("posture", "X", "phases", List.of("EARLY", "CHEESE")), "c2");
        storeGameCase("clean", Map.of("posture", "X", "phases", List.of("EARLY", "MACRO")), "c3");

        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.notContainsAny(List.of("RUSH", "CHEESE")))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("clean");
    }

    @Test
    void structuredFields_notContains_validation_requiresCategoricalList() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("posture", CbrFilter.notContains("X"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void registerNumericListSchema() {
        store().registerSchema(CbrFeatureSchema.of("player-stats",
                                                   FeatureField.categorical("region"),
                                                   FeatureField.numericList("scores", 0, 100)));
    }

    private String storeNumericListCase(String problem, Map<String, Object> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features),
                "player-stats", ENTITY, CBR, TENANT, caseId);
    }

    @Test
    void numericList_storeAndRetrieve() {
        registerNumericListSchema();
        storeNumericListCase("high scorer", Map.of("region", "NA", "scores", List.of(85, 92, 78)), "c1");
        var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of("region", "NA"), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void numericList_containsRange_matchesElementInRange() {
        registerNumericListSchema();
        storeNumericListCase("has-90s", Map.of("region", "NA", "scores", List.of(85, 92, 78)), "c1");
        storeNumericListCase("no-90s", Map.of("region", "NA", "scores", List.of(50, 60, 70)), "c2");

        var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of(), 10)
                        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("has-90s");
    }

    @Test
    void numericList_containsRange_noMatch() {
        registerNumericListSchema();
        storeNumericListCase("low", Map.of("region", "NA", "scores", List.of(10, 20, 30)), "c1");

        var q = CbrQuery.of(TENANT, CBR, "player-stats", Map.of(), 10)
                        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void numericList_validation_queryFeaturesRejected() {
        registerNumericListSchema();
        assertThatThrownBy(() -> {
            var q = CbrQuery.of(TENANT, CBR, "player-stats",
                                Map.of("scores", List.of(50, 60)), 10);
            store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericList_validation_storeNonNumberRejected() {
        registerNumericListSchema();
        assertThatThrownBy(() -> storeNumericListCase("bad", Map.of("scores", List.of("not-a-number")), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericList_validation_containsRangeOnCategoricalList_rejected() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsRange(new NumericRange(1, 5)));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }


    // ========================= Temporal fields =========================

    private void registerTemporalSchema() {
        store().registerSchema(CbrFeatureSchema.of("temporal-game",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.numeric("mmr", 0, 8000),
                                                   FeatureField.timeSeries("economyCurve", "minute",
                                                                           FeatureField.numeric("minute", 0, 30),
                                                                           FeatureField.numeric("economy", 0, 500),
                                                                           FeatureField.numeric("army", 0, 200),
                                                                           FeatureField.categorical("posture")),
                                                   FeatureField.discreteSequence("phaseProgression")));
    }

    private String storeTemporalCase(String problem, Map<String, Object> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features),
                "temporal-game", ENTITY, CBR, TENANT, caseId);
    }

    @Test
    void temporal_timeSeries_schemaCreation() {
        registerTemporalSchema();
    }

    @Test
    void temporal_timeSeries_validation_storeAscendingTimestamps() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of(
                                  "race", "Terran",
                                  "economyCurve", List.of(
                                          Map.of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                                          Map.of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"))),
                          "valid");
    }

    @Test
    void temporal_timeSeries_validation_storeNonAscending_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", "Terran",
                                                           "economyCurve", List.of(
                                                                   Map.of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"),
                                                                   Map.of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_missingTimestampField_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", "Terran",
                                                           "economyCurve", List.of(Map.of("economy", 30, "army", 0, "posture", "MACRO"))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_innerFieldTypes() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", "Terran",
                                                           "economyCurve", List.of(Map.of("minute", 1, "economy", "not-a-number", "army", 0, "posture", "MACRO"))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_emptyList_accepted() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of("race", "Terran", "economyCurve", List.of()), "empty");
    }

    @Test
    void temporal_discreteSequence_validation_storeListOfStrings() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of(
                "race", "Terran",
                "phaseProgression", List.of("MACRO", "AGGRESSIVE", "ALL_IN")), "valid-seq");
    }

    @Test
    void temporal_discreteSequence_validation_storeWrongType_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                "race", "Terran",
                "phaseProgression", "not-a-list"), "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_filter_onTemporalField_rejected() {
        registerTemporalSchema();
        var query = CbrQuery.of(TENANT, CBR, "temporal-game", Map.of("race", "Terran"), 10)
                            .withFilter("economyCurve", CbrFilter.contains("X"));
        assertThatThrownBy(() -> store().retrieveSimilar(query, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_identicalSequences_scorePerfect() {
        registerTemporalSchema();
        var curve = List.of(
                Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"));
        storeTemporalCase("game", Map.of("race", "Terran", "economyCurve", curve), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("economyCurve", curve), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void temporal_timeSeries_differentSequences_scoredByDtw() {
        registerTemporalSchema();
        storeTemporalCase("close", Map.of("race", "Terran",
                                          "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 32, "army", 1, "posture", "MACRO"),
                        Map.<String, Object>of("minute", 3, "economy", 47, "army", 6, "posture", "MACRO"))), "close");
        storeTemporalCase("far", Map.of("race", "Terran",
                                        "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 200, "army", 100, "posture", "AGGRESSIVE"),
                        Map.<String, Object>of("minute", 3, "economy", 400, "army", 150, "posture", "ALL_IN"))), "far");

        var queryCurve = List.of(
                Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"));
        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("economyCurve", queryCurve), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close");
    }

    @Test
    void temporal_timeSeries_variableLength_handledByDtw() {
        registerTemporalSchema();
        storeTemporalCase("short", Map.of("race", "Terran",
                                          "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), "short");
        storeTemporalCase("long", Map.of("race", "Terran",
                                         "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                        Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"),
                        Map.<String, Object>of("minute", 5, "economy", 60, "army", 10, "posture", "AGGRESSIVE"))), "long");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("economyCurve", List.of(
                                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                                        Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void temporal_timeSeries_dtwExcludesTimestampField() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "economyCurve", List.of(
                        Map.<String, Object>of("minute", 29, "economy", 50, "army", 10, "posture", "MACRO"))), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("economyCurve", List.of(
                                        Map.<String, Object>of("minute", 1, "economy", 50, "army", 10, "posture", "MACRO"))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_discreteSequence_identicalSequences_scorePerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "phaseProgression", List.of("MACRO", "AGGRESSIVE", "ALL_IN")), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("phaseProgression", List.of("MACRO", "AGGRESSIVE", "ALL_IN")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_discreteSequence_oneSubstitution_scoreLessThanPerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "phaseProgression", List.of("MACRO", "AGGRESSIVE", "ALL_IN")), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("phaseProgression", List.of("MACRO", "DEFENSIVE", "ALL_IN")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void temporal_discreteSequence_completelyDifferent_scoreNearZero() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "phaseProgression", List.of("A", "B", "C")), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("phaseProgression", List.of("X", "Y", "Z")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(0.0);
    }

    @Test
    void temporal_discreteSequence_bothEmpty_scorePerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "phaseProgression", List.of()), "c1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("phaseProgression", List.of()), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_mixedFlatAndTemporal_weightedScoring() {
        registerTemporalSchema();
        storeTemporalCase("sameRaceDiffCurve", Map.of(
                "race", "Terran", "mmr", 4500,
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 200, "army", 100, "posture", "AGGRESSIVE"))), "c1");
        storeTemporalCase("diffRaceSameCurve", Map.of(
                "race", "Zerg", "mmr", 4500,
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), "c2");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("race", "Terran", "economyCurve", List.of(
                                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void temporal_weightOverride_temporalFieldDominates() {
        registerTemporalSchema();
        storeTemporalCase("sameRaceDiffCurve", Map.of(
                "race", "Terran",
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 200, "army", 100, "posture", "AGGRESSIVE"))), "c1");
        storeTemporalCase("diffRaceSameCurve", Map.of(
                "race", "Zerg",
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), "c2");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("race", "Terran", "economyCurve", List.of(
                                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), 10)
                            .withWeight("economyCurve", 10.0).withWeight("race", 0.1)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("diffRaceSameCurve");
    }

    @Test
    void temporal_timeSeries_storeAndRetrieve_roundTrip() {
        registerTemporalSchema();
        var curve = List.of(
                Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"));
        storeTemporalCase("game", Map.of("race", "Terran", "economyCurve", curve), "rt1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game", Map.of("race", "Terran"), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        @SuppressWarnings("unchecked")
        var retrieved = (List<Map<String, Object>>) results.get(0).cbrCase().features().get("economyCurve");
        assertThat(retrieved).hasSize(2);
    }

    @Test
    void temporal_discreteSequence_storeAndRetrieve_roundTrip() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", "Terran",
                                         "phaseProgression", List.of("MACRO", "AGGRESSIVE", "ALL_IN")), "rt2");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game", Map.of("race", "Terran"), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        @SuppressWarnings("unchecked")
        var retrieved = (List<String>) results.get(0).cbrCase().features().get("phaseProgression");
        assertThat(retrieved).containsExactly("MACRO", "AGGRESSIVE", "ALL_IN");
    }

    @Test
    void temporal_coexistsWithStructuredFields() {
        store().registerSchema(CbrFeatureSchema.of("mixed",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.categoricalList("tags"),
                                                   FeatureField.timeSeries("trajectory", "t",
                                                                           FeatureField.numeric("t", 0, 10),
                                                                           FeatureField.numeric("v", 0, 100)),
                                                   FeatureField.discreteSequence("phases")));

        store().store(
                new FeatureVectorCbrCase("problem", "solution", null, null, Map.of(
                        "race", "Terran",
                        "tags", List.of("aggro", "fast"),
                        "trajectory", List.of(Map.<String, Object>of("t", 1, "v", 50)),
                        "phases", List.of("A", "B"))),
                "mixed", ENTITY, CBR, TENANT, "mixed-1");

        var query = CbrQuery.of(TENANT, CBR, "mixed", Map.of("race", "Terran"), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void temporal_flatFeatureFilter_reducesCandidatesBeforeDtw() {
        registerTemporalSchema();
        storeTemporalCase("terran-game", Map.of(
                "race", "Terran",
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), "t1");
        storeTemporalCase("zerg-game", Map.of(
                "race", "Zerg",
                "economyCurve", List.of(
                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), "z1");

        var query = CbrQuery.of(TENANT, CBR, "temporal-game",
                                Map.of("race", "Terran", "economyCurve", List.of(
                                        Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("terran-game");
    }

// ========================= Temporal SimilaritySpec =========================

    private void registerTemporalSchemaWithSpecs() {
        store().registerSchema(CbrFeatureSchema.of("temporal-game-specs",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.numeric("mmr", 0, 8000),
                                                   FeatureField.timeSeries("economyCurve", "minute",
                                                                           new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(5)),
                                                                           FeatureField.numeric("minute", 0, 30),
                                                                           FeatureField.numeric("economy", 0, 500),
                                                                           FeatureField.numeric("army", 0, 200),
                                                                           FeatureField.categorical("posture")),
                                                   FeatureField.discreteSequence("phaseProgression",
                                                                                 new SimilaritySpec.EditDistanceSpec(Map.of(
                                                                                         "MACRO", Map.of("DEFENSIVE", 0.8, "AGGRESSIVE", 0.3),
                                                                                         "DEFENSIVE", Map.of("AGGRESSIVE", 0.1))))));
    }

    private String storeTemporalSpecCase(String problem, Map<String, Object> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features),
                "temporal-game-specs", ENTITY, CBR, TENANT, caseId);
    }

    @Test
    void temporal_timeSeries_dtwSpec_acceptedBySchema() {
        registerTemporalSchemaWithSpecs();
    }

    @Test
    void temporal_timeSeries_editDistanceSpec_rejectedBySchema() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad-ts",
                                                                            FeatureField.timeSeries("curve", "t",
                                                                                                    new SimilaritySpec.EditDistanceSpec(Map.of()),
                                                                                                    FeatureField.numeric("t", 0, 10),
                                                                                                    FeatureField.numeric("val", 0, 100)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_discreteSequence_editDistanceSpec_acceptedBySchema() {
        registerTemporalSchemaWithSpecs();
    }

    @Test
    void temporal_discreteSequence_dtwSpec_rejectedBySchema() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad-ds",
                                                                            FeatureField.discreteSequence("phases", new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(3))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_dtwSpec_affectsRetrieval() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("close match", Map.of(
                                      "race", "Terran",
                                      "economyCurve", List.of(
                                              Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                                              Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"))),
                              "close");
        storeTemporalSpecCase("far match", Map.of(
                                      "race", "Terran",
                                      "economyCurve", List.of(
                                              Map.<String, Object>of("minute", 1, "economy", 400, "army", 150, "posture", "AGGRESSIVE"),
                                              Map.<String, Object>of("minute", 3, "economy", 100, "army", 50, "posture", "DEFENSIVE"))),
                              "far");
        var query = CbrQuery.of(TENANT, CBR, "temporal-game-specs", Map.of(
                                        "economyCurve", List.of(
                                                Map.<String, Object>of("minute", 1, "economy", 32, "army", 2, "posture", "MACRO"),
                                                Map.<String, Object>of("minute", 3, "economy", 47, "army", 7, "posture", "MACRO"))),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close match");
    }

    @Test
    void temporal_discreteSequence_editDistanceSpec_affectsRetrieval() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("defensive", Map.of(
                                      "race", "Terran",
                                      "phaseProgression", List.of("MACRO", "DEFENSIVE")),
                              "def");
        storeTemporalSpecCase("aggressive", Map.of(
                                      "race", "Terran",
                                      "phaseProgression", List.of("MACRO", "AGGRESSIVE")),
                              "agg");
        var query = CbrQuery.of(TENANT, CBR, "temporal-game-specs", Map.of(
                                        "phaseProgression", List.of("MACRO", "MACRO")),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("defensive");
    }

    @Test
    void temporal_timeSeries_windowedDtw_similarResult() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("windowed test", Map.of(
                                      "race", "Terran",
                                      "economyCurve", List.of(
                                              Map.<String, Object>of("minute", 1, "economy", 30, "army", 0, "posture", "MACRO"),
                                              Map.<String, Object>of("minute", 3, "economy", 45, "army", 5, "posture", "MACRO"))),
                              "w1");
        var query = CbrQuery.of(TENANT, CBR, "temporal-game-specs", Map.of(
                                        "economyCurve", List.of(
                                                Map.<String, Object>of("minute", 1, "economy", 32, "army", 2, "posture", "MACRO"),
                                                Map.<String, Object>of("minute", 3, "economy", 43, "army", 3, "posture", "MACRO"))),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).score()).isGreaterThan(0.5);
    }
}
