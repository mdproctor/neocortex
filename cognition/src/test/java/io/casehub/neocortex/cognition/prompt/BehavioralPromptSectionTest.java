package io.casehub.neocortex.cognition.prompt;

import io.casehub.neocortex.cognition.gut.GutFeeling;
import io.casehub.neocortex.cognition.gut.GutFeelingParticipant;
import io.casehub.neocortex.cognition.gut.GutValence;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.inmem.InMemoryMindMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BehavioralPromptSectionTest {

    private InMemoryMindMapStore store;
    private BehavioralPromptSection section;
    private CognitionRenderContext context;

    @BeforeEach
    void setUp() {
        store = new InMemoryMindMapStore();
        section = new BehavioralPromptSection(store);
        context = new CognitionRenderContext("agent1", "t1", null);
    }

    @Test
    void returnsNullWhenNoAttractors() {
        assertThat(section.render(context)).isNull();
    }

    @Test
    void rendersSingleStrongAttractor() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("completionism", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "completionism",
                "category", "achievement",
                "strength", "0.82",
                "agent-id", "agent1",
                "source-count", "14",
                "source-names", "task abandonment, deadline pressure")),
            "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("deeply ingrained");
        assertThat(rendered).contains("completionism");
        assertThat(rendered).contains("task abandonment");
    }

    @Test
    void rendersStrengtheningTrend() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("authority-distrust", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "authority-distrust",
                "category", "social",
                "strength", "0.55",
                "previous-strength", "0.40",
                "agent-id", "agent1",
                "source-count", "6",
                "source-names", "criticism encounters, hierarchical interactions")),
            "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("noticeable");
        assertThat(rendered).contains("strengthening");
    }

    @Test
    void rendersFadingTrend() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("resource-anxiety", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "resource-anxiety",
                "category", "survival",
                "strength", "0.28",
                "previous-strength", "0.45",
                "agent-id", "agent1",
                "source-count", "3",
                "source-names", "early scarcity experiences")),
            "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("emerging");
        assertThat(rendered).contains("fading");
    }

    @Test
    void omitsTrendWhenStable() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("stable-pattern", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "stable-pattern",
                "category", "social",
                "strength", "0.50",
                "previous-strength", "0.48",
                "agent-id", "agent1",
                "source-count", "5",
                "source-names", "repeated interactions")),
            "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).doesNotContain("strengthening");
        assertThat(rendered).doesNotContain("fading");
    }

    @Test
    void filtersAttractorsBelowThreshold() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("weak-pattern", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "weak-pattern",
                "category", "misc",
                "strength", "0.08",
                "agent-id", "agent1",
                "source-count", "1")),
            "t1");

        assertThat(section.render(context)).isNull();
    }

    @Test
    void limitsToTopFiveByStrength() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        for (int i = 1; i <= 7; i++) {
            store.addNode(NodeInput.of("pattern-" + i, sgId)
                .withTraits(Set.of("CapsGenerated"))
                .withProperties(Map.of(
                    "caps-node-id", "pattern-" + i,
                    "category", "cat",
                    "strength", String.valueOf(0.2 + i * 0.1),
                    "agent-id", "agent1",
                    "source-count", "3")),
                "t1");
        }

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("pattern-7");
        assertThat(rendered).contains("pattern-3");
        assertThat(rendered).doesNotContain("pattern-1");
        assertThat(rendered).doesNotContain("pattern-2");
    }

    @Test
    void onlyRendersForRequestingAgent() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("other-agent-pattern", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "other-pattern",
                "category", "social",
                "strength", "0.80",
                "agent-id", "agent2",
                "source-count", "10")),
            "t1");

        assertThat(section.render(context)).isNull();
    }

    @Test
    void rendersWithoutSourceNames() {
        var sgId = store.createSubgraph(
            new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("unnamed-pattern", sgId)
            .withTraits(Set.of("CapsGenerated"))
            .withProperties(Map.of(
                "caps-node-id", "unnamed-pattern",
                "category", "misc",
                "strength", "0.60",
                "agent-id", "agent1",
                "source-count", "4")),
            "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("unnamed-pattern");
        assertThat(rendered).doesNotContain("rooted in");
    }

    private static GutFeelingParticipant stubGutFeeling(
            String agentId, String tenantId, GutFeeling feeling) {
        return new GutFeelingParticipant(null, null) {
            @Override
            public Optional<GutFeeling> currentResult(String aId, String tId) {
                if (agentId.equals(aId) && tenantId.equals(tId)) {return Optional.of(feeling);}
                return Optional.empty();
            }
        };
    }

    @Test
    void nullGutFeelingParticipantRendersNormally() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("pattern", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "pattern",
                                       "category", "misc",
                                       "strength", "0.60",
                                       "agent-id", "agent1",
                                       "source-count", "4")),
                      "t1");

        var sectionWithNull = new BehavioralPromptSection(store, null);
        var rendered        = sectionWithNull.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered).doesNotContain("transient");
    }

    @Test
    void rendersGutFeelingAlongsideCrystallized() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("completionism", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "completionism",
                                       "category", "achievement",
                                       "strength", "0.80",
                                       "agent-id", "agent1",
                                       "source-count", "10")),
                      "t1");

        var gutParticipant = stubGutFeeling("agent1", "t1",
                                            new GutFeeling(GutValence.AVOID, 0.6, "past authority criticism"));
        var sectionWithGut = new BehavioralPromptSection(store, gutParticipant);
        var rendered       = sectionWithGut.render(context);

        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("completionism");
        assertThat(rendered).contains("authority criticism");
        assertThat(rendered).contains("transient");
    }

    @Test
    void rendersGutFeelingAloneWhenNoAttractors() {
        var gutParticipant = stubGutFeeling("agent1", "t1",
                                            new GutFeeling(GutValence.CAUTIOUS, 0.4, "uncertain territory"));
        var sectionWithGut = new BehavioralPromptSection(store, gutParticipant);
        var rendered       = sectionWithGut.render(context);

        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("cautious wariness");
        assertThat(rendered).contains("transient");
    }

    @Test
    void rendersApproachValence() {
        var gut = stubGutFeeling("agent1", "t1",
                                 new GutFeeling(GutValence.APPROACH, 0.5, "positive collaboration"));
        var sectionWithGut = new BehavioralPromptSection(store, gut);
        assertThat(sectionWithGut.render(context)).contains("drawn to engage");
    }

    @Test
    void rendersAvoidValence() {
        var gut = stubGutFeeling("agent1", "t1",
                                 new GutFeeling(GutValence.AVOID, 0.7, "past negative encounters"));
        var sectionWithGut = new BehavioralPromptSection(store, gut);
        assertThat(sectionWithGut.render(context)).contains("pulling back");
    }

    @Test
    void activatedDriveSortedBeforeInactiveDespiteLowerStrength() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("strong-inactive", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "strong-inactive",
                                       "category", "achievement",
                                       "strength", "0.90",
                                       "agent-id", "agent1",
                                       "source-count", "10")),
                      "t1");
        store.addNode(NodeInput.of("weak-activated", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "weak-activated",
                                       "category", "social",
                                       "strength", "0.30",
                                       "agent-id", "agent1",
                                       "source-count", "3")),
                      "t1");

        var activatedContext = new CognitionRenderContext("agent1", "t1", null,
                                                          java.util.List.of("social"));
        var rendered = section.render(activatedContext);
        assertThat(rendered).isNotNull();
        int activatedPos = rendered.indexOf("weak-activated");
        int inactivePos  = rendered.indexOf("strong-inactive");
        assertThat(activatedPos).isLessThan(inactivePos);
    }

    @Test
    void withinActivatedGroupSortedByStrengthDescending() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("weaker-activated", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "weaker-activated",
                                       "category", "social",
                                       "strength", "0.40",
                                       "agent-id", "agent1",
                                       "source-count", "3")),
                      "t1");
        store.addNode(NodeInput.of("stronger-activated", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "stronger-activated",
                                       "category", "achievement",
                                       "strength", "0.70",
                                       "agent-id", "agent1",
                                       "source-count", "7")),
                      "t1");

        var activatedContext = new CognitionRenderContext("agent1", "t1", null,
                                                          java.util.List.of("social", "achievement"));
        var rendered = section.render(activatedContext);
        assertThat(rendered).isNotNull();
        int strongerPos = rendered.indexOf("stronger-activated");
        int weakerPos   = rendered.indexOf("weaker-activated");
        assertThat(strongerPos).isLessThan(weakerPos);
    }

    @Test
    void noActivatedDrivesFallsBackToStrengthOrdering() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("weaker", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "weaker",
                                       "category", "social",
                                       "strength", "0.30",
                                       "agent-id", "agent1",
                                       "source-count", "3")),
                      "t1");
        store.addNode(NodeInput.of("stronger", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "stronger",
                                       "category", "achievement",
                                       "strength", "0.80",
                                       "agent-id", "agent1",
                                       "source-count", "8")),
                      "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        int strongerPos = rendered.indexOf("stronger");
        int weakerPos   = rendered.indexOf("weaker");
        assertThat(strongerPos).isLessThan(weakerPos);
    }

    @Test
    void activatedDriveBumpsIntoTopFive() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        for (int i = 1; i <= 6; i++) {
            store.addNode(NodeInput.of("inactive-" + i, sgId)
                                   .withTraits(Set.of("CapsGenerated"))
                                   .withProperties(Map.of(
                                           "caps-node-id", "inactive-" + i,
                                           "category", "achievement",
                                           "strength", String.valueOf(0.3 + i * 0.1),
                                           "agent-id", "agent1",
                                           "source-count", "3")),
                          "t1");
        }
        store.addNode(NodeInput.of("activated-low", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "activated-low",
                                       "category", "social",
                                       "strength", "0.15",
                                       "agent-id", "agent1",
                                       "source-count", "1")),
                      "t1");

        var activatedContext = new CognitionRenderContext("agent1", "t1", null,
                                                          java.util.List.of("social"));
        var rendered = section.render(activatedContext);
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("activated-low");
    }

    @Test
    void rendersOscillationAttractorAsTension() {
        var sgId = store.createSubgraph(
                new SubgraphInput("Behavioral", SubgraphTypes.BEHAVIORAL, null), "t1");
        store.addNode(NodeInput.of("withdrawal-oscillation", sgId)
                               .withTraits(Set.of("CapsGenerated"))
                               .withProperties(Map.of(
                                       "caps-node-id", "withdraw",
                                       "category", "approach_avoidance",
                                       "strength", "0.65",
                                       "agent-id", "agent1",
                                       "source-count", "5",
                                       "source-names", "social exclusion, public criticism",
                                       "convergence-type", "oscillation")),
                      "t1");

        var rendered = section.render(context);
        assertThat(rendered).isNotNull();
        assertThat(rendered)
                .as("Oscillation attractor should be framed as tension, not stable tendency")
                .contains("competing");
        assertThat(rendered)
                .as("Should NOT use 'deeply ingrained' or 'noticeable' labels for oscillation")
                .doesNotContain("deeply ingrained")
                .doesNotContain("noticeable")
                .doesNotContain("emerging");
    }


}
