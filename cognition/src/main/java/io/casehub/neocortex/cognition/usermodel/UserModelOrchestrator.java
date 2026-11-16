package io.casehub.neocortex.cognition.usermodel;

import io.casehub.neocortex.cognition.relationship.RelationshipStageConfig;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UserModelOrchestrator {

    private static final Logger LOG = Logger.getLogger(UserModelOrchestrator.class.getName());

    private static final String SYSTEM_PROMPT = """
            You are analysing interaction history between an agent and a person to update \
            a behavioral profile. Given the current profile and recent interactions, \
            produce an updated assessment.

            Respond with JSON only:
            {"communicationStyle":"<how this person communicates>",\
            "topicsOfInterest":"<topics this person frequently discusses>",\
            "preferences":"<observed preferences>",\
            "synthesisNotes":"<notable patterns or changes>"}

            If insufficient new information exists for a field, repeat the current value unchanged. \
            Empty string if no data at all.""";

    private final UserProfileMemory profileMemory;
    private final UserModelConfig config;
    private final @Nullable SessionPipeline sessionPipeline;
    private final io.casehub.neocortex.cognitive.index.@Nullable CognitiveMemoryRetriever memoryRetriever;

    private final ConcurrentHashMap<String, SubjectState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();

    public UserModelOrchestrator(UserProfileMemory profileMemory,
                                 UserModelConfig config,
                                 @Nullable SessionPipeline sessionPipeline,
                                 io.casehub.neocortex.cognitive.index.@Nullable CognitiveMemoryRetriever memoryRetriever) {
        this.profileMemory = profileMemory;
        this.config = config;
        this.sessionPipeline = sessionPipeline;
        this.memoryRetriever = memoryRetriever;
    }

    public UserModelOrchestrator(UserProfileMemory profileMemory,
                                 UserModelConfig config,
                                 @Nullable SessionPipeline sessionPipeline) {
        this(profileMemory, config, sessionPipeline, null);
    }

    public void record(InteractionSignal signal,
                       String agentId, String subjectId, String tenantId) {
        var key = stateKey(agentId, subjectId, tenantId);
        var state = states.computeIfAbsent(key,
                k -> new SubjectState(agentId, subjectId, tenantId));
        synchronized (state) {
            switch (signal.quality()) {
                case POSITIVE -> state.pendingPositive++;
                case NEGATIVE -> state.pendingNegative++;
                case NEUTRAL -> state.pendingNeutral++;
            }
            state.textBuffer.append("[").append(signal.quality().name())
                    .append("] ").append(signal.description()).append("\n");
            state.pendingInteractions++;
            state.lastInteractionTimestamp = Instant.now();
            state.lastActivityTimestamp = Instant.now();
        }
    }

    public UserModelTick tick(String agentId, String subjectId, String tenantId) {
        var key = stateKey(agentId, subjectId, tenantId);
        var state = states.get(key);

        if (state == null) {
            var existing = profileMemory.lookup(agentId, subjectId, tenantId).orElse(null);
            if (existing != null) {
                state = states.computeIfAbsent(key,
                        k -> SubjectState.fromProfile(existing));
            } else {
                return new UserModelTick.Unchanged("no signals");
            }
        }

        final var finalState = state;
        var result = withLock(key, () -> doTick(finalState));
        evictStale();
        return result;
    }

    public @Nullable UserProfile currentProfile(String agentId, String subjectId, String tenantId) {
        var state = states.get(stateKey(agentId, subjectId, tenantId));
        return state != null ? state.currentProfile : null;
    }

    public java.util.List<UserProfile> activeProfiles(String agentId, String tenantId) {
        var prefix = agentId + ":";
        var suffix = ":" + tenantId;
        return states.entrySet().stream()
                     .filter(e -> e.getKey().startsWith(prefix) && e.getKey().endsWith(suffix))
                     .map(e -> e.getValue().currentProfile)
                     .filter(java.util.Objects::nonNull)
                     .toList();
    }

    public void seedProfile(UserProfile profile) {
        String key   = profile.agentId() + ":" + profile.subjectId() + ":" + profile.tenantId();
        var    state = new SubjectState(profile.agentId(), profile.subjectId(), profile.tenantId());
        state.currentProfile           = profile;
        state.totalInteractions        = profile.totalInteractions();
        state.cumulativePositive       = profile.positiveSignals();
        state.cumulativeNegative       = profile.negativeSignals();
        state.cumulativeNeutral        = profile.neutralSignals();
        state.lastInteractionTimestamp = profile.lastInteraction();
        states.put(key, state);
    }


    public static double computeFamiliarity(int positive, int negative, int neutral,
                                     RelationshipStageConfig stageConfig,
                                     long ticksSinceLastInteraction) {
        int total = positive + negative + neutral;
        if (total == 0) return 0.0;
        double affect = (positive * stageConfig.positiveWeight()
                - negative * stageConfig.negativeWeight()) / total;
        double affectNorm = Math.max(0.0, Math.min(1.0, (affect + 1.0) / 2.0));
        double volumeFactor = 1.0 - 1.0 / (1.0 + total * 0.1);
        double score = affectNorm * volumeFactor;
        return score * Math.pow(1.0 - stageConfig.decayRate(), ticksSinceLastInteraction);
    }

    public static double computeFamiliarity(int positive, int negative, int neutral,
                                            RelationshipStageConfig stageConfig,
                                            long ticksSinceLastInteraction,
                                            double trustFormationRate) {
        int total = positive + negative + neutral;
        if (total == 0) {return 0.0;}
        double effectivePositiveWeight = stageConfig.positiveWeight() * (0.5 + trustFormationRate);
        double effectiveNegativeWeight = stageConfig.negativeWeight() * (1.5 - trustFormationRate);
        double affect = (positive * effectivePositiveWeight
                         - negative * effectiveNegativeWeight) / total;
        double affectNorm   = Math.max(0.0, Math.min(1.0, (affect + 1.0) / 2.0));
        double volumeFactor = 1.0 - 1.0 / (1.0 + total * 0.1);
        double score        = affectNorm * volumeFactor;
        return score * Math.pow(1.0 - stageConfig.decayRate(), ticksSinceLastInteraction);
    }

    public static double computeFamiliarity(int positive, int negative, int neutral,
                                            RelationshipStageConfig stageConfig,
                                            long ticksSinceLastInteraction,
                                            double trustFormationRate,
                                            io.casehub.neocortex.cognitive.index.ConflictInterpretation conflictInterpretation) {
        int total = positive + negative + neutral;
        if (total == 0) {return 0.0;}
        double effectivePositiveWeight = stageConfig.positiveWeight() * (0.5 + trustFormationRate);
        double conflictMultiplier = switch (conflictInterpretation) {
            case REPAIR -> 0.6;
            case INFORMATION -> 0.9;
            case NEUTRAL -> 1.0;
            case DISENGAGE -> 1.4;
        };
        double effectiveNegativeWeight = stageConfig.negativeWeight() * (1.5 - trustFormationRate) * conflictMultiplier;
        double affect = (positive * effectivePositiveWeight
                         - negative * effectiveNegativeWeight) / total;
        double affectNorm   = Math.max(0.0, Math.min(1.0, (affect + 1.0) / 2.0));
        double volumeFactor = 1.0 - 1.0 / (1.0 + total * 0.1);
        double score        = affectNorm * volumeFactor;
        return score * Math.pow(1.0 - stageConfig.decayRate(), ticksSinceLastInteraction);
    }


    private UserModelTick doTick(SubjectState state) {
        int snapPositive, snapNegative, snapNeutral, snapInteractions;
        String snapText;

        synchronized (state) {
            snapPositive = state.pendingPositive;
            snapNegative = state.pendingNegative;
            snapNeutral = state.pendingNeutral;
            snapInteractions = state.pendingInteractions;
            snapText = state.textBuffer.toString();

            state.pendingPositive = 0;
            state.pendingNegative = 0;
            state.pendingNeutral = 0;
            state.pendingInteractions = 0;
            state.textBuffer.setLength(0);
        }

        if (snapInteractions == 0 && state.currentProfile == null) {
            return new UserModelTick.Unchanged("no signals");
        }

        state.cumulativePositive += snapPositive;
        state.cumulativeNegative += snapNegative;
        state.cumulativeNeutral += snapNeutral;
        state.totalInteractions += snapInteractions;

        var now = Instant.now();
        long ticksSinceInteraction = 0;
        if (state.lastInteractionTimestamp != null) {
            long elapsed = Duration.between(state.lastInteractionTimestamp, now).toMillis();
            long interval = config.expectedTickInterval().toMillis();
            ticksSinceInteraction = interval > 0 ? elapsed / interval : 0;
        }

        var stageConfig = config.stageConfig();
        double familiarity = computeFamiliarity(
                state.cumulativePositive, state.cumulativeNegative,
                state.cumulativeNeutral, stageConfig, ticksSinceInteraction);
        String stage = stageConfig.resolveStage(familiarity);

        var previousProfile = state.currentProfile;
        boolean stageChanged = previousProfile == null
                || !stage.equals(previousProfile.relationshipStage());
        boolean scoreChanged = previousProfile == null
                || Math.abs(familiarity - previousProfile.familiarityScore()) > 0.001;

        if (!stageChanged && !scoreChanged && snapInteractions == 0) {
            return new UserModelTick.Unchanged(null);
        }

        String commStyle = previousProfile != null ? previousProfile.communicationStyle() : null;
        String topics = previousProfile != null ? previousProfile.topicsOfInterest() : null;
        String prefs = previousProfile != null ? previousProfile.preferences() : null;
        String notes = previousProfile != null ? previousProfile.synthesisNotes() : null;
        Instant lastSynthesised = previousProfile != null ? previousProfile.lastSynthesised() : null;
        Instant profileCreated = previousProfile != null ? previousProfile.profileCreated() : now;
        boolean synthesised = false;

        if (shouldSynthesise(state, snapText)) {
            var result = invokeLlmSynthesis(state, snapText, stage, familiarity);
            if (result != null) {
                commStyle = result.communicationStyle();
                topics = result.topicsOfInterest();
                prefs = result.preferences();
                notes = result.synthesisNotes();
                lastSynthesised = now;
                synthesised = true;
            }
            state.lastSynthesisTimestamp = now;
        }

        var profile = new UserProfile(
                state.agentId, state.subjectId, state.tenantId,
                stage, familiarity,
                state.totalInteractions,
                state.cumulativePositive, state.cumulativeNegative, state.cumulativeNeutral,
                state.lastInteractionTimestamp != null ? state.lastInteractionTimestamp : now,
                profileCreated, lastSynthesised,
                commStyle, topics, prefs, notes, Map.of());

        state.currentProfile = profile;
        state.lastActivityTimestamp = now;

        try {
            profileMemory.store(profile);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to persist profile for "
                    + state.subjectId, e);
        }

        if (synthesised) {
            return new UserModelTick.Synthesised(profile, previousProfile);
        }
        return new UserModelTick.Updated(profile);
    }

    private boolean shouldSynthesise(SubjectState state, String text) {
        if (text.isEmpty()) return false;
        long lines = text.chars().filter(c -> c == '\n').count();
        if (lines < config.minSignalsForSynthesis()) return false;
        if (state.lastSynthesisTimestamp != null) {
            var elapsed = Duration.between(state.lastSynthesisTimestamp, Instant.now());
            if (elapsed.compareTo(config.synthesisCooldown()) < 0) return false;
        }
        return true;
    }

    private @Nullable SynthesisFields invokeLlmSynthesis(
            SubjectState state, String text, String stage, double familiarity) {
        try {
            var previousStyle = state.currentProfile != null
                    ? state.currentProfile.communicationStyle() : "not yet assessed";
            var previousTopics = state.currentProfile != null
                    ? state.currentProfile.topicsOfInterest() : "not yet assessed";
            var previousPrefs = state.currentProfile != null
                    ? state.currentProfile.preferences() : "not yet assessed";

            int maxObs = config.maxObservationsInPrompt();
            String[] lines = text.split("\n");
            var recentText = new StringBuilder();
            int start = Math.max(0, lines.length - maxObs);
            for (int i = start; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    recentText.append(lines[i]).append("\n");
                }
            }

            var userPrompt = String.format("""
                    Current profile:
                      Stage: %s (familiarity: %.2f)
                      Interactions: %d (%d↑ %d↓ %d→)
                      Previous communication style: %s
                      Previous topics: %s
                      Previous preferences: %s

                    Recent interactions (%d since last synthesis):
                    %s""",
                    stage, familiarity,
                    state.totalInteractions,
                    state.cumulativePositive, state.cumulativeNegative, state.cumulativeNeutral,
                    previousStyle != null ? previousStyle : "not yet assessed",
                    previousTopics != null ? previousTopics : "not yet assessed",
                    previousPrefs != null ? previousPrefs : "not yet assessed",
                    lines.length, recentText);

            if (memoryRetriever != null) {
                var memories = memoryRetriever.query(
                        io.casehub.neocortex.memory.experience.ExperienceQuery.forAgent(state.agentId, state.tenantId).withLimit(5));
                if (!memories.isEmpty()) {
                    var memSb = new StringBuilder("\n\nRelevant experience memories:\n");
                    for (var m : memories) {
                        var mText = m.text();
                        if (mText != null && !mText.isBlank()) {
                            memSb.append("- ").append(mText.length() > 120 ? mText.substring(0, 120) : mText).append("\n");
                        }
                    }
                    userPrompt = userPrompt + memSb;
                }
            }

            var responseText = invokeText(SYSTEM_PROMPT, userPrompt, state.agentId);
            if (responseText == null) return null;
            return parseResult(responseText);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "LLM synthesis failed for " + state.subjectId, e);
            return null;
        }
    }

    private @Nullable String invokeText(String systemPrompt, String userPrompt, String agentId) {
        var pipeline = this.sessionPipeline;
        if (pipeline == null) return null;
        try {
            var session = pipeline.openSession("user-model-" + agentId, AgentSessionInit.of(systemPrompt));
            var responseText = new StringBuilder();
            session.query(userPrompt).subscribe().asStream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .forEach(responseText::append);
            return responseText.isEmpty() ? null : responseText.toString();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Agent invocation failed", e);
            return null;
        }
    }

    private @Nullable SynthesisFields parseResult(String json) {
        try {
            json = json.strip();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").strip();
            }
            var commStyle = extractJsonField(json, "communicationStyle");
            var topics = extractJsonField(json, "topicsOfInterest");
            var prefs = extractJsonField(json, "preferences");
            var notes = extractJsonField(json, "synthesisNotes");
            var result = new SynthesisFields(
                    emptyToNull(commStyle), emptyToNull(topics),
                    emptyToNull(prefs), emptyToNull(notes));
            if (result.communicationStyle() == null && result.topicsOfInterest() == null
                    && result.preferences() == null && result.synthesisNotes() == null) {
                return null;
            }
            return result;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse synthesis result: " + json, e);
            return null;
        }
    }

    private static @Nullable String extractJsonField(String json, String field) {
        var pattern = "\"" + field + "\"\\s*:\\s*\"";
        int start = json.indexOf(pattern.replace("\\s*", ""));
        if (start < 0) {
            start = json.indexOf("\"" + field + "\"");
            if (start < 0) return null;
            int colon = json.indexOf(':', start);
            if (colon < 0) return null;
            int quote = json.indexOf('"', colon + 1);
            if (quote < 0) {
                int nullStart = json.indexOf("null", colon);
                if (nullStart >= 0) return null;
                return null;
            }
            start = quote;
        } else {
            start = json.indexOf('"', json.indexOf(':', start));
        }
        start++;
        var sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static @Nullable String emptyToNull(String val) {
        return val != null && !val.isBlank() ? val : null;
    }

    private void evictStale() {
        var now = Instant.now();
        var timeout = config.evictionTimeout();
        states.entrySet().removeIf(entry -> {
            var st = entry.getValue();
            return st.lastActivityTimestamp != null
                    && Duration.between(st.lastActivityTimestamp, now).compareTo(timeout) > 0;
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

    private static String stateKey(String agentId, String subjectId, String tenantId) {
        return agentId + ":" + subjectId + ":" + tenantId;
    }

    record SynthesisFields(@Nullable String communicationStyle,
                           @Nullable String topicsOfInterest,
                           @Nullable String preferences,
                           @Nullable String synthesisNotes) {}

    static final class SubjectState {
        final String agentId;
        final String subjectId;
        final String tenantId;

        int pendingPositive;
        int pendingNegative;
        int pendingNeutral;
        int pendingInteractions;
        final StringBuilder textBuffer = new StringBuilder();

        int cumulativePositive;
        int cumulativeNegative;
        int cumulativeNeutral;
        int totalInteractions;

        @Nullable Instant lastInteractionTimestamp;
        @Nullable Instant lastSynthesisTimestamp;
        @Nullable Instant lastActivityTimestamp;
        @Nullable UserProfile currentProfile;

        SubjectState(String agentId, String subjectId, String tenantId) {
            this.agentId = agentId;
            this.subjectId = subjectId;
            this.tenantId = tenantId;
            this.lastActivityTimestamp = Instant.now();
        }

        static SubjectState fromProfile(UserProfile profile) {
            var state = new SubjectState(profile.agentId(), profile.subjectId(), profile.tenantId());
            state.cumulativePositive = profile.positiveSignals();
            state.cumulativeNegative = profile.negativeSignals();
            state.cumulativeNeutral = profile.neutralSignals();
            state.totalInteractions = profile.totalInteractions();
            state.lastInteractionTimestamp = profile.lastInteraction();
            state.lastSynthesisTimestamp = profile.lastSynthesised();
            state.currentProfile = profile;
            return state;
        }
    }
}
