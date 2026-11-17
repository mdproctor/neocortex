package io.casehub.neocortex.cognition.prompt;

import io.casehub.neocortex.cognition.gut.GutFeeling;
import io.casehub.neocortex.cognition.gut.GutFeelingParticipant;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

public class BehavioralPromptSection implements CognitionPromptRenderer {

    private final           MindMapStore          mindMapStore;
    private final @Nullable GutFeelingParticipant gutFeelingParticipant;
    private final @Nullable PromptRenderingConfig config;

    public BehavioralPromptSection(MindMapStore mindMapStore) {
        this(mindMapStore, null, null);
    }

    public BehavioralPromptSection(MindMapStore mindMapStore,
                                   @Nullable GutFeelingParticipant gutFeelingParticipant) {
        this(mindMapStore, gutFeelingParticipant, null);
    }

    public BehavioralPromptSection(MindMapStore mindMapStore,
                                   @Nullable GutFeelingParticipant gutFeelingParticipant,
                                   @Nullable PromptRenderingConfig config) {
        this.mindMapStore          = mindMapStore;
        this.gutFeelingParticipant = gutFeelingParticipant;
        this.config                = config;
    }

    private double minStrength()         {return config != null ? config.behavioralMinStrength() : 0.1;}

    private double deepRootedThreshold() {return config != null ? config.behavioralDeepRootedThreshold() : 0.7;}

    private double noticeableThreshold() {return config != null ? config.behavioralNoticeableThreshold() : 0.4;}

    private double trendDelta()          {return config != null ? config.behavioralTrendDelta() : 0.05;}

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        var attractors = mindMapStore.search(
                                             MindMapQuery.of(context.tenantId(), 100)
                                                         .withType(SubgraphTypes.BEHAVIORAL))
                                     .stream()
                                     .filter(n -> n.traits().contains("CapsGenerated"))
                                     .filter(n -> context.agentId().equals(n.property("agent-id").orElse(null)))
                                     .filter(n -> parseDouble(n, "strength") > minStrength())
                                     .sorted(Comparator.<MindMapNode, Integer>comparing(n -> {
                                                           String cat = n.property("category").orElse("");
                                                           return context.isDriveActivated(cat) ? 0 : 1;
                                                       })
                                                       .thenComparing(Comparator.comparingDouble(
                                                               (MindMapNode n) -> parseDouble(n, "strength")).reversed()))
                                     .limit(5)
                                     .toList();

        var sb = new StringBuilder();
        for (int i = 0; i < attractors.size(); i++) {
            if (i > 0) {sb.append(" ");}
            sb.append(renderAttractor(attractors.get(i)));
        }

        if (gutFeelingParticipant != null) {
            gutFeelingParticipant.currentResult(context.agentId(), context.tenantId())
                                 .ifPresent(gut -> {
                                     if (!sb.isEmpty()) {sb.append(" ");}
                                     sb.append(renderGutFeeling(gut));
                                 });
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private String renderAttractor(MindMapNode node) {
        double  strength    = parseDouble(node, "strength");
        boolean oscillating = "oscillation".equals(node.property("convergence-type").orElse(null));

        String sourceContext = "";
        var    sourceNames   = node.property("source-names");
        if (sourceNames.isPresent() && !sourceNames.get().isBlank()) {
            sourceContext = " — rooted in " + sourceNames.get();
        }

        String attractorName = node.property("personality-label").orElse(node.name());

        if (oscillating) {
            return String.format("You feel a competing pull toward %s%s — your mind hasn't settled on this.",
                                 attractorName, sourceContext);
        }

        String label = strength > deepRootedThreshold() ? "deeply ingrained"
                                                        : strength > noticeableThreshold() ? "noticeable"
                                                                                           : "emerging";

        String trend       = "";
        var    previousOpt = node.property("previous-strength");
        if (previousOpt.isPresent()) {
            double previous = Double.parseDouble(previousOpt.get());
            if (strength > previous + trendDelta()) {
                trend = " that's been strengthening";
            } else if (strength < previous - trendDelta()) {trend = " but it's fading";}
        }

        return String.format("You have a %s %s%s%s.",
                             label, attractorName, trend, sourceContext);
    }

    private static String renderGutFeeling(GutFeeling gut) {
        String feeling = switch (gut.valence()) {
            case APPROACH -> "you feel drawn to engage";
            case AVOID -> "you feel like pulling back";
            case CAUTIOUS -> "you feel a cautious wariness";
        };
        String resonance = gut.resonanceDescription() != null
                           ? "This situation resonates with " + gut.resonanceDescription() + " — "
                           : "Something about this situation — ";
        return resonance + feeling + " (transient, may not apply here).";
    }


    private static double parseDouble(MindMapNode node, String property) {
        return node.property(property).map(Double::parseDouble).orElse(0.0);
    }
}
