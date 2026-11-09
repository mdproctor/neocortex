package io.casehub.neocortex.cognition.search;

import io.casehub.neocortex.cognition.appraisal.ActionReadiness;
import io.casehub.neocortex.cognition.appraisal.ActionTendency;
import io.casehub.neocortex.cognition.appraisal.AppraisalTickParticipant;
import io.casehub.neocortex.cognition.appraisal.HabituationState;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.core.CognitionTickContext;
import io.casehub.neocortex.cognition.core.CognitionTickParticipant;
import io.casehub.neocortex.cognition.drive.CuriosityChannel;
import io.casehub.neocortex.cognition.drive.CuriosityDrive;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.cognition.gut.GutFeelingParticipant;
import io.casehub.neocortex.cognition.gut.GutValence;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.core.CognitiveResourcePool;
import io.casehub.neocortex.cognitive.ExtremeInteractionConfig;
import io.casehub.neocortex.cognitive.HabituationConfig;
import io.casehub.neocortex.cognitive.SearchBudgetConfig;
import io.casehub.neocortex.cognitive.SearchFatigueEffects;
import io.casehub.neocortex.cognitive.index.CognitiveDefaultsRegistry;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DERIVED-phase tick participant computing a 4-dimensional search budget
 * from personality baseline + transient cognitive state.
 * <p>
 * Reads already-composed outputs from DriveOrchestrator, AppraisalTickParticipant,
 * GutFeelingParticipant, and MoodOrchestrator. Does not compose motivations itself
 * (Mischel & Shoda, 1995 constraint satisfaction; Frijda, 1986 control precedence).
 * <p>
 * Per-parameter logistic activation extending CapsSettler's bare logistic with
 * tunable steepness k and midpoint x0. Laterality has a threat-collapse floor
 * (Frijda, 1986 approach/avoidance). Convergence has a pathological floor
 * (Davis & Nolen-Hoeksema, 1998 perseveration).
 */
public class SearchBudgetTickParticipant implements CognitionTickParticipant {

    private final DriveOrchestrator driveOrchestrator;
    private final @Nullable AppraisalTickParticipant appraisalParticipant;
    private final @Nullable GutFeelingParticipant gutFeelingParticipant;
    private final @Nullable MoodOrchestrator moodOrchestrator;
    private final CognitiveDefaultsRegistry defaultsRegistry;
    private final @Nullable CognitionConfig cognitionConfig;
    private final @Nullable CuriosityDrive curiosityDrive;
    private final @Nullable CognitiveResourcePool cognitiveResourcePool;
    private final           ConcurrentHashMap<String, SearchBudget> results = new ConcurrentHashMap<>();



    public SearchBudgetTickParticipant(
            DriveOrchestrator driveOrchestrator,
            @Nullable AppraisalTickParticipant appraisalParticipant,
            @Nullable GutFeelingParticipant gutFeelingParticipant,
            @Nullable MoodOrchestrator moodOrchestrator,
            CognitiveDefaultsRegistry defaultsRegistry,
            @Nullable CognitionConfig cognitionConfig,
            @Nullable CuriosityDrive curiosityDrive,
            @Nullable CognitiveResourcePool cognitiveResourcePool) {
        this.driveOrchestrator = driveOrchestrator;
        this.appraisalParticipant = appraisalParticipant;
        this.gutFeelingParticipant = gutFeelingParticipant;
        this.moodOrchestrator = moodOrchestrator;
        this.defaultsRegistry = defaultsRegistry;
        this.cognitionConfig = cognitionConfig;
        this.curiosityDrive = curiosityDrive;
        this.cognitiveResourcePool = cognitiveResourcePool;
    }

    @Override
    public void tick(CognitionTickContext context) {
        String agentId  = context.agentId();
        String tenantId = context.tenantId();
        String key      = agentId + ":" + tenantId;
        results.remove(key);

        var driveOpt = driveOrchestrator.currentDrives(agentId, tenantId);
        if (driveOpt.isEmpty()) {return;}
        DriveProfile profile = driveOpt.get();

        var defaults = defaultsRegistry.forAgentOrDefaults(agentId);
        SearchBudgetConfig config = defaults.searchBudgetConfig() != null
                                    ? defaults.searchBudgetConfig() : SearchBudgetConfig.defaults();

        double    compositeIntensity = profile.compositeMotivation();
        DriveAxis dominant           = profile.dominantDrive();

        double threatIntensity = threatIntensity(agentId, tenantId);
        double moodPleasure    = moodPleasure(agentId, tenantId);
        var    gutValence      = gutValence(agentId, tenantId);

        HabituationState habituation =
                appraisalParticipant != null ? appraisalParticipant.currentHabituation(agentId, tenantId) : null;
        HabituationConfig habConfig =
                defaults.habituationConfig() != null ? defaults.habituationConfig() : null;
        double arousal = arousalScore(agentId, tenantId);

        double breadth    = computeBreadth(compositeIntensity, dominant, config, moodPleasure, gutValence);
        double laterality = computeLaterality(compositeIntensity, dominant, config, threatIntensity);
        double depth      = computeDepth(compositeIntensity, dominant, config);
        double convergence = computeConvergence(compositeIntensity, dominant, config, gutValence,
                                                habituation, habConfig, arousal);

        // Channel modulation — additive, after base computation
        if (curiosityDrive != null && config.channelModulation() != null) {
            var channel = curiosityDrive.lastChannel();
            if (channel != null) {
                var mod = config.channelModulation();
                if (channel.focus() == CuriosityChannel.FocusType.SPECIFIC) {
                    depth += channel.gapProximity() * mod.specificDepthBoost();
                    breadth -= channel.gapProximity() * mod.specificBreadthPenalty();
                } else {
                    breadth += mod.diversiveBreadthBoost();
                    laterality += mod.diversiveLateralityBoost();
                    convergence -= mod.diversiveConvergencePenalty();
                }
                if (channel.hedonic() == CuriosityChannel.HedonicType.D_TYPE) {
                    convergence += mod.deprivationConvergenceBoost();
                } else if (channel.hedonic() == CuriosityChannel.HedonicType.BLENDED) {
                    convergence += mod.deprivationConvergenceBoost() * 0.5; // D-type halves modulation — Litman (2008) deprivation curiosity is narrower
                }
                laterality += (1.0 - channel.comprehension()) * mod.lowComprehensionLateralityBoost();
                convergence += channel.comprehension() * mod.highComprehensionConvergenceBoost();
            }
        }

        // Fatigue reallocation — per-parameter signed effects (Hockey 1997)
        SearchFatigueEffects fatigueEffects = config.fatigueEffects();
        if (fatigueEffects != null && cognitiveResourcePool != null) {
            var poolConfig = defaults.cognitiveResourcePoolConfig();
            if (compositeIntensity > config.extremePeak()) {
                cognitiveResourcePool.recordEffort(agentId, tenantId, 1.0);
            } else if (poolConfig != null) {
                cognitiveResourcePool.recordRecovery(agentId, tenantId, poolConfig.recoveryRate());
            }
            double depletion = cognitiveResourcePool.currentDepletion(agentId, tenantId);
            if (depletion > 0.0) {
                depth += depletion * fatigueEffects.depthEffect();
                breadth += depletion * fatigueEffects.breadthEffect();
                laterality += depletion * fatigueEffects.lateralityEffect();
                convergence += depletion * fatigueEffects.convergenceEffect();
            }
        }

        // Capacity ceiling — bounds parameters by cognitive capacity (Halford/Mednick/Engle)
        double depthGap = 0.0, breadthGap = 0.0, lateralityGap = 0.0;
        double rc = -1.0, ar = -1.0, wm = -1.0;
        boolean capacityConstrained = false;
        var capacity = defaults.capacityConfig();
        if (capacity != null) {
            rc = capacity.relationalComplexityOrDefault();
            wm = capacity.workingMemorySpanOrDefault();
            ar = capacity.associativeReachOrDefault();

            depthGap = Math.max(0.0, depth - rc);
            breadthGap = Math.max(0.0, breadth - wm);
            lateralityGap = Math.max(0.0, laterality - ar);

            depth = Math.min(rc, depth);
            breadth = Math.min(wm, breadth);
            laterality = Math.min(ar, laterality);

            double maxGap = Math.max(depthGap, Math.max(breadthGap, lateralityGap));
            capacityConstrained = maxGap > 0.05;
        }

        boolean extremeActivation = (config.extremeInteraction() != null || config.fatigueEffects() != null)
                && compositeIntensity > config.extremePeak();

        SearchMotivation source = determineSource(profile, threatIntensity);

        results.put(key, new SearchBudget(
                clamp01(breadth), clamp01(laterality), clamp01(depth), clamp01(convergence),
                source, extremeActivation, capacityConstrained, depthGap, breadthGap, lateralityGap,
                rc, ar, wm, Instant.now()));
    }

    public Optional<SearchBudget> currentBudget(String agentId, String tenantId) {
        return Optional.ofNullable(results.get(agentId + ":" + tenantId));
    }

    // --- per-parameter computation ---

    // Drive modulation magnitudes calibrated empirically. Relative ordering grounded in
    // Guilford (1967) fluency and DeYoung (2015) Openness/Intellect.
    private double computeBreadth(double intensity, DriveAxis dominant,
                                  SearchBudgetConfig config, double moodPleasure,
                                  @Nullable GutValence gut) {
        ExtremeInteractionConfig ext  = config.extremeInteraction();
        double                   peak = ext != null ? ext.breadthPeakOrDefault(config.extremePeak()) : config.extremePeak();
        double base = ext != null
                      ? activationWithDecay(intensity, config.breadthSteepness(), config.breadthMidpoint(), ext.breadthDecay(), peak)
                      : logistic(intensity, config.breadthSteepness(), config.breadthMidpoint());
        double ampThreshold = ext != null ? ext.amplificationThresholdOrDefault(peak) : peak;
        double scale = modulationScale(ext != null ? ext.breadthAmplification() : 0.0, intensity, ampThreshold);
        if (dominant == DriveAxis.CURIOSITY) {base += 0.15 * scale;}
        if (dominant == DriveAxis.AFFILIATION) {base += 0.08 * scale;}
        if (moodPleasure > 0.1) {base += moodPleasure * 0.1;}
        if (gut == GutValence.APPROACH) {base += 0.05;}
        return base;
    }

    // Drive modulations calibrated empirically. Threat collapse grounded in Frijda (1986).
    private double computeLaterality(double intensity, DriveAxis dominant,
                                     SearchBudgetConfig config, double threatIntensity) {
        if (threatIntensity > config.lateralityThreatFloor()) {return 0.0;}
        ExtremeInteractionConfig ext  = config.extremeInteraction();
        double                   peak = ext != null ? ext.lateralityPeakOrDefault(config.extremePeak()) : config.extremePeak();
        double base = ext != null
                      ? activationWithDecay(intensity, config.lateralitySteepness(), config.lateralityMidpoint(), ext.lateralityDecay(), peak)
                      : logistic(intensity, config.lateralitySteepness(), config.lateralityMidpoint());
        double ampThreshold = ext != null ? ext.amplificationThresholdOrDefault(peak) : peak;
        double scale = modulationScale(ext != null ? ext.lateralityAmplification() : 0.0, intensity, ampThreshold);
        if (dominant == DriveAxis.CURIOSITY) {base += 0.1 * scale;}
        if (dominant == DriveAxis.AUTONOMY) {base += 0.08 * scale;}
        return base;
    }

    // Drive modulations calibrated empirically. Conscientiousness→depth grounded in
    // Krueger et al. (2012) Disinhibition factor.
    private double computeDepth(double intensity, DriveAxis dominant, SearchBudgetConfig config) {
        ExtremeInteractionConfig ext  = config.extremeInteraction();
        double                   peak = ext != null ? ext.depthPeakOrDefault(config.extremePeak()) : config.extremePeak();
        double base = ext != null
                      ? activationWithDecay(intensity, config.depthSteepness(), config.depthMidpoint(), ext.depthDecay(), peak)
                      : logistic(intensity, config.depthSteepness(), config.depthMidpoint());
        double ampThreshold = ext != null ? ext.amplificationThresholdOrDefault(peak) : peak;
        double scale = modulationScale(ext != null ? ext.depthAmplification() : 0.0, intensity, ampThreshold);
        if (dominant == DriveAxis.COMPETENCE) {base += 0.15 * scale;}
        if (dominant == DriveAxis.AUTONOMY) {base += 0.1 * scale;}
        if (dominant == DriveAxis.AFFILIATION) {base -= 0.1 * scale;}
        return base;
    }

    // Drive modulations calibrated empirically. Perseveration grounding:
    // Davis & Nolen-Hoeksema (1998).
    private double computeConvergence(double intensity, DriveAxis dominant,
                                      SearchBudgetConfig config, @Nullable GutValence gut,
                                      @Nullable HabituationState habituation,
                                      @Nullable HabituationConfig habConfig,
                                      double arousalScore) {
        ExtremeInteractionConfig ext  = config.extremeInteraction();
        double                   peak = ext != null ? ext.convergencePeakOrDefault(config.extremePeak()) : config.extremePeak();
        double base = ext != null
                      ? activationWithDecay(intensity, config.convergenceSteepness(), config.convergenceMidpoint(), ext.convergenceDecay(), peak)
                      : logistic(intensity, config.convergenceSteepness(), config.convergenceMidpoint());
        double ampThreshold = ext != null ? ext.amplificationThresholdOrDefault(peak) : peak;
        double scale = modulationScale(ext != null ? ext.convergenceAmplification() : 0.0, intensity, ampThreshold);
        if (dominant == DriveAxis.CURIOSITY) {base -= 0.1 * scale;}
        if (dominant == DriveAxis.COMPETENCE) {base += 0.1 * scale;}
        if (dominant == DriveAxis.AFFILIATION) {base += 0.1 * scale;}
        if (dominant == DriveAxis.AUTONOMY) {base -= 0.08 * scale;}
        if (gut == GutValence.AVOID) {base += 0.1;}

        if (habituation != null && habConfig != null) {
            boolean anyFailure = habituation.observationCounts().entrySet().stream()
                                            .anyMatch(e -> e.getValue() > habConfig.repetitionTolerance()
                                                           && habituation.noveltyScores().getOrDefault(e.getKey(), 1.0)
                                                              > habConfig.noveltyThreshold());
            if (arousalScore + (anyFailure ? 1.0 : 0.0) > config.pathologicalThreshold()) {
                base = 0.0;
            }
        }
        return base;
    }

    // --- source selection (Frijda control precedence) ---

    private SearchMotivation determineSource(DriveProfile profile, double threatIntensity) {
        double maxDriveIntensity = profile.drives().values().stream()
                .mapToDouble(d -> d.intensity()).max().orElse(0.0);

        if (threatIntensity > maxDriveIntensity) return SearchMotivation.THREAT;
        return SearchMotivation.fromDriveAxis(profile.dominantDrive());
    }

    // --- input accessors ---

    private double threatIntensity(String agentId, String tenantId) {
        if (appraisalParticipant == null) return 0.0;
        return appraisalParticipant.currentResult(agentId, tenantId)
                .map(r -> r.actionTendencies().stream()
                        .filter(t -> t.readiness() == ActionReadiness.AVOIDANCE)
                        .mapToDouble(ActionTendency::intensity)
                        .max().orElse(0.0))
                .orElse(0.0);
    }

    private double moodPleasure(String agentId, String tenantId) {
        if (moodOrchestrator == null) return 0.0;
        return moodOrchestrator.currentMood(agentId, tenantId)
                .map(m -> m.pleasure())
                .orElse(0.0);
    }

    private @Nullable GutValence gutValence(String agentId, String tenantId) {
        if (gutFeelingParticipant == null) return null;
        return gutFeelingParticipant.currentResult(agentId, tenantId)
                .map(g -> g.valence())
                .orElse(null);
    }

    private double arousalScore(String agentId, String tenantId) {
        if (appraisalParticipant == null) {return 0.0;}
        return appraisalParticipant.currentResult(agentId, tenantId)
                                   .map(r -> {
                                       if (r.emotions().isEmpty()) {return 0.0;}
                                       double totalIntensity = r.emotions().stream()
                                                                .mapToDouble(e -> e.intensity()).sum();
                                       if (totalIntensity <= 0) {return 0.0;}
                                       return r.emotions().stream()
                                               .mapToDouble(e -> e.intensity() * e.pad().arousal())
                                               .sum() / totalIntensity;
                                   })
                                   .orElse(0.0);
    }


    // --- activation functions ---

    static double logistic(double x, double k, double midpoint) {
        return 1.0 / (1.0 + Math.exp(-k * (x - midpoint)));
    }

    static double activationWithDecay(double x, double k, double midpoint, double decay, double peak) {
        double base = logistic(x, k, midpoint);
        double penalty = Math.exp(-decay * Math.max(0.0, x - peak));
        return base * penalty;
    }

    private static double modulationScale(double amplificationRate, double intensity, double peak) {
        double scale = 1.0 + amplificationRate * Math.max(0.0, intensity - peak);
        return Math.max(-1.0, scale);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
