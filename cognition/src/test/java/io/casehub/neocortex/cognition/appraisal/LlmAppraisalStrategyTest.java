package io.casehub.neocortex.cognition.appraisal;

import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.cognitive.index.PersonalityFacet;
import io.casehub.neocortex.cognitive.index.RelationalSchema;
import io.casehub.neocortex.memory.mood.MoodState;
import io.casehub.neocortex.session.testing.StubPipelines;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.smallrye.mutiny.Uni;
import java.time.Duration;

class LlmAppraisalStrategyTest {

    @Test
    void parsesJsonAndProducesEmotions() {
        var json = """
                {
                  "narrative": "A cold knot tightens in your chest — something is wrong.",
                  "dimensions": {
                    "relevance": 0.9,
                    "conduciveness": -0.7,
                    "controllability": 0.3,
                    "novelty": 0.8,
                    "internal-standards": 1.0,
                    "external-standards": 1.0
                  }
                }""";
        var strategy = stubStrategy(json);
        var ctx = context("Clara is missing from the room",
                List.of(new Drive("protection", DriveCategory.CHARACTER, 0.8, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).isNotEmpty();
        assertThat(result.emotions()).extracting("type").contains(EmotionType.FEAR);
        assertThat(result.narrative())
                .isEqualTo("A cold knot tightens in your chest — something is wrong.");
    }

    @Test
    void positiveScenarioProducesJoy() {
        var json = """
                {
                  "narrative": "A spark of excitement lights up inside you.",
                  "dimensions": {
                    "relevance": 0.8,
                    "conduciveness": 0.7,
                    "controllability": 0.6,
                    "novelty": 0.7,
                    "internal-standards": 1.0,
                    "external-standards": 1.0
                  }
                }""";
        var strategy = stubStrategy(json);
        var ctx = context("A hidden inscription reveals the puzzle's answer",
                List.of(new Drive("curiosity", DriveCategory.CHARACTER, 0.7, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).extracting("type").contains(EmotionType.JOY);
        assertThat(result.actionTendencies()).extracting("readiness")
                .contains(ActionReadiness.APPROACH);
        assertThat(result.narrative()).contains("excitement");
    }

    @Test
    void fallsBackToEmptyOnLlmFailure() {
        var strategy = errorStrategy();
        var ctx = context("some situation",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).isEmpty();
        assertThat(result.narrative()).isNull();
    }

    @Test
    void fallsBackToEmptyOnMalformedJson() {
        var strategy = stubStrategy("not json at all");
        var ctx = context("some situation",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.emotions()).isEmpty();
        assertThat(result.narrative()).isNull();
    }

    @Test
    void habituationUpdated() {
        var json = """
                {
                  "narrative": "You feel alert.",
                  "dimensions": { "relevance": 0.6, "novelty": 0.7, "conduciveness": 0.0 }
                }""";
        var strategy = stubStrategy(json);
        var ctx = context("something happens",
                List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")));

        var result = strategy.appraise(ctx);

        assertThat(result.updatedHabituation().observationCounts()).isNotEmpty();
    }

    @Test
    void includesMoodInPromptWhenAvailable() {
        var json = """
                {
                  "narrative": "Uneasy calm.",
                  "dimensions": { "relevance": 0.5, "conduciveness": -0.2 }
                }""";
        var strategy = stubStrategy(json);
        var mood = new MoodState("a1", "t1", Instant.now(), -0.3, 0.2, 0.1,
                "prior", null, null, Map.of());
        var ctx = new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough("a tense moment"),
                List.of(new Drive("protection", DriveCategory.CHARACTER, 0.7, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), mood);

        var result = strategy.appraise(ctx);
        assertThat(result).isNotNull();
        assertThat(result.narrative()).isEqualTo("Uneasy calm.");
    }

    @Test
    void includesDispositionInPromptWhenAvailable() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "A surge of defiance.",
                             "dimensions": { "relevance": 0.7, "conduciveness": -0.3 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        var disposition = new DispositionAxes(
                "assertive", "low", "high", "high", "confrontational");
        var ctx = new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough("a rival challenges authority"),
                List.of(new Drive("dominance", DriveCategory.CHARACTER, 0.8, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), null, disposition);

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).contains("Disposition:");
        assertThat(capturedMessage[0]).contains("Social orientation: assertive");
        assertThat(capturedMessage[0]).contains("Risk appetite: high");
        assertThat(capturedMessage[0]).contains("Conflict mode: confrontational");
    }

    @Test
    void omitsDispositionFromPromptWhenNull() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "Calm.",
                             "dimensions": { "relevance": 0.3, "conduciveness": 0.1 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        var ctx      = context("a quiet morning", List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")));

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).doesNotContain("Disposition:");
    }

    @Test
    void includesRelationshipInPromptWhenAvailable() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override
            public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "Predatory satisfaction.",
                             "dimensions": { "relevance": 0.9, "conduciveness": 0.8 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }

            @Override
            public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

            @Override
            public void close(Duration timeout)   {}
        }));
        var schema = new RelationalSchema("penelope", "prey", 0.1, 0.0, 0.9,
                                          "She inherited everything you built.");
        var ctx = new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough("Penelope escapes the trap"),
                List.of(new Drive("dominance", DriveCategory.CHARACTER, 0.8, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), null,
                null, schema, null);

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).contains("Relationship with penelope:");
        assertThat(capturedMessage[0]).contains("Role: prey");
        assertThat(capturedMessage[0]).contains("Trust: 0.10");
        assertThat(capturedMessage[0]).contains("She inherited everything you built.");
    }

    @Test
    void includesPersonalityFacetsInPromptWhenAvailable() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override
            public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "Cold calculation.",
                             "dimensions": { "relevance": 0.7, "conduciveness": 0.5 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }

            @Override
            public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

            @Override
            public void close(Duration timeout)   {}
        }));
        var facets = List.of(
                new PersonalityFacet("Callousness", 0.9, "numbness where sympathy should be"),
                new PersonalityFacet("Grandiosity", 0.85, null),
                new PersonalityFacet("Anxiousness", 0.2, null));
        var ctx = new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough("someone asks for help"),
                List.of(new Drive("autonomy", DriveCategory.CHARACTER, 0.6, "")),
                null, HabituationConfig.defaults(), HabituationState.empty(), null,
                null, null, facets);

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).contains("Personality traits:");
        assertThat(capturedMessage[0]).contains("Callousness (0.90): numbness where sympathy should be");
        assertThat(capturedMessage[0]).contains("Grandiosity (0.85)");
        assertThat(capturedMessage[0]).doesNotContain("Anxiousness");
    }

    @Test
    void omitsRelationshipAndPersonalityWhenNull() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override
            public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "Neutral.",
                             "dimensions": { "relevance": 0.3, "conduciveness": 0.0 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }

            @Override
            public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

            @Override
            public void close(Duration timeout)   {}
        }));
        var ctx = context("a quiet moment", List.of(new Drive("curiosity", DriveCategory.BASELINE, 0.5, "")));

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).doesNotContain("Relationship with");
        assertThat(capturedMessage[0]).doesNotContain("Personality traits:");
    }

    @Test
    void situationalFacetRendersWithBaselineInPrompt() {
        var capturedMessage = new String[1];
        var capturedSystem  = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> {
            capturedSystem[0] = init.systemPrompt();
            return new AgentSession() {
                @Override
                public Multi<AgentEvent> query(String prompt) {
                    capturedMessage[0] = prompt;
                    var json = """
                               {
                                 "narrative": "Heightened alertness.",
                                 "dimensions": { "relevance": 0.6, "conduciveness": -0.3 }
                               }""";
                    return Multi.createFrom().item(new AgentEvent.TextDelta(json));
                }

                @Override
                public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

                @Override
                public void close(Duration timeout) {}
            };
        }));
        var facets = List.of(
                new PersonalityFacet("Anxiousness", 0.77, "situational [usual 0.62]"));
        var ctx = new AppraisalContext("test-agent",
                                       PerceivedSituation.passThrough("an unexpected noise"),
                                       List.of(new Drive("protection", DriveCategory.CHARACTER, 0.6, "")),
                                       null, HabituationConfig.defaults(), HabituationState.empty(), null,
                                       null, null, facets);

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).contains("Anxiousness (0.77): situational [usual 0.62]");
        assertThat(capturedSystem[0]).contains("situational");
    }

    @Test
    void situationalModeRenderedInPromptWhenActive() {
        var capturedMessage = new String[1];
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override
            public Multi<AgentEvent> query(String prompt) {
                capturedMessage[0] = prompt;
                var json = """
                           {
                             "narrative": "Alert.",
                             "dimensions": { "relevance": 0.7, "conduciveness": -0.4 }
                           }""";
                return Multi.createFrom().item(new AgentEvent.TextDelta(json));
            }

            @Override
            public Uni<Void> interrupt() {return Uni.createFrom().voidItem();}

            @Override
            public void close(Duration timeout) {}
        }));
        var sitMode = new SituationalModeContext("STRESS", 0.72, null, 0.0);
        var ctx = new AppraisalContext("test-agent",
                                       PerceivedSituation.passThrough("a tense standoff"),
                                       List.of(new Drive("protection", DriveCategory.CHARACTER, 0.7, "")),
                                       null, HabituationConfig.defaults(), HabituationState.empty(), null,
                                       null, null, null, sitMode);

        strategy.appraise(ctx);

        assertThat(capturedMessage[0]).contains("Situational mode:");
        assertThat(capturedMessage[0]).contains("Affective: STRESS");
        assertThat(capturedMessage[0]).contains("0.72");
    }


    private static AppraisalContext context(String observation, List<Drive> drives) {
        return new AppraisalContext("test-agent", 
                PerceivedSituation.passThrough(observation),
                drives, null, HabituationConfig.defaults(),
                HabituationState.empty(), null);
    }


@Test
void actionTargetsPopulateTendencyTargets() {
    var json = """
            {
              "narrative": "Something is terribly wrong here.",
              "dimensions": {
                "relevance": 0.9,
                "conduciveness": -0.8,
                "controllability": 0.2,
                "novelty": 0.7,
                "urgency": 0.5,
                "internal-standards": 1.0,
                "external-standards": 1.0
              },
              "action_targets": {
                "avoidance": "the exposed server",
                "attending": "the unfamiliar pattern"
              }
            }""";
    var strategy = stubStrategy(json);
    var ctx = context("A data breach has been discovered",
            List.of(new Drive("protection", DriveCategory.CHARACTER, 0.8, "")));

    var result = strategy.appraise(ctx);

    var avoidance = result.actionTendencies().stream()
            .filter(t -> t.readiness() == ActionReadiness.AVOIDANCE).findFirst();
    assertThat(avoidance).isPresent();
    assertThat(avoidance.get().target()).isEqualTo("the exposed server");

    var attending = result.actionTendencies().stream()
            .filter(t -> t.readiness() == ActionReadiness.ATTENDING).findFirst();
    assertThat(attending).isPresent();
    assertThat(attending.get().target()).isEqualTo("the unfamiliar pattern");
}

@Test
void missingActionTargets_tendenciesStillWork() {
    var json = """
            {
              "narrative": "Something catches your eye.",
              "dimensions": {
                "relevance": 0.7,
                "conduciveness": 0.5,
                "controllability": 0.6,
                "novelty": 0.8,
                "internal-standards": 1.0,
                "external-standards": 1.0
              }
            }""";
    var strategy = stubStrategy(json);
    var ctx = context("A hidden clue appears",
            List.of(new Drive("curiosity", DriveCategory.CHARACTER, 0.7, "")));

    var result = strategy.appraise(ctx);
    assertThat(result.actionTendencies()).isNotEmpty();
}
private static LlmAppraisalStrategy stubStrategy(String response) {
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(response));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        return strategy;
    }

    private static LlmAppraisalStrategy errorStrategy() {
        var strategy = new LlmAppraisalStrategy(StubPipelines.create(init -> new AgentSession() {
            @Override public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().failure(new RuntimeException("LLM unavailable"));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration timeout) {}
        }));
        return strategy;
    }
}
