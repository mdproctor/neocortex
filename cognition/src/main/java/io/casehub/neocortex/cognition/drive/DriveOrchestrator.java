package io.casehub.neocortex.cognition.drive;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.mood.MoodOrchestrator;
import io.casehub.neocortex.cognition.narrative.NarrativeModulation;
import io.casehub.neocortex.cognition.narrative.NarrativeOrchestrator;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class DriveOrchestrator {

    private final DriveSource curiosity;
    private final DriveSource competence;
    private final DriveSource affiliation;
    private final DriveSource autonomy;
    private final MoodOrchestrator moodOrchestrator;
    private final DriveComposer composer;
    private final DriveConfig config;
    private final Clock clock;
    private final @Nullable NarrativeOrchestrator narrativeOrchestrator;
    private volatile        io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant subThoughtParticipant;


    private final ConcurrentHashMap<String, DriveProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<DriveEvaluation>> lastEvaluations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<DriveAxis, Double>> pendingAppraisalModulations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<DriveAxis, String>> pendingAppraisalDescriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastTickTimes = new ConcurrentHashMap<>();

    public DriveOrchestrator(DriveSource curiosity, DriveSource competence,
                             DriveSource affiliation, DriveSource autonomy,
                             MoodOrchestrator moodOrchestrator, DriveComposer composer,
                             DriveConfig config) {
        this(curiosity, competence, affiliation, autonomy, moodOrchestrator,
                composer, config, Clock.systemUTC(), null);
    }

    DriveOrchestrator(DriveSource curiosity, DriveSource competence,
                      DriveSource affiliation, DriveSource autonomy,
                      MoodOrchestrator moodOrchestrator, DriveComposer composer,
                      DriveConfig config, Clock clock) {
        this(curiosity, competence, affiliation, autonomy, moodOrchestrator,
                composer, config, clock, null);
    }

    public DriveOrchestrator(DriveSource curiosity, DriveSource competence,
                      DriveSource affiliation, DriveSource autonomy,
                      MoodOrchestrator moodOrchestrator, DriveComposer composer,
                      DriveConfig config, Clock clock,
                      @Nullable NarrativeOrchestrator narrativeOrchestrator) {
        this.curiosity = curiosity;
        this.competence = competence;
        this.affiliation = affiliation;
        this.autonomy = autonomy;
        this.moodOrchestrator = moodOrchestrator;
        this.composer = composer;
        this.config = config;
        this.clock = clock;
        this.narrativeOrchestrator = narrativeOrchestrator;
    }

    public DriveTick tick(String agentId, String tenantId, AgentDescriptor descriptor) {
        var key = agentId + ":" + tenantId;
        lastTickTimes.put(key, Instant.now());
        return withLock(key, () -> {
            var raw = new EnumMap<DriveAxis, DriveIntensity>(DriveAxis.class);
            raw.put(DriveAxis.CURIOSITY, curiosity.evaluate(agentId, tenantId));
            raw.put(DriveAxis.COMPETENCE, competence.evaluate(agentId, tenantId));
            raw.put(DriveAxis.AFFILIATION, affiliation.evaluate(agentId, tenantId));
            raw.put(DriveAxis.AUTONOMY, autonomy.evaluate(agentId, tenantId));

            var mood = moodOrchestrator.currentMood(agentId, tenantId).orElse(null);
            var disposition = descriptor.disposition();
            var now = Instant.now(clock);

            var modulations = new java.util.ArrayList<ModulationLayer>();
            if (narrativeOrchestrator != null) {
                narrativeOrchestrator.currentNarrative(agentId, tenantId)
                        .map(NarrativeModulation::compute)
                        .ifPresent(m -> modulations.add(new ModulationLayer(m, config.narrativeModulationStrength(), "narrative")));
            }
            if (subThoughtParticipant != null) {
                var stResult = subThoughtParticipant.currentSubThoughts(agentId, tenantId);
                if (stResult != null && !stResult.isEmpty()) {
                    modulations.add(new ModulationLayer(
                            io.casehub.neocortex.cognition.subthought.SubThoughtModulation.compute(stResult),
                            config.subThoughtModulationStrength(), "sub-thought"));
                }
            }
            var appraisalMod = pendingAppraisalModulations.remove(key);
            if (appraisalMod != null) {
                var appraisalDesc = pendingAppraisalDescriptions.remove(key);
                modulations.add(new ModulationLayer(appraisalMod, 1.0, "appraisal", appraisalDesc));
            }

            var result = composer.composeWithEvaluations(raw, disposition, mood, modulations, config,
                    agentId, tenantId, now);
            var newProfile = result.profile();
            lastEvaluations.put(key, result.evaluations());

            var previous = profiles.get(key);
            newProfile = applySmoothing(newProfile, previous, config.smoothingAlpha());
            profiles.put(key, newProfile);

            if (previous == null) {
                return new DriveTick.Updated(newProfile, newProfile,
                        List.of(DriveAxis.values()));
            }

            var changed = new ArrayList<DriveAxis>();
            for (var axis : DriveAxis.values()) {
                var prevI = previous.drives().get(axis);
                var newI = newProfile.drives().get(axis);
                if (prevI == null || newI == null) {
                    changed.add(axis);
                } else if (Math.abs(prevI.intensity() - newI.intensity()) > config.changeThreshold()) {
                    changed.add(axis);
                }
            }

            if (changed.isEmpty()) {
                return new DriveTick.NoChange("all axes within threshold");
            }
            return new DriveTick.Updated(previous, newProfile, changed);
        });
    }

    public void recordAppraisalModulation(String agentId, String tenantId, Map<DriveAxis, Double> modulation) {
        recordAppraisalModulation(agentId, tenantId, modulation, null);
    }

    public void recordAppraisalModulation(String agentId, String tenantId,
                                           Map<DriveAxis, Double> modulation,
                                           @Nullable Map<DriveAxis, String> descriptions) {
        var key = agentId + ":" + tenantId;
        pendingAppraisalModulations.put(key, modulation);
        if (descriptions != null) {
            pendingAppraisalDescriptions.put(key, descriptions);
        }
    }

    public void setSubThoughtParticipant(io.casehub.neocortex.cognition.subthought.SubThoughtTickParticipant participant) {
        this.subThoughtParticipant = participant;
    }


    public Optional<DriveProfile> currentDrives(String agentId, String tenantId) {
        return Optional.ofNullable(profiles.get(agentId + ":" + tenantId));
    }

    public List<DriveEvaluation> lastDriveEvaluations(String agentId, String tenantId) {
        return lastEvaluations.getOrDefault(agentId + ":" + tenantId, List.of());
    }

    private static DriveProfile applySmoothing(DriveProfile current, @Nullable DriveProfile previous, double alpha) {
        if (previous == null || alpha >= 1.0) {return current;}
        var     smoothed   = new EnumMap<DriveAxis, DriveIntensity>(DriveAxis.class);
        boolean anyChanged = false;
        for (var entry : current.drives().entrySet()) {
            var axis   = entry.getKey();
            var newDi  = entry.getValue();
            var prevDi = previous.drives().get(axis);
            if (prevDi == null || newDi.intensity() >= prevDi.intensity()) {
                smoothed.put(axis, newDi);
            } else {
                double blended = alpha * newDi.intensity() + (1 - alpha) * prevDi.intensity();
                smoothed.put(axis, new DriveIntensity(axis, Math.clamp(blended, 0.0, 1.0), newDi.trigger()));
                anyChanged = true;
            }
        }
        if (!anyChanged) {return current;}
        DriveAxis dominant = smoothed.entrySet().stream()
                .max(Map.Entry.comparingByValue((a, b) -> Double.compare(a.intensity(), b.intensity())))
                .map(Map.Entry::getKey).orElse(current.dominantDrive());
        double composite = smoothed.values().stream().mapToDouble(DriveIntensity::intensity).average().orElse(0.0);
        return new DriveProfile(current.agentId(), current.tenantId(), smoothed,
                                Math.clamp(composite, 0.0, 1.0), dominant, current.evaluatedAt());
    }


    public void evictIdle(java.time.Duration maxIdle) {
        var cutoff = Instant.now().minus(maxIdle);
        lastTickTimes.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                var key = e.getKey();
                profiles.remove(key);
                lastEvaluations.remove(key);
                tickLocks.remove(key);
                pendingAppraisalModulations.remove(key);
                pendingAppraisalDescriptions.remove(key);
                return true;
            }
            return false;
        });
    }

    private <T> T withLock(String key, Supplier<T> action) {
        var lock = tickLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
