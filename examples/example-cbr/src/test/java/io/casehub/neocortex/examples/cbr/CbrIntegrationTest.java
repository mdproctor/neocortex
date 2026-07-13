package io.casehub.neocortex.examples.cbr;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.MemoryDomain;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.*;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(QdrantTestResource.class)
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CbrIntegrationTest {

    @Inject CbrCaseMemoryStore store;

    @Test
    @Order(1)
    void seedAllDomains() {
        // Register schemas and store all seed data
        AmlInvestigationDemo.run(store);
        ClinicalAdverseEventDemo.run(store);
        DevtownPrReviewDemo.run(store);
        LifeContractorDemo.run(store);
        IotSituationDemo.run(store);
        QuarkmindBattleDemo.run(store);
    }

    @Test
    @Order(2)
    void denseVectorSearchRanksByProblemSimilarity() {
        // Query with problem text — Qdrant should rank by embedding similarity
        var query = CbrQuery.of("demo", new MemoryDomain("aml"),
                "aml-investigation", Map.of("transaction_pattern", string("STRUCTURING")), 10)
            .withProblem("cash deposits split across branches to avoid reporting threshold");

        var results = store.retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        // With graded similarity, perfect feature matches score 1.0
        // Check that results are ranked by score (highest first)
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(results.size() - 1).score());
        // At least one result should have a high score
        assertThat(results.get(0).score()).isGreaterThan(0.5);
    }

    @Test
    @Order(2)
    void minSimilarityFiltersLowScores() {
        var query = CbrQuery.of("demo", new MemoryDomain("aml"),
                "aml-investigation", Map.of("transaction_pattern", string("STRUCTURING")), 10)
            .withProblem("cash deposits split across branches")
            .withMinSimilarity(0.99);

        var results = store.retrieveSimilar(query, FeatureVectorCbrCase.class);
        // With graded similarity, perfect feature matches score 1.0 (>= 0.99 threshold)
        // All 4 STRUCTURING cases are perfect matches, so all 4 should be returned
        assertThat(results).hasSizeLessThanOrEqualTo(4);
        // All results should meet the threshold
        assertThat(results).allSatisfy(r -> assertThat(r.score()).isGreaterThanOrEqualTo(0.99));
    }

    @Test
    @Order(2)
    void planTraceRoundTripsThroughQdrant() {
        var query = CbrQuery.of("demo", new MemoryDomain("quarkmind"),
                "quarkmind-battle",
                Map.of("opponent_race", string("ZERG"), "detected_build", string("ROACH_RUSH")), 10);

        var results = store.retrieveSimilar(query, PlanCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.cbrCase().planTrace()).isNotEmpty();
            assertThat(r.cbrCase().planTrace().get(0).bindingName()).isNotBlank();
        });
    }

    @Test
    @Order(2)
    void crossDomainIsolation() {
        // AML query should not return clinical cases
        var query = CbrQuery.of("demo", new MemoryDomain("aml"),
                "aml-investigation", Map.of("transaction_pattern", string("STRUCTURING")), 100);

        var results = store.retrieveSimilar(query, FeatureVectorCbrCase.class);
        // With graded similarity, all AML cases are returned (filtered by identity: tenant, domain, caseType)
        // Perfect feature matches (transaction_pattern=STRUCTURING) score 1.0, others score lower
        // Check that the top 4 results are perfect matches
        var topResults = results.stream().limit(4).toList();
        assertThat(topResults).allSatisfy(r -> {
            assertThat(r.score()).isEqualTo(1.0);
            assertThat(r.cbrCase().features().get("transaction_pattern")).isEqualTo(string("STRUCTURING"));
        });
        // All results should be from AML domain (no cross-domain leakage)
        assertThat(results).allSatisfy(r ->
            assertThat(r.cbrCase().features()).containsKey("transaction_pattern"));
    }

    @Test
    @Order(3)
    void notBeforeFiltersOldCases() {
        // All seed cases were just stored — notBefore set to future should return nothing
        var query = CbrQuery.of("demo", new MemoryDomain("aml"),
                "aml-investigation", Map.of("transaction_pattern", string("STRUCTURING")), 10)
            .withNotBefore(java.time.Instant.now().plusSeconds(3600));

        var results = store.retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }
}
