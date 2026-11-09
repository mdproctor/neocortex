package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognitive.CognitiveCapacityConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.session.ParallelLlmExecutor;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Consolidation phase that generates LLM prose describing an agent's default
 * cognitive search disposition. Crystallises to MindMap COGNITIVE subgraph
 * nodes with the {@value #GENERATED_TRAIT} trait.
 * <p>
 * Runs at {@code @Priority(28)} — after DriveDescriptionGenerationPhase (22)
 * and PrioritySignatureDerivationPhase (27), before CommunitySummaryPhase (30).
 * <p>
 * Rendering style grounded in Anthropic "Functional Emotions" (2026):
 * cognitive state descriptions causally influence LLM behavior.
 */
@ApplicationScoped
@Priority(28)
public class SearchBudgetDescriptionGenerationPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(
            SearchBudgetDescriptionGenerationPhase.class.getName());

    static final String GENERATED_TRAIT = "SearchBudgetGenerated";

    private static final String SYSTEM_PROMPT_UNIFIED = """
            You describe an agent's cognitive search disposition — how they \
            naturally approach exploration and problem-solving, shaped by their personality.

            Generate descriptions based on what information is provided:

            1. **Normal range** (always) — how they naturally explore under moderate \
            motivation. Write 2-3 sentences in second person ("you").

            2. **Extreme intensity** (only when extreme parameters are provided) — how \
            their exploration changes under extreme motivation pressure. 2-3 sentences.

            3. **Capacity constrained** (only when capacity parameters are provided) — \
            what happens when motivation pushes past cognitive capacity limits. \
            Where does their reach exceed their grasp? 2-3 sentences.

            Describe felt cognitive state, not parameters. Do not name parameters.

            Respond with JSON containing only the requested fields. Always use \
            "normal" as the key for the normal-range description:
            - Base only: {"normal": "..."}
            - With extreme: {"normal": "...", "extreme": "..."}
            - With capacity: {"normal": "...", "constrained": "..."}
            - With both: {"normal": "...", "extreme": "...", "constrained": "..."}""";

    private final MindMapStore store;
    private final CognitiveDefaultsRegistry defaultsRegistry;
    private final @Nullable SessionPipeline sessionPipeline;

    @Inject
    public SearchBudgetDescriptionGenerationPhase(
            MindMapStore store,
            CognitiveDefaultsRegistry defaultsRegistry,
            Instance<SessionPipeline> sessionPipelineInstance) {
        this.store = store;
        this.defaultsRegistry = defaultsRegistry;
        this.sessionPipeline = sessionPipelineInstance != null && sessionPipelineInstance.isResolvable()
                ? sessionPipelineInstance.get() : null;
    }

    SearchBudgetDescriptionGenerationPhase(
            MindMapStore store,
            CognitiveDefaultsRegistry defaultsRegistry,
            @Nullable SessionPipeline sessionPipeline) {
        this.store = store;
        this.defaultsRegistry = defaultsRegistry;
        this.sessionPipeline = sessionPipeline;
    }

    @Override
    public String name() {
        return "search-budget-description";
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        var tasks = new ArrayList<java.util.function.Consumer<AgentSession>>();
        var agentConfigs = new ArrayList<Map.Entry<String, CognitiveDefaults>>();

        for (CognitiveDefaults defaults : defaultsRegistry.allProfiles()) {
            if (defaults.tenantId() != null && !tenantId.equals(defaults.tenantId())) continue;
            if (defaults.searchBudgetConfig() == null) continue;

            String agentId = defaults.agentId();
            SearchBudgetConfig config = defaults.searchBudgetConfig();
            boolean hasExtremeFeatures = config.extremeInteraction() != null || config.fatigueEffects() != null;

            if (sessionPipeline != null) {
                agentConfigs.add(Map.entry(agentId, defaults));
                tasks.add(session -> {
                    String userPrompt = buildUserPrompt(agentId, config, defaults.capacityConfig());
                    try {
                        var responseText = new StringBuilder();
                        session.query(userPrompt).subscribe().asStream()
                               .filter(e -> e instanceof AgentEvent.TextDelta)
                               .map(e -> ((AgentEvent.TextDelta) e).text())
                               .forEach(responseText::append);
                        String normal = extractField(responseText.toString(), "normal");
                        if (normal == null) normal = extractField(responseText.toString(), "description");
                        if (normal == null) normal = generateFallback(config);
                        String extreme = hasExtremeFeatures ? extractField(responseText.toString(), "extreme") : null;
                        if (hasExtremeFeatures && extreme == null) extreme = generateExtremeFallback(config);
                        String constrained = defaults.capacityConfig() != null
                                ? extractField(responseText.toString(), "constrained") : null;
                        crystallise(agentId, tenantId, normal, extreme, constrained);
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "LLM call failed for " + agentId, e);
                        crystallise(agentId, tenantId, generateFallback(config),
                                hasExtremeFeatures ? generateExtremeFallback(config) : null, null);
                    }
                });
            } else {
                crystallise(agentId, tenantId, generateFallback(config),
                        hasExtremeFeatures ? generateExtremeFallback(config) : null, null);
            }
        }

        if (!tasks.isEmpty() && sessionPipeline != null) {
            try {
                ParallelLlmExecutor.execute(sessionPipeline, "consolidation", 6,
                        SYSTEM_PROMPT_UNIFIED, "search-budget-description", tasks);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "LLM search budget description generation failed", e);
                for (var entry : agentConfigs) {
                    var config = entry.getValue().searchBudgetConfig();
                    boolean extreme = config.extremeInteraction() != null || config.fatigueEffects() != null;
                    crystallise(entry.getKey(), tenantId, generateFallback(config),
                            extreme ? generateExtremeFallback(config) : null, null);
                }
            }
        }
    }

    private void crystallise(String agentId, String tenantId, String description,
                             @Nullable String extremeDescription, @Nullable String constrainedDescription) {
        var existing = store.search(MindMapQuery.of(tenantId, 10)
                        .withTraits(Set.of(GENERATED_TRAIT)))
                .stream()
                .filter(n -> n.traits().contains(GENERATED_TRAIT))
                .filter(n -> agentId.equals(n.property("agent-id").orElse(null)))
                .findFirst();

        var props = new java.util.HashMap<String, String>();
        props.put("description", description);
        if (extremeDescription != null) {
            props.put("extreme-description", extremeDescription);
        }
        if (constrainedDescription != null) {
            props.put("constrained-description", constrainedDescription);
        }

        if (existing.isPresent()) {
            store.updateNode(existing.get().id(),
                    NodeUpdate.empty().withPropertiesToSet(props),
                    tenantId);
        } else {
            props.put("agent-id", agentId);
            store.addNode(
                    NodeInput.of("search-budget-" + agentId, SubgraphTypes.COGNITIVE)
                            .withTraits(Set.of(GENERATED_TRAIT))
                            .withProperties(props),
                    tenantId);
        }
    }

    private String buildUserPrompt(String agentId, SearchBudgetConfig config,
                                    @Nullable CognitiveCapacityConfig capacityConfig) {
        var sb = new StringBuilder();
        sb.append(String.format(
                "Agent: %s\nBreadth steepness: %.1f, midpoint: %.2f\n"
                        + "Laterality steepness: %.1f, midpoint: %.2f, threat floor: %.2f\n"
                        + "Depth steepness: %.1f, midpoint: %.2f\n"
                        + "Convergence steepness: %.1f, midpoint: %.2f\n"
                        + "Pathological threshold: %.2f",
                agentId,
                config.breadthSteepness(), config.breadthMidpoint(),
                config.lateralitySteepness(), config.lateralityMidpoint(), config.lateralityThreatFloor(),
                config.depthSteepness(), config.depthMidpoint(),
                config.convergenceSteepness(), config.convergenceMidpoint(),
                config.pathologicalThreshold()));
        if (config.channelModulation() != null) {
            var mod = config.channelModulation();
            sb.append(String.format(
                    "\nChannel tendencies:\n"
                            + "- Specific focus depth boost: %.2f\n"
                            + "- Diversive breadth boost: %.2f\n"
                            + "- Deprivation convergence boost: %.2f\n"
                            + "- Low comprehension laterality boost: %.2f\n"
                            + "- High comprehension convergence boost: %.2f",
                    mod.specificDepthBoost(), mod.diversiveBreadthBoost(),
                    mod.deprivationConvergenceBoost(),
                    mod.lowComprehensionLateralityBoost(),
                    mod.highComprehensionConvergenceBoost()));
        }
        if (config.extremeInteraction() != null || config.fatigueEffects() != null) {
            sb.append(String.format("\nExtreme peak tolerance: %.2f", config.extremePeak()));
        }
        if (config.fatigueEffects() != null) {
            var f = config.fatigueEffects();
            sb.append(String.format(
                    "\nFatigue effects: depth=%.2f, breadth=%.2f, laterality=%.2f, convergence=%.2f",
                    f.depthEffect(), f.breadthEffect(), f.lateralityEffect(), f.convergenceEffect()));
        }
        if (capacityConfig != null) {
            sb.append(String.format(
                    "\nCapacity ceilings: relational complexity=%.2f, associative reach=%.2f, working memory span=%.2f",
                    capacityConfig.relationalComplexityOrDefault(),
                    capacityConfig.associativeReachOrDefault(),
                    capacityConfig.workingMemorySpanOrDefault()));
        }
        return sb.toString();
    }

    private static @Nullable String extractField(String response, String field) {
        if (response == null || !response.contains("\"" + field + "\"")) {return null;}
        int start      = response.indexOf("\"" + field + "\"");
        int valueStart = response.indexOf("\"", start + field.length() + 2) + 1;
        int valueEnd   = response.indexOf("\"", valueStart);
        if (valueEnd > valueStart) {return response.substring(valueStart, valueEnd);}
        return null;
    }

    static String generateExtremeFallback(SearchBudgetConfig config) {
        var sb = new StringBuilder();
        if (config.fatigueEffects() != null) {
            var f = config.fatigueEffects();
            if (f.depthEffect() > -0.05) {
                sb.append("Pressure sharpens you. Your focus narrows to essential threads and holds there. ");
            } else {
                sb.append("High pressure overwhelms your capacity to sustain threads. ");
            }
            if (f.convergenceEffect() > 0) {
                sb.append("Fatigue, when it comes, takes breadth first — you stop scanning for alternatives and commit to the line you're on.");
            } else {
                sb.append("Your convergence stutters — you know you should synthesise, but uncertainty prevents commitment.");
            }
        } else {
            sb.append("Under extreme motivation, your exploration narrows. What held your attention broadens or contracts depending on what you can sustain.");
        }
        return sb.toString().trim();
    }


    // Calibrated empirically — personality profile classification thresholds
    static String generateFallback(SearchBudgetConfig config) {
        boolean broadExplorer = config.breadthSteepness() > 6.0 || config.breadthMidpoint() < 0.45;
        boolean deepDiver = config.depthMidpoint() < 0.45;
        boolean quickConverter = config.convergenceMidpoint() < 0.45;
        boolean fixationProne = config.pathologicalThreshold() < 1.2;

        var sb = new StringBuilder();
        if (broadExplorer) {
            sb.append("Multiple angles present themselves naturally — single explanations rarely satisfy. ");
        } else {
            sb.append("You tend toward focused investigation rather than broad scanning. ");
        }

        if (deepDiver) {
            sb.append("Threads pull you deeper before you're ready to surface. ");
        }

        if (quickConverter) {
            sb.append("Synthesis arrives early — you move from exploration to conclusion without lingering.");
        } else if (fixationProne) {
            sb.append("When something grips your attention, letting go doesn't come easily.");
        } else {
            sb.append("You balance exploration and conclusion at a steady pace.");
        }

        if (config.channelModulation() != null) {
            var mod = config.channelModulation();
            var d = io.casehub.neocortex.cognitive.ChannelModulationConfig.defaults();
            if (mod.specificDepthBoost() > d.specificDepthBoost() + 0.02) {
                sb.append(" When a specific question grips you, the world narrows and depth pulls you in.");
            }
            if (mod.diversiveBreadthBoost() > d.diversiveBreadthBoost() + 0.02) {
                sb.append(" Broad curiosity fans out quickly — angles multiply before convergence arrives.");
            }
            if (mod.deprivationConvergenceBoost() > d.deprivationConvergenceBoost() + 0.02) {
                sb.append(" Unresolved gaps create pressure — you feel the pull to close them.");
            }
        }

        return sb.toString().trim();
    }
}
