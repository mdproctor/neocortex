package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.prompt.BlockTag;
import io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.cognition.trace.PipelineTrace;
import org.jspecify.annotations.Nullable;

import java.util.function.BiFunction;

public class ShadowArchetypePromptSection implements CognitionPromptRenderer {

    @FunctionalInterface
    public interface ModeProfileResolver {
        java.util.Set<String> resolve(String agentId, String tenantId, String mode);
    }

    private final           BiFunction<String, String, java.util.Optional<PipelineTrace.SituationalPersonalitySnapshot>> snapshotAccessor;
    private final io.casehub.neocortex.mindmap.MindMapStore store;
    private final           ShadowFrameConfig                                                                            config;
    private final ModeProfileResolver modeProfileResolver;

    public ShadowArchetypePromptSection(
            BiFunction<String, String, java.util.Optional<PipelineTrace.SituationalPersonalitySnapshot>> snapshotAccessor) {
        this(snapshotAccessor, null, ShadowFrameConfig.defaults(), null);
    }

    public ShadowArchetypePromptSection(
            BiFunction<String, String, java.util.Optional<PipelineTrace.SituationalPersonalitySnapshot>> snapshotAccessor,
            io.casehub.neocortex.mindmap.MindMapStore store,
            ShadowFrameConfig config,
            ModeProfileResolver modeProfileResolver) {
        this.snapshotAccessor    = snapshotAccessor;
        this.store               = store;
        this.config              = config;
        this.modeProfileResolver = modeProfileResolver;
    }

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        var snapshot = snapshotAccessor.apply(context.agentId(), context.tenantId());
        if (snapshot.isEmpty()) {return null;}

        var s = snapshot.get();
        if (s.affectiveWinner() == null && s.socialWinner() == null) {return null;}

        var    sb         = new StringBuilder();
        String activeMode = s.affectiveWinner();

        if (activeMode != null) {
            double intensity = s.modeIntensities().getOrDefault(activeMode, 0.0);
            sb.append("Your personality has shifted under ")
              .append(modeName(activeMode));
            sb.append(" (intensity ").append(String.format("%.0f%%", intensity * 100)).append(").\n");
        }
        if (s.socialWinner() != null) {
            double intensity = s.modeIntensities().getOrDefault(s.socialWinner(), 0.0);
            sb.append("Social orientation: ")
              .append(modeName(s.socialWinner()));
            sb.append(" (intensity ").append(String.format("%.0f%%", intensity * 100)).append(").\n");
        }

        java.util.Set<String> modeProfileFacets = activeMode != null && modeProfileResolver != null
                                                  ? modeProfileResolver.resolve(context.agentId(), context.tenantId(), activeMode)
                                                  : java.util.Set.of();

        String dominantAttractor = store != null
                                   ? resolveDominantAttractor(context.agentId(), context.tenantId()) : null;

        var scored = FacetRelevanceScorer.score(
                s.facetDisplacements(), activeMode, modeProfileFacets, dominantAttractor, config);

        for (var facet : scored) {
            String direction = facet.displacement() > 0 ? "elevated" : "reduced";
            String magnitude = Math.abs(facet.displacement()) > 0.15 ? ""
                                                                     : Math.abs(facet.displacement()) > 0.08 ? "" : "slightly ";
            sb.append("  ").append(formatFacetName(facet.facet())).append(": ")
              .append(magnitude).append(direction)
              .append(" (").append(String.format("%+d%%", Math.round(facet.displacement() * 100))).append(")");
            if (facet.modeCharacteristic()) {sb.append(" — characteristic of this state");}
            sb.append("\n");
        }

        boolean archetypeResolved = false;
        if (store != null) {
            var family = resolveArchetypeFamily(context.agentId(), context.tenantId());
            if (family.isPresent()) {
                archetypeResolved = true;
                sb.append("\nYou are a ").append(capitalise(family.get())).append(". These shifts are your current felt reality.\n");
            }
        }

        if (scored.isEmpty() && !archetypeResolved) {
            if (s.publishedCount() > 0) {
                sb.append(s.publishedCount())
                  .append(s.publishedCount() == 1 ? " personality trait has" : " personality traits have")
                  .append(" shifted from baseline.");
            }
            sb.append(" This is your current reality — do not comment on or explain it.");
        }

        return sb.toString().trim();
    }

    @Override
    public BlockTag blockTag() {
        return BlockTag.SITUATIONAL;
    }

    private String resolveDominantAttractor(String agentId, String tenantId) {
        var nodes = store.search(
                io.casehub.neocortex.mindmap.MindMapQuery.of(tenantId, 10)
                                                         .withType(io.casehub.neocortex.mindmap.SubgraphTypes.BEHAVIORAL));
        return nodes.stream()
                    .filter(n -> n.traits().contains("CapsGenerated"))
                    .filter(n -> agentId.equals(n.properties().get("agent-id")))
                    .max(java.util.Comparator.comparingDouble(
                            n -> parseDouble(n.properties().get("strength"))))
                    .map(n -> n.properties().get("caps-node-id"))
                    .orElse(null);
    }

    private java.util.Optional<String> resolveArchetypeFamily(String agentId, String tenantId) {
        var nodes = store.search(
                io.casehub.neocortex.mindmap.MindMapQuery.of(tenantId, 10)
                                                         .withType(io.casehub.neocortex.mindmap.SubgraphTypes.PERSON)
                                                         .withText(agentId));
        if (nodes.isEmpty()) {return java.util.Optional.empty();}
        return java.util.Optional.ofNullable(nodes.getFirst().properties().get("archetype-1-family"));
    }

    private static String modeName(String raw) {
        return raw.toLowerCase().replace('_', '-');
    }

    private static String formatFacetName(String kebab) {
        String spaced = kebab.replace('-', ' ');
        return spaced.substring(0, 1).toUpperCase() + spaced.substring(1);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static double parseDouble(String s) {
        try {return s != null ? Double.parseDouble(s) : 0.0;} catch (NumberFormatException e) {return 0.0;}
    }
}
