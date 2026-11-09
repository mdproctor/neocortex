package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.cognition.drive.CuriosityChannel;
import io.casehub.neocortex.cognitive.index.CognitiveDefaults;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapDetector;
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
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Consolidation phase that generates LLM prose describing an agent's curiosity
 * experience through four channels (Litman I/D, Silvia appraisal, Berlyne focus,
 * Loewenstein gap proximity). Crystallises to MindMap COGNITIVE subgraph nodes
 * with the {@value #GENERATED_TRAIT} trait.
 * <p>
 * Runs at {@code @Priority(29)} — after SearchBudgetDescriptionGenerationPhase (28),
 * before CommunitySummaryPhase (30).
 */
@ApplicationScoped
@Priority(29)
public class CuriosityDescriptionGenerationPhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(
            CuriosityDescriptionGenerationPhase.class.getName());

    static final String GENERATED_TRAIT = "CuriosityDescriptionGenerated";

    private static final String SYSTEM_PROMPT = """
            You describe how curiosity feels from the inside for this specific character.
            Four channels shape the experience:
            - Hedonic: Is this pleasurable exploration (I-type) or an aversive need-to-know (D-type)?
            - Comprehension: Are the pieces connecting (interest) or is this overwhelming (confusion)?
            - Focus: Is attention locked on one question (specific) or scanning broadly (diversive)?
            - Gap proximity: How close to knowing — tip of the tongue, or vast unknown?

            Write in second person. 3-5 sentences. Do not name the channels or use technical \
            terms. Describe the felt cognitive state — what draws attention, what satisfies, \
            what feels incomplete.

            Respond with JSON only: {"description": "..."}""";

    private final MindMapStore store;
    private final InformationGapDetector gapDetector;
    private final CuriosityChannelComputer channelComputer;
    private final CognitiveDefaultsRegistry defaultsRegistry;
    private final @Nullable SessionPipeline sessionPipeline;
    private final @Nullable RecurringThoughtPhase recurringThoughtPhase;


    @Inject
    public CuriosityDescriptionGenerationPhase(
            MindMapStore store,
            InformationGapDetector gapDetector,
            CognitiveDefaultsRegistry defaultsRegistry,
            Instance<SessionPipeline> sessionPipelineInstance,
            Instance<RecurringThoughtPhase> recurringThoughtPhaseInstance) {
        this.store                 = store;
        this.gapDetector           = gapDetector;
        this.channelComputer       = new CuriosityChannelComputer();
        this.defaultsRegistry      = defaultsRegistry;
        this.sessionPipeline       = sessionPipelineInstance != null && sessionPipelineInstance.isResolvable()
                                     ? sessionPipelineInstance.get() : null;
        this.recurringThoughtPhase = recurringThoughtPhaseInstance != null && recurringThoughtPhaseInstance.isResolvable()
                                     ? recurringThoughtPhaseInstance.get() : null;
    }

    CuriosityDescriptionGenerationPhase(
            MindMapStore store,
            InformationGapDetector gapDetector,
            CognitiveDefaultsRegistry defaultsRegistry,
            @Nullable SessionPipeline sessionPipeline) {
        this.store                 = store;
        this.gapDetector           = gapDetector;
        this.channelComputer       = new CuriosityChannelComputer();
        this.defaultsRegistry      = defaultsRegistry;
        this.sessionPipeline       = sessionPipeline;
        this.recurringThoughtPhase = null;
    }

    @Override
    public String name() {
        return "curiosity-description";
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        var gaps = gapDetector.detectGaps(tenantId, null);
        if (gaps.isEmpty()) return;

        var tasks = new ArrayList<Consumer<AgentSession>>();
        var agentChannels = new ArrayList<Map.Entry<String, CuriosityChannel>>();

        for (CognitiveDefaults defaults : defaultsRegistry.allProfiles()) {
            if (defaults.tenantId() != null && !tenantId.equals(defaults.tenantId())) continue;

            String agentId = defaults.agentId();
            double deprivationBias = deriveDeprivationBias(defaults);
            var channel = channelComputer.compute(gaps, deprivationBias);
            if (channel == null) continue;

            if (sessionPipeline != null) {
                agentChannels.add(Map.entry(agentId, channel));
                tasks.add(session -> {
                    String userPrompt = buildUserPrompt(agentId, channel, gaps.size());
                    try {
                        var responseText = new StringBuilder();
                        session.query(userPrompt).subscribe().asStream()
                                .filter(e -> e instanceof AgentEvent.TextDelta)
                                .map(e -> ((AgentEvent.TextDelta) e).text())
                                .forEach(responseText::append);
                        String description = extractDescription(responseText.toString());
                        if (description == null) description = generateFallback(channel);
                        crystallise(agentId, tenantId, description);
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "LLM call failed for " + agentId, e);
                        crystallise(agentId, tenantId, generateFallback(channel));
                    }
                });
            } else {
                crystallise(agentId, tenantId, generateFallback(channel));
            }
        }

        if (!tasks.isEmpty() && sessionPipeline != null) {
            try {
                ParallelLlmExecutor.execute(sessionPipeline, "consolidation", 6,
                        SYSTEM_PROMPT, "curiosity-description", tasks);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "LLM curiosity description generation failed", e);
                for (var entry : agentChannels) {
                    crystallise(entry.getKey(), tenantId, generateFallback(entry.getValue()));
                }
            }
        }
    }

    private void crystallise(String agentId, String tenantId, String description) {
        var existing = store.search(MindMapQuery.of(tenantId, 10)
                        .withTraits(Set.of(GENERATED_TRAIT)))
                .stream()
                .filter(n -> n.traits().contains(GENERATED_TRAIT))
                .filter(n -> agentId.equals(n.property("agent-id").orElse(null)))
                .findFirst();

        if (existing.isPresent()) {
            store.updateNode(existing.get().id(),
                    NodeUpdate.empty().withPropertiesToSet(Map.of("description", description)),
                    tenantId);
        } else {
            store.addNode(
                    NodeInput.of("curiosity-" + agentId, SubgraphTypes.COGNITIVE)
                            .withTraits(Set.of(GENERATED_TRAIT))
                            .withProperties(Map.of(
                                    "agent-id", agentId,
                                    "description", description)),
                    tenantId);
        }
    }

    private String buildUserPrompt(String agentId, CuriosityChannel channel, int totalGaps) {
        var sb = new StringBuilder(String.format(
                "Agent: %s\nHedonic type: %s\nComprehension: %.2f\nFocus: %s\n"
                + "Gap proximity: %.2f\nTotal knowledge gaps: %d",
                agentId, channel.hedonic().name(), channel.comprehension(),
                channel.focus().name(), channel.gapProximity(), totalGaps));

        if (recurringThoughtPhase != null) {
            var assessments = recurringThoughtPhase.getAssessments(agentId, "");
            if (!assessments.isEmpty()) {
                var worst = assessments.stream()
                                       .map(a -> a.position())
                                       .max(java.util.Comparator.comparingInt(Enum::ordinal))
                                       .orElse(io.casehub.neocortex.mindmap.ContinuumPosition.HEALTHY);
                if (worst != io.casehub.neocortex.mindmap.ContinuumPosition.HEALTHY) {
                    sb.append("\nThought pattern: ").append(worst.name().toLowerCase())
                      .append(" — the curiosity has become repetitive and involuntary");
                }
            }
        }

        return sb.toString();
    }

    private static @Nullable String extractDescription(String response) {
        if (response == null || !response.contains("\"description\"")) return null;
        int start = response.indexOf("\"description\"");
        int valueStart = response.indexOf("\"", start + 14) + 1;
        int valueEnd = response.indexOf("\"", valueStart);
        if (valueEnd > valueStart) return response.substring(valueStart, valueEnd);
        return null;
    }

    static String generateFallback(CuriosityChannel channel) {
        var sb = new StringBuilder();

        switch (channel.hedonic()) {
            case I_TYPE -> sb.append("Something here catches your interest — a pleasant pull toward understanding. ");
            case D_TYPE -> sb.append("There's a gap in what you know and it won't leave you alone. ");
            case BLENDED -> sb.append("Part fascination, part itch — the not-knowing is both exciting and uncomfortable. ");
        }

        // Silvia (2006) — interest (high comprehension) vs confusion (low). Calibrated empirically.
        if (channel.comprehension() > 0.6) {
            sb.append("The pieces are starting to connect. ");
        } else if (channel.comprehension() < 0.3) {
            sb.append("The more you look, the less you understand. ");
        }

        switch (channel.focus()) {
            case SPECIFIC -> sb.append("Your attention keeps returning to one question.");
            case DIVERSIVE -> sb.append("Everything seems worth exploring right now.");
        }

        return sb.toString().trim();
    }

    private double deriveDeprivationBias(CognitiveDefaults defaults) {
        if (defaults.appraisalWeights() != null) {
            // Litman (2008) — neuroticism correlates with D-type curiosity.
            // Lower fear onset threshold = more anxiety-prone = higher D-type curiosity.
            double fearThreshold = defaults.appraisalWeights().fearOnsetThreshold();
            return Math.clamp(1.0 - fearThreshold, 0.0, 1.0);
        }
        return 0.5;
    }
}
