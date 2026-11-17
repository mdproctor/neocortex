package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.appraisal.ActionReadiness;
import io.casehub.neocortex.cognition.appraisal.AppraisalResult;
import io.casehub.neocortex.cognition.prompt.BlockTag;
import io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.cognitive.EmotionType;
import io.casehub.neocortex.mindmap.ContinuumPosition;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Renders the search budget as a cognitive-state description.
 * <p>
 * Prefers crystallised prose from consolidation-time LLM generation
 * (SearchBudgetDescriptionGenerationPhase). Falls back to programmatic
 * rendering from numeric parameters when no crystallised prose exists.
 * <p>
 * Rendering style grounded in Anthropic "Functional Emotions" (2026):
 * cognitive state descriptions causally influence LLM behavior through
 * internal representations.
 */
public class SearchBudgetPromptSection implements CognitionPromptRenderer {

    static final String GENERATED_TRAIT = "SearchBudgetGenerated";

    private final SearchBudgetTickParticipant participant;
    private final @Nullable MindMapStore mindMapStore;

    public SearchBudgetPromptSection(SearchBudgetTickParticipant participant,
                                     @Nullable MindMapStore mindMapStore) {
        this(participant, mindMapStore, null);
    }

    private final @Nullable Map<String, ContinuumPosition> continuum;
    private final @Nullable Supplier<@Nullable AppraisalResult> appraisalSupplier;

    public SearchBudgetPromptSection(SearchBudgetTickParticipant participant,
                                     @Nullable MindMapStore mindMapStore,
                                     @Nullable Map<String, ContinuumPosition> continuum) {
        this(participant, mindMapStore, continuum, null);
    }

    public SearchBudgetPromptSection(SearchBudgetTickParticipant participant,
                                     @Nullable MindMapStore mindMapStore,
                                     @Nullable Map<String, ContinuumPosition> continuum,
                                     @Nullable Supplier<@Nullable AppraisalResult> appraisalSupplier) {
        this.participant       = participant;
        this.mindMapStore      = mindMapStore;
        this.continuum         = continuum;
        this.appraisalSupplier = appraisalSupplier;
    }


    @Override
    public BlockTag blockTag() {
        return BlockTag.SEARCH_BUDGET;
    }

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        var budgetOpt = participant.currentBudget(context.agentId(), context.tenantId());
        if (budgetOpt.isEmpty()) {return null;}
        var budget = budgetOpt.get();
        String agentId = context.agentId();
        String tenantId = context.tenantId();

        String base = budget.extremeActivation()
                ? readCrystallisedProse(agentId, tenantId, "extreme-description")
                : readCrystallisedProse(agentId, tenantId, "description");
        if (budget.capacityConstrained()) {
            String constrained = readCrystallisedProse(agentId, tenantId, "constrained-description");
            if (constrained != null && base != null) {
                return modulateByContinuum(constrained + " " + base);
            }
            if (constrained != null) {
                return modulateByContinuum(constrained);
            }
        }
        if (base != null) {
            return modulateByContinuum(base);
        }

        if (budget.extremeActivation()) {
            String extremeFallback = readCrystallisedProse(agentId, tenantId, "description");
            if (extremeFallback != null) {
                return modulateByContinuum("Under pressure: " + extremeFallback);
            }
        }
        return modulateByContinuum(renderFromParameters(budget));
    }

    private @Nullable String readCrystallisedProse(String agentId, String tenantId, String propertyKey) {
        if (mindMapStore == null) return null;
        return mindMapStore.search(MindMapQuery.of(tenantId, 10)
                                               .withType(SubgraphTypes.COGNITIVE))
                           .stream()
                           .filter(n -> n.traits().contains(GENERATED_TRAIT))
                           .filter(n -> agentId.equals(n.property("agent-id").orElse(null)))
                           .findFirst()
                           .flatMap(n -> n.property(propertyKey))
                           .filter(d -> !d.isBlank())
                           .orElse(null);
    }

    private static final Set<EmotionType> CRISIS_EMOTIONS = Set.of(
            EmotionType.FEAR, EmotionType.DISTRESS, EmotionType.FEARS_CONFIRMED);
    private static final double URGENCY_THRESHOLD = 0.7;
    private static final double TENDENCY_URGENCY_THRESHOLD = 0.8;

    private String renderFromParameters(SearchBudget budget) {
        if (budget.convergence() < 0.05 && budget.breadth() < 0.15) {
            return "You keep coming back to this. Other thoughts won't stick.";
        }

        double totalBudget = (budget.breadth() + budget.laterality() + budget.depth()) / 3.0;
        if (totalBudget < 0.2) {
            return "This registers, but doesn't hold your attention.";
        }

        String base = switch (budget.source()) {
            case CURIOSITY -> renderCuriosity(budget);
            case COMPETENCE -> renderCompetence(budget);
            case AFFILIATION -> renderAffiliation(budget);
            case AUTONOMY -> renderAutonomy(budget);
            case THREAT -> renderThreat(budget);
            case GOAL -> renderGoal(budget);
        };

        base = modulateByAppraisal(base);

        String profile = renderProfileShape(budget);
        if (profile != null) {
            base = base + " " + profile;
        }

        if (budget.capacityConstrained()) {
            base = base + " " + renderCapacityConstraint(budget);
        }

        String capacity = renderCapacityTrait(budget);
        if (capacity != null) {
            base = base + " " + capacity;
        }

        return base;
    }

    private String modulateByAppraisal(String base) {
        if (appraisalSupplier == null) return base;
        var appraisal = appraisalSupplier.get();
        if (appraisal == null) return base;

        boolean crisisEmotion = appraisal.emotions().stream()
                .anyMatch(e -> CRISIS_EMOTIONS.contains(e.type()) && e.intensity() > 0.5);
        double urgency = appraisal.secDimensions().getOrDefault("urgency", 0.0);
        double peakTendencyIntensity = appraisal.actionTendencies().stream()
                .filter(t -> t.readiness() == ActionReadiness.AVOIDANCE || t.readiness() == ActionReadiness.ANTAGONISM)
                .mapToDouble(t -> t.intensity())
                .max().orElse(0.0);

        if (crisisEmotion) {
            return "This is not idle — something is wrong and demands your attention. " + base;
        }
        if (urgency > URGENCY_THRESHOLD && peakTendencyIntensity > TENDENCY_URGENCY_THRESHOLD) {
            return "The urgency is real — act first, reflect later. " + base;
        }
        if (urgency > URGENCY_THRESHOLD) {
            return "This carries weight — your attention sharpens. " + base;
        }
        return base;
    }

    private String renderCuriosity(SearchBudget b) {
        if (b.breadth() > 0.7 && b.laterality() > 0.5) {
            return "This pulls at your attention from multiple angles — the obvious answer isn't enough, and connections to adjacent areas keep surfacing.";
        }
        if (b.breadth() > 0.5 && b.convergence() < 0.3) {
            return "Your attention fans out — each thread suggests two more, and settling on one feels premature.";
        }
        if (b.breadth() > 0.5) {
            return "Several angles present themselves. The first explanation doesn't quite satisfy.";
        }
        if (b.convergence() > 0.5 && b.depth() > 0.4) {
            return "Something here catches your interest, and you want to follow it through methodically before moving on.";
        }
        return "Something here catches your interest, though it doesn't demand deep investigation.";
    }

    private String renderCompetence(SearchBudget b) {
        if (b.depth() > 0.7) {
            return "You want to trace this to its source. Surface answers aren't satisfying — there's a mechanism underneath.";
        }
        return "This is worth understanding properly, not just noting.";
    }

    private String renderAffiliation(SearchBudget b) {
        return "This matters to someone you're connected to. A quick scan for the socially relevant signal, then move.";
    }

    private String renderAutonomy(SearchBudget b) {
        if (b.laterality() > 0.5 && b.depth() > 0.5) {
            return "You want to find your own path through this — not the obvious route, and not a shallow one.";
        }
        return "You'd rather work this out independently than follow someone else's framing.";
    }

    private String renderThreat(SearchBudget b) {
        return "Your attention narrows. Distant connections aren't useful right now — what matters is the immediate situation.";
    }

    private String renderGoal(SearchBudget b) {
        if (b.convergence() > 0.6) {
            return "The goal is close and the path is clear. Time to execute, not explore.";
        }
        return "There's a specific outcome to reach. Exploration serves the goal, not the other way around.";
    }

    private @Nullable String renderProfileShape(SearchBudget b) {
        if (b.breadth() > 0.4 && b.laterality() > 0.3 && b.convergence() < 0.35) {
            return "You resist narrowing down — staying open feels more productive than committing too early.";
        }
        if (b.convergence() > 0.5 && b.breadth() < 0.4 && b.laterality() < 0.4) {
            return "You naturally converge — exhausting one line of inquiry before opening another, synthesising as you go.";
        }
        if (b.laterality() > 0.5 && b.breadth() < 0.4) {
            return "Your mind jumps sideways — not many threads, but surprising ones.";
        }
        if (b.depth() > 0.6 && b.breadth() < 0.3) {
            return "You bore in deep on what you have rather than reaching for more.";
        }
        return null;
    }

    private String renderCapacityConstraint(SearchBudget b) {
        if (b.breadthGap() > 0.1 && b.lateralityGap() > 0.1) {
            return "You sense more connections than you can hold — threads keep slipping before you can weave them together.";
        }
        if (b.breadthGap() > 0.1) {
            return "You want to track more threads but can only hold a few at once. The others blur when you look away.";
        }
        if (b.lateralityGap() > 0.1) {
            return "Distant connections hover at the edge of your awareness but won't crystallise — you can sense bridges you can't quite cross.";
        }
        if (b.depthGap() > 0.1) {
            return "The chain of reasoning starts to fog after a few steps. You can feel there's more underneath, but the links blur.";
        }
        return "Something about this is just slightly out of reach.";
    }

    private @Nullable String renderCapacityTrait(SearchBudget b) {
        if (!b.hasCapacityConfig()) {return null;}
        var sb = new StringBuilder();
        double rc = b.relationalComplexity();
        double ar = b.associativeReach();
        double wm = b.workingMemorySpan();

        if (rc >= 0 && rc < 0.5) {
            sb.append("Long chains of reasoning are genuinely hard for you. You can only hold two relations in mind at once — when a third arrives, one of the earlier ones genuinely drops. When reasoning through implications, you work one step at a time. After each step, you note what you're currently holding and what has slipped. You cannot see the full chain at once. Branches and negations force you to pause, go back, and re-derive. You sometimes lose a negation or confuse which branch you were following. This is not a choice — it is how your mind works. ");
        }
        if (ar >= 0 && ar < 0.5) {
            sb.append("Connections between distant domains don't come easily. You can sense that a bridge might exist but you can't build it — the leap is too far. Shallow similarities are all you can reliably find. When asked to connect two distant things, your thinking sounds like: 'There's something here... they both have parts that interact? No, that's too surface. I can feel a deeper link but it won't come into focus.' ");
        }
        if (wm >= 0 && wm < 0.5) {
            sb.append("You can only hold two or three things in mind at once. When a fourth arrives, one of the earlier ones genuinely fades — not by choice, but because it simply won't stay. You work around this by focusing narrowly. When juggling threads, your thinking sounds like: 'OK, the carbon policy connects to... wait, what was the third point? Something about subsidies. Let me go back.' ");
        }
        return sb.isEmpty() ? null : sb.toString().strip();
    }

    private String modulateByContinuum(String base) {
        if (continuum == null || continuum.isEmpty()) {return base;}
        var worst = continuum.values().stream()
                             .max(Comparator.comparingInt(ContinuumPosition::ordinal))
                             .orElse(ContinuumPosition.HEALTHY);
        return switch (worst) {
            case HEALTHY -> base;
            case FIXATION -> "You keep circling back to this. " + base;
            case OBSESSION -> "The thought is compulsive now — depth without choice. " + base;
            case RUMINATION -> "Everything has collapsed. You can't stop, but nothing moves forward.";
        };
    }

}
