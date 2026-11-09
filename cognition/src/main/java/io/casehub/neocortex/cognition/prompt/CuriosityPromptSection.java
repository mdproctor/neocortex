package io.casehub.neocortex.cognition.prompt;

import io.casehub.neocortex.cognition.drive.CuriosityChannel;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.CuriosityChannelComputer;
import io.casehub.neocortex.mindmap.intelligence.InformationGapScore;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Renders curiosity state for the cognitive prompt via crystallised prose or
 * programmatic four-channel rendering.
 *
 * <p>Channels grounded in: Loewenstein (1994) information-gap theory,
 * Litman (2005/2008) I/D curiosity types, Silvia (2006) appraisal theory of interest,
 * Berlyne (1960) specific/diversive exploration.
 *
 * <p>Rendering style: Anthropic "Functional Emotions" (2026) — cognitive state
 * descriptions that causally influence LLM behaviour through internal representations.
 */
public class CuriosityPromptSection implements CognitionPromptRenderer {

    static final String GENERATED_TRAIT = "CuriosityDescriptionGenerated";

    private final           CuriosityDrive           curiosityDrive;
    private final           CuriosityChannelComputer channelComputer;
    private final @Nullable DriveOrchestrator        driveOrchestrator;
    private final @Nullable MindMapStore             mindMapStore;
    private final           double                   deprivationBias;
    private final           double                   baselineIntensity;
    private final @Nullable Map<String, ContinuumPosition> continuum;


    public CuriosityPromptSection(CuriosityDrive curiosityDrive,
                                  CuriosityChannelComputer channelComputer,
                                  @Nullable DriveOrchestrator driveOrchestrator,
                                  @Nullable MindMapStore mindMapStore,
                                  double deprivationBias) {
        this(curiosityDrive, channelComputer, driveOrchestrator, mindMapStore, deprivationBias, 0.5, null); // Default midpoint intensity. Calibrated empirically.
    }

    public CuriosityPromptSection(CuriosityDrive curiosityDrive,
                                  CuriosityChannelComputer channelComputer,
                                  @Nullable DriveOrchestrator driveOrchestrator,
                                  @Nullable MindMapStore mindMapStore,
                                  double deprivationBias,
                                  double baselineIntensity) {
        this(curiosityDrive, channelComputer, driveOrchestrator, mindMapStore,
             deprivationBias, baselineIntensity, null);
    }

    public CuriosityPromptSection(CuriosityDrive curiosityDrive,
                                  CuriosityChannelComputer channelComputer,
                                  @Nullable DriveOrchestrator driveOrchestrator,
                                  @Nullable MindMapStore mindMapStore,
                                  double deprivationBias,
                                  double baselineIntensity,
                                  @Nullable Map<String, ContinuumPosition> continuum) {
        this.curiosityDrive    = curiosityDrive;
        this.channelComputer   = channelComputer;
        this.driveOrchestrator = driveOrchestrator;
        this.mindMapStore      = mindMapStore;
        this.deprivationBias   = deprivationBias;
        this.baselineIntensity = baselineIntensity;
        this.continuum         = continuum;
    }


    @Override
    public BlockTag blockTag() {
        return BlockTag.CURIOSITY;
    }

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        List<InformationGapScore> gaps = curiosityDrive.lastGaps();
        if (gaps == null || gaps.isEmpty()) {return null;}

        String crystallised = readCrystallisedProse(context.agentId(), context.tenantId());
        if (crystallised != null) {
            return modulateByContinuum(crystallised);
        }

        var channel = channelComputer.compute(gaps, deprivationBias);
        if (channel == null) {return null;}

        String base = renderFromChannels(channel, gaps, context);
        return modulateByContinuum(base);
    }

    private @Nullable String readCrystallisedProse(String agentId, String tenantId) {
        if (mindMapStore == null) {return null;}
        return mindMapStore.search(MindMapQuery.of(tenantId, 10)
                                               .withType(SubgraphTypes.COGNITIVE))
                           .stream()
                           .filter(n -> n.traits().contains(GENERATED_TRAIT))
                           .filter(n -> agentId.equals(n.property("agent-id").orElse(null)))
                           .findFirst()
                           .flatMap(n -> n.property("description"))
                           .filter(d -> !d.isBlank())
                           .orElse(null);
    }

    private String renderFromChannels(CuriosityChannel channel,
                                      List<InformationGapScore> gaps,
                                      CognitionRenderContext context) {
        var sb = new StringBuilder();

        switch (channel.hedonic()) {
            case I_TYPE -> sb.append("Something here catches your interest — a pleasant pull toward understanding. ");
            case D_TYPE -> sb.append("There's a gap in what you know and it won't leave you alone. ");
            case BLENDED -> sb.append("Part fascination, part itch — the not-knowing is both exciting and uncomfortable. ");
        }

        if (channel.comprehension() > 0.6) { // Silvia (2006) — high comprehension → interest. Calibrated empirically.
            sb.append("The pieces are starting to connect. ");
        } else if (channel.comprehension() < 0.3) { // Silvia (2006) — low comprehension → confusion. Calibrated empirically.
            sb.append("The more you look, the less you understand. ");
        }

        if (channel.focus() == CuriosityChannel.FocusType.SPECIFIC && !gaps.isEmpty()) {
            var topGap = gaps.getFirst();
            sb.append("Your attention keeps returning to ").append(topGap.nodeName());
            if (topGap.goalRelevance() >= 0.7) { // Goal relevance rendering thresholds. Calibrated empirically.
                sb.append(" — closely related to an active goal");
            } else if (topGap.goalRelevance() >= 0.4) {
                sb.append(" — connected to an active goal");
            }
            sb.append(". ");
        } else if (channel.focus() == CuriosityChannel.FocusType.DIVERSIVE) {
            long goalRelevantCount = gaps.stream().limit(5).filter(g -> g.goalRelevance() >= 0.4).count();
            if (goalRelevantCount > 0) {
                sb.append("Several threads worth exploring, some tied to active goals. ");
            } else {
                sb.append("Everything seems worth exploring right now. ");
            }
        }

        appendBaselineDeviation(sb);
        appendDriveRank(sb, context);

        return sb.toString().trim();
    }

    private void appendBaselineDeviation(StringBuilder sb) {
        double currentIntensity = curiosityDrive.lastGaps().stream()
                                                .limit(5)
                                                .mapToDouble(InformationGapScore::gapIntensity)
                                                .average().orElse(0.0);

        double deviation = currentIntensity - baselineIntensity;
        if (deviation > 0.15) { // Minimum deviation to report. Calibrated empirically.
            sb.append("This is stronger than your usual curiosity. ");
        } else if (deviation < -0.15) {
            sb.append("Quieter than usual — the pull to explore is muted. ");
        }
    }

    private void appendDriveRank(StringBuilder sb, CognitionRenderContext context) {
        if (driveOrchestrator == null) {return;}

        driveOrchestrator.currentDrives(context.agentId(), context.tenantId())
                         .ifPresent(profile -> {
                             if (profile.dominantDrive() == DriveAxis.CURIOSITY) {
                                 sb.append("Curiosity is the dominant pull right now.");
                             } else {
                                 String dominant = profile.dominantDrive().name().charAt(0)
                                                   + profile.dominantDrive().name().substring(1).toLowerCase();
                                 sb.append(dominant).append(" edges ahead, but curiosity is present.");
                             }
                         });
    }

    private String modulateByContinuum(String base) {
        if (continuum == null || continuum.isEmpty()) {return base;}
        var worst = continuum.values().stream()
                             .max(Comparator.comparingInt(ContinuumPosition::ordinal))
                             .orElse(ContinuumPosition.HEALTHY);
        return switch (worst) {
            case HEALTHY -> base;
            case FIXATION -> base + " This keeps pulling you back — it's becoming hard to look away.";
            case OBSESSION -> "Something unbidden keeps surfacing. " + base
                              + " The thought arrives uninvited, intrusive, circling.";
            case RUMINATION -> "The pull has gone flat. What started as curiosity has turned inward — "
                               + "you can't stop returning to it, but the exploration brings no relief.";
        };
    }

}
