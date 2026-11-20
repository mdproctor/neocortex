package io.casehub.neocortex.cognition.eval.divergence;

import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.casehub.neocortex.cognition.eval.divergence.DivergenceFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

class ConsolidationDivergenceTest {

    @Test
    void behavioralAttractorsDiverge() {
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

        var openResult = harness.capsSettlingResult("open", TENANT);
        var cautiousResult = harness.capsSettlingResult("cautious", TENANT);

        assertThat(openResult).as("open agent should have settling result").isPresent();
        assertThat(cautiousResult).as("cautious agent should have settling result").isPresent();

        var openAttractors = openResult.get().attractors();
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
                .as("behavioral attractor strengths should differ — open=%s vs cautious=%s",
                        openByCategory, cautiousByCategory)
                .isTrue();
    }

    @Test
    void driveClassificationDiverges() {
        var open     = DivergenceFixtures.openDefaults();
        var cautious = DivergenceFixtures.cautiousDefaults();

        assertThat(open.cbrStrategy()).isNotNull();
        assertThat(cautious.cbrStrategy()).isNotNull();

        assertThat(open.cbrStrategy().retrievalMode())
                .as("CBR retrieval mode (drive classification strategy) should differ — "
                    + "open=%s vs cautious=%s",
                    open.cbrStrategy().retrievalMode(), cautious.cbrStrategy().retrievalMode())
                .isNotEqualTo(cautious.cbrStrategy().retrievalMode());
    }

    @Test
    void experienceGraduationDiverges() {
        var open = DivergenceFixtures.openDefaults().recurringThoughtConfig();
        var cautious = DivergenceFixtures.cautiousDefaults().recurringThoughtConfig();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean thresholdsDiffer = open.fixationThreshold() != cautious.fixationThreshold()
                || open.obsessionThreshold() != cautious.obsessionThreshold()
                || open.ruminationThreshold() != cautious.ruminationThreshold();

        assertThat(thresholdsDiffer)
                .as("experience graduation thresholds should differ — "
                    + "open[fix=%.2f obs=%.2f rum=%.2f] vs cautious[fix=%.2f obs=%.2f rum=%.2f]",
                        open.fixationThreshold(), open.obsessionThreshold(), open.ruminationThreshold(),
                        cautious.fixationThreshold(), cautious.obsessionThreshold(), cautious.ruminationThreshold())
                .isTrue();
    }

    @Test
    void beliefRevisionRateDiverges() {
        var open = DivergenceFixtures.openDefaults().graphStructure();
        var cautious = DivergenceFixtures.cautiousDefaults().graphStructure();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.inferenceStyle() != cautious.inferenceStyle()
                || open.connectiveBias() != cautious.connectiveBias();

        assertThat(differs)
                .as("graph structure (belief revision) should differ — "
                    + "open[style=%s bias=%.2f] vs cautious[style=%s bias=%.2f]",
                        open.inferenceStyle(), open.connectiveBias(),
                        cautious.inferenceStyle(), cautious.connectiveBias())
                .isTrue();
    }

    @Test
    void goalPrioritizationDiverges() {
        var open = DivergenceFixtures.openDefaults().extractionBias();
        var cautious = DivergenceFixtures.cautiousDefaults().extractionBias();

        assertThat(open).isNotNull();
        assertThat(cautious).isNotNull();

        boolean differs = open.relationshipBias() != cautious.relationshipBias()
                || open.affectSensitivity() != cautious.affectSensitivity();

        assertThat(differs)
                .as("extraction bias (goal prioritization) should differ — "
                    + "open[rel=%.2f affect=%.2f] vs cautious[rel=%.2f affect=%.2f]",
                        open.relationshipBias(), open.affectSensitivity(),
                        cautious.relationshipBias(), cautious.affectSensitivity())
                .isTrue();
    }
}
