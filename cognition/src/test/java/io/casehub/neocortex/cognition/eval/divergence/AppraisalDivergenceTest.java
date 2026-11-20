package io.casehub.neocortex.cognition.eval.divergence;

import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.casehub.neocortex.cognition.eval.divergence.DivergenceFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

class AppraisalDivergenceTest {

    @Test
    void appraisalDimensionsDiverge() {
        var open = DivergenceFixtures.openDefaults().appraisalWeights();
        var cautious = DivergenceFixtures.cautiousDefaults().appraisalWeights();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();
        assertThat(open).as("appraisal weights should differ between open and cautious personalities")
                .isNotEqualTo(cautious);
    }

    @Test
    void emotionMappingDiverges() {
        var open     = DivergenceFixtures.openDefaults().appraisalWeights();
        var cautious = DivergenceFixtures.cautiousDefaults().appraisalWeights();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean weightsDiffer =
                open.urgencyWeight() != cautious.urgencyWeight()
                || open.relationshipWeight() != cautious.relationshipWeight()
                || open.fearOnsetThreshold() != cautious.fearOnsetThreshold()
                || open.selfStandardsStrictness() != cautious.selfStandardsStrictness()
                || open.otherStandardsStrictness() != cautious.otherStandardsStrictness();

        assertThat(weightsDiffer)
                .as("appraisal weights should differ — same event would map to different emotions. "
                    + "open[urgency=%.2f relation=%.2f fear=%.2f selfStd=%.2f otherStd=%.2f] vs "
                    + "cautious[urgency=%.2f relation=%.2f fear=%.2f selfStd=%.2f otherStd=%.2f]",
                    open.urgencyWeight(), open.relationshipWeight(), open.fearOnsetThreshold(),
                    open.selfStandardsStrictness(), open.otherStandardsStrictness(),
                    cautious.urgencyWeight(), cautious.relationshipWeight(), cautious.fearOnsetThreshold(),
                    cautious.selfStandardsStrictness(), cautious.otherStandardsStrictness())
                .isTrue();
    }

    @Test
    void driveCompositionDiverges() {
        var open     = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        var openCuriosity     = open.curiosity();
        var cautiousCuriosity = cautious.curiosity();

        assertThat(openCuriosity).isNotNull();
        assertThat(cautiousCuriosity).isNotNull();

        assertThat(openCuriosity)
                .as("curiosity config (drive modulation) should differ between open and cautious")
                .isNotEqualTo(cautiousCuriosity);
    }

    @Test
    void gutFeelingDiverges() {
        var store = new InMemoryMindMapStore();

        var cogSgId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("Cognitive",
                                                               io.casehub.neocortex.mindmap.SubgraphTypes.COGNITIVE, null), TENANT);

        for (String agentId : List.of("open", "cautious")) {
            for (int i = 0; i < 5; i++) {
                store.addNode(
                        io.casehub.neocortex.mindmap.NodeInput.of("experience-" + agentId + "-" + i, cogSgId)
                                                              .withProperties(java.util.Map.of(
                                                                      "source-memory-id", "mem-" + agentId + "-" + i,
                                                                      "agent-id", agentId,
                                                                      "event-type", "observation")),
                        TENANT);
            }
        }

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-adventure-open", cogSgId)
                                                            .withProperties(java.util.Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "open",
                                                                    "drive-type", "adventure", "intensity", "0.9")), TENANT);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-curiosity-open", cogSgId)
                                                            .withProperties(java.util.Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "open",
                                                                    "drive-type", "curiosity", "intensity", "0.8")), TENANT);

        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-suspicion-cautious", cogSgId)
                                                            .withProperties(java.util.Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "cautious",
                                                                    "drive-type", "suspicion", "intensity", "0.9")), TENANT);
        store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("drive-self-preservation-cautious", cogSgId)
                                                            .withProperties(java.util.Map.of(
                                                                    "cognitiveKind", "drive-intensity", "agent-id", "cautious",
                                                                    "drive-type", "self-preservation", "intensity", "0.8")), TENANT);

        var harness = DivergenceFixtures.fullBuilder(store).build();

        harness.consolidate("open", TENANT);

        var openResult     = harness.capsSettlingResult("open", TENANT);
        var cautiousResult = harness.capsSettlingResult("cautious", TENANT);

        assertThat(openResult).as("open agent should have settling result").isPresent();
        assertThat(cautiousResult).as("cautious agent should have settling result").isPresent();

        var openAttractors     = openResult.get().attractors();
        var cautiousAttractors = cautiousResult.get().attractors();

        assertThat(openAttractors).as("open agent should have attractors").isNotEmpty();
        assertThat(cautiousAttractors).as("cautious agent should have attractors").isNotEmpty();

        var openByCategory = openAttractors.stream()
                                           .collect(java.util.stream.Collectors.toMap(
                                                   io.casehub.neocortex.caps.CapsAttractor::category,
                                                   io.casehub.neocortex.caps.CapsAttractor::strength,
                                                   Math::max));
        var cautiousByCategory = cautiousAttractors.stream()
                                                   .collect(java.util.stream.Collectors.toMap(
                                                           io.casehub.neocortex.caps.CapsAttractor::category,
                                                           io.casehub.neocortex.caps.CapsAttractor::strength,
                                                           Math::max));

        boolean attractorsDiffer = !openByCategory.keySet().equals(cautiousByCategory.keySet());
        if (!attractorsDiffer) {
            for (var category : openByCategory.keySet()) {
                if (Math.abs(openByCategory.getOrDefault(category, 0.0)
                             - cautiousByCategory.getOrDefault(category, 0.0)) > 0.001) {
                    attractorsDiffer = true;
                    break;
                }
            }
        }

        assertThat(attractorsDiffer)
                .as("behavioral attractors (somatic markers) should differ — open=%s vs cautious=%s",
                    openByCategory, cautiousByCategory)
                .isTrue();
    }

    @Test
    void searchBudgetsDiverge() {
        var harness = DivergenceFixtures.pairedBuilder().build();

        harness.observe("open", TENANT, "someone shared an unexpected idea");
        harness.tick("open", TENANT);
        harness.observe("cautious", TENANT, "someone shared an unexpected idea");
        harness.tick("cautious", TENANT);

        var openBudget = harness.searchBudget("open", TENANT);
        var cautiousBudget = harness.searchBudget("cautious", TENANT);

        assertThat(openBudget).isPresent();
        assertThat(cautiousBudget).isPresent();

        var ob = openBudget.get();
        var cb = cautiousBudget.get();

        boolean anyDimDiffers = ob.breadth() != cb.breadth()
                || ob.depth() != cb.depth()
                || ob.laterality() != cb.laterality()
                || ob.convergence() != cb.convergence();

        assertThat(anyDimDiffers)
                .as("search budgets should differ on at least one dimension — " +
                    "open[b=%.3f d=%.3f l=%.3f c=%.3f] vs cautious[b=%.3f d=%.3f l=%.3f c=%.3f]",
                    ob.breadth(), ob.depth(), ob.laterality(), ob.convergence(),
                    cb.breadth(), cb.depth(), cb.laterality(), cb.convergence())
                .isTrue();
    }

    @Test
    void curiosityChannelDiverges() {
        var store = new InMemoryMindMapStore();
        var subgraphId = store.createSubgraph(
                new io.casehub.neocortex.mindmap.SubgraphInput("General",
                        io.casehub.neocortex.mindmap.SubgraphTypes.GENERAL, null), TENANT);
        var nodeIds = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            nodeIds.add(store.addNode(io.casehub.neocortex.mindmap.NodeInput.of("topic-" + i, subgraphId)
                    .withConfidence(new io.casehub.neocortex.cognitive.Confidence(
                            io.casehub.neocortex.cognitive.ConfidenceOrigin.INFERRED, 0.4, null)), TENANT));
        }
        for (int i = 1; i < nodeIds.size(); i++) {
            store.addEdge(io.casehub.neocortex.mindmap.EdgeInput.of(
                    nodeIds.get(i), nodeIds.get(0), "related"), TENANT);
        }

        var harness = DivergenceFixtures.pairedBuilder()
                .withMindMapStore(store)
                .build();

        harness.observe("open", TENANT, "someone shared an unexpected idea");
        harness.tick("open", TENANT);
        var openChannel = harness.lastChannel("open");

        harness.observe("cautious", TENANT, "someone shared an unexpected idea");
        harness.tick("cautious", TENANT);
        var cautiousChannel = harness.lastChannel("cautious");

        assertThat(openChannel).as("open agent should have non-null channel").isNotNull();
        assertThat(cautiousChannel).as("cautious agent should have non-null channel").isNotNull();

        assertThat(openChannel.hedonic())
                .as("open (anxiousness=0.2) should be I_TYPE, cautious (anxiousness=0.85) should be D_TYPE")
                .isNotEqualTo(cautiousChannel.hedonic());
    }
}
