package io.casehub.neocortex.cognition.innerlife;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.neocortex.cognition.core.TokenJaccardDistance;
import io.casehub.neocortex.cognition.drive.DriveAxis;
import io.casehub.neocortex.cognition.drive.DriveOrchestrator;
import io.casehub.neocortex.cognition.drive.DriveProfile;
import io.casehub.neocortex.cognition.prompt.CognitionPromptRenderer;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.neocortex.summarisation.LevelEvent;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.jspecify.annotations.Nullable;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class InnerLifeOrchestrator {

    private static final Logger LOG = Logger.getLogger(InnerLifeOrchestrator.class.getName());

    private static final String SYSTEM_PROMPT = """
            You are an agent with an inner life. Given your personality, recent observations, \
            reflections, and available channels, decide whether you are motivated to initiate \
            a conversation right now.

            Respond with JSON only: {"score": <0.0-1.0>, "content": "<what you want to say>", \
            "channelHint": "<suggested channel or null>"}

            Score 0.0 = no motivation. Score 1.0 = strongly motivated. Only produce content \
            if you genuinely have something worth saying. If unmotivated, set score low and \
            content to empty string.""";

    private final ReflectionOrchestrator reflectionOrchestrator;
    private final List<CivilityConstraint> civilityConstraints;
    private final InnerLifeConfig          config;
    private final DriveOrchestrator        driveOrchestrator;
    private final @Nullable SessionPipeline sessionPipeline;

    private final ConcurrentHashMap<String, AgentState> agentStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.time.Instant> lastTickTimes = new ConcurrentHashMap<>();

    public InnerLifeOrchestrator(
            final ReflectionOrchestrator reflectionOrchestrator,
            final List<CivilityConstraint> civilityConstraints,
            final InnerLifeConfig config,
            final DriveOrchestrator driveOrchestrator,
            @Nullable SessionPipeline sessionPipeline) {
        this.reflectionOrchestrator = reflectionOrchestrator;
        this.civilityConstraints = civilityConstraints;
        this.config = config;
        this.driveOrchestrator = driveOrchestrator;
        this.sessionPipeline = sessionPipeline;
    }

    public void observe(final LevelEvent<?> event, final AgentDescriptor descriptor) {
        var state = agentStates.computeIfAbsent(agentKey(descriptor), k -> new AgentState());
        synchronized (state) {
            state.eventBuffer.add(event);
            state.rawObservationText.append(event.payload().toString()).append('\n');
            state.observationCountSinceLastInitiation++;
            state.lastActivityTimestamp = Instant.now();
        }
    }

    public void observeResponse(final AgentDescriptor descriptor) {
        var state = agentStates.get(agentKey(descriptor));
        if (state != null) {
            synchronized (state) {
                state.consecutiveInitiationsWithoutResponse = 0;
            }
        }
    }

    public void reportDropped(String agentId, String tenantId, Instant userActivityAt) {
        var key   = tenantId + ":" + agentId;
        var state = agentStates.get(key);
        if (state == null) {
            LOG.log(Level.FINE, "reportDropped: no state for {0}", key);
            return;
        }
        synchronized (state) {
            if (state.consecutiveInitiationsWithoutResponse > 0) {
                state.consecutiveInitiationsWithoutResponse--;
            }
            if (!state.initiationTimestamps.isEmpty()) {
                state.initiationTimestamps.removeLast();
            }
            state.lastActivityTimestamp = userActivityAt;
        }
        LOG.log(Level.FINE, "Proactive turn dropped for {0}, userActivityAt={1}",
                new Object[]{key, userActivityAt});
    }


    public InnerLifeTick tick(final AgentDescriptor descriptor, final String channelContext) {
        return tick(descriptor, channelContext, List.of());
    }

    public InnerLifeTick tick(final AgentDescriptor descriptor, final String channelContext,
                              final List<CognitionPromptRenderer> additionalSections) {
        var agentKey = agentKey(descriptor);
        lastTickTimes.put(agentKey, java.time.Instant.now());
        var lock     = tickLocks.computeIfAbsent(agentKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return doTick(descriptor, channelContext, agentKey, additionalSections);
        } finally {
            lock.unlock();
        }
    }


    private InnerLifeTick doTick(final AgentDescriptor descriptor,
                                 final String channelContext,
                                 final String agentKey,
                                 final List<CognitionPromptRenderer> additionalSections) {
        var drivesOpt = driveOrchestrator.currentDrives(
                descriptor.agentId(), descriptor.tenancyId());
        if (drivesOpt.isEmpty()) {
            LOG.log(Level.WARNING,
                    "InnerLife tick called without current drive profile for "
                    + descriptor.agentId()
                    + " — ensure CognitionCore.tick() has run first");
            return new InnerLifeTick.Silent("drives not current");
        }

        var driveProfile = drivesOpt.get();
        var state        = agentStates.computeIfAbsent(agentKey, k -> new AgentState());

        List<LevelEvent<?>> eventSnapshot;
        String              rawTextSnapshot;
        int                 observationCount;
        synchronized (state) {
            eventSnapshot    = new ArrayList<>(state.eventBuffer);
            rawTextSnapshot  = state.rawObservationText.toString();
            observationCount = state.observationCountSinceLastInitiation;
            state.eventBuffer.clear();
            state.rawObservationText.setLength(0);
            state.observationCountSinceLastInitiation = 0;
        }

        var initiationContext = buildInitiationContext(state, descriptor);
        for (var constraint : civilityConstraints) {
            var check = constraint.permitInitiation(initiationContext);
            if (check instanceof CivilityCheck.Denied denied) {
                restoreBuffer(state, eventSnapshot, rawTextSnapshot, observationCount);
                return new InnerLifeTick.Silent(denied.reason());
            }
        }

        var nonVerbalTick = evaluateNonVerbal(driveProfile, state);
        if (nonVerbalTick != null) {
            state.lastActivityTimestamp = Instant.now();
            return nonVerbalTick;
        }

        state.lastActivityTimestamp = Instant.now();

        var gate = config.contentQualityGate();
        var now  = Instant.now();
        var timeSinceLastEval = state.lastLlmEvaluationTimestamp.equals(Instant.EPOCH)
                                ? Duration.ofDays(365)
                                : Duration.between(state.lastLlmEvaluationTimestamp, now);
        boolean quietPeriodTriggered = timeSinceLastEval.compareTo(gate.quietPeriodBypass()) >= 0;

        if (eventSnapshot.isEmpty() && rawTextSnapshot.isBlank()) {
            return new InnerLifeTick.Silent(null);
        }

        if (!quietPeriodTriggered) {
            if (observationCount < gate.minObservations()) {
                restoreBuffer(state, eventSnapshot, rawTextSnapshot, observationCount);
                return new InnerLifeTick.Silent(null);
            }
            double novelty = TokenJaccardDistance.distance(rawTextSnapshot, state.previousObservationText);
            if (novelty < gate.noveltyThreshold()) {
                restoreBuffer(state, eventSnapshot, rawTextSnapshot, observationCount);
                return new InnerLifeTick.Silent(null);
            }
        }

        var reflections = reflectionOrchestrator.reflect(
                descriptor.agentId(), descriptor.tenancyId(),
                state.lastLlmEvaluationTimestamp.equals(Instant.EPOCH) ? Instant.EPOCH : state.lastLlmEvaluationTimestamp,
                config.maxReflectionSources());

        var userPrompt   = assemblePrompt(descriptor, eventSnapshot, reflections, channelContext);
        var systemPrompt = buildSystemPrompt(driveProfile, additionalSections);

        MotivationAssessment assessment;
        try {
            var responseText = invokeText(systemPrompt, userPrompt, descriptor.agentId());
            if (responseText == null) {
                throw new RuntimeException("LLM invocation returned empty");
            }
            assessment = parseAssessment(responseText);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "LLM invocation failed for agent " + descriptor.agentId(), e);
            return new InnerLifeTick.Silent("llm failure");
        }

        if (assessment == null) {
            return new InnerLifeTick.Silent("parse failure");
        }

        state.lastLlmEvaluationTimestamp = now;
        state.previousObservationText    = rawTextSnapshot;

        if (assessment.score() >= config.motivationThreshold()) {
            state.lastInitiationTimestamp = now;
            state.initiationTimestamps.addLast(now);
            state.consecutiveInitiationsWithoutResponse++;
            return new InnerLifeTick.Initiated(assessment.content(), assessment.channelHint(), assessment.score());
        }

        return new InnerLifeTick.Silent(null);
    }

    private @Nullable InnerLifeTick evaluateNonVerbal(DriveProfile driveProfile, AgentState state) {
        var nvConfig             = config.nonVerbalConfig();
        var affiliationDrive     = driveProfile.drives().get(DriveAxis.AFFILIATION);
        var affiliationIntensity = affiliationDrive != null ? affiliationDrive.intensity() : 0.0;

        if (affiliationIntensity < nvConfig.affiliationDriveThreshold()) {
            return null;
        }

        var silenceDuration = Duration.between(state.lastActivityTimestamp, Instant.now());
        if (silenceDuration.compareTo(nvConfig.silenceThreshold()) < 0) {
            return null;
        }

        LOG.log(Level.FINE, "Non-verbal bid: affiliation={0}, silence={1}s",
                new Object[]{affiliationIntensity, silenceDuration.toSeconds()});
        return new InnerLifeTick.NonVerbal(nvConfig.defaultTag(), affiliationIntensity);
    }

    private String buildSystemPrompt(DriveProfile driveProfile,
                                     List<CognitionPromptRenderer> additionalSections) {
        if (additionalSections == null || additionalSections.isEmpty()) {
            return SYSTEM_PROMPT;
        }

        var renderContext = new CognitionRenderContext(
                driveProfile.agentId(), driveProfile.tenantId(), null,
                driveProfile.drives().entrySet().stream()
                            .filter(e -> e.getValue().intensity() >= config.motivationThreshold())
                            .map(e -> e.getKey().name())
                            .toList());

        var sb = new StringBuilder(SYSTEM_PROMPT);
        for (var section : additionalSections) {
            var rendered = section.render(renderContext);
            if (rendered != null && !rendered.isBlank()) {
                sb.append("\n\n").append(rendered);
            }
        }
        return sb.toString();
    }


    private void restoreBuffer(AgentState state, List<LevelEvent<?>> events,
                                String rawText, int observationCount) {
        synchronized (state) {
            state.eventBuffer.addAll(0, events);
            state.rawObservationText.insert(0, rawText);
            state.observationCountSinceLastInitiation += observationCount;
        }
    }

    private InitiationContext buildInitiationContext(AgentState state, AgentDescriptor descriptor) {
        var now = Instant.now();
        var windowStart = now.minus(config.windowDuration());
        state.initiationTimestamps.removeIf(ts -> ts.isBefore(windowStart));
        int initiationsInWindow = state.initiationTimestamps.size();

        return new InitiationContext(
                state.lastInitiationTimestamp,
                initiationsInWindow,
                state.consecutiveInitiationsWithoutResponse,
                descriptor);
    }

    private String assemblePrompt(AgentDescriptor descriptor,
                                   List<LevelEvent<?>> events,
                                   List<String> reflections,
                                   String channelContext) {
        var sb = new StringBuilder();
        sb.append("Personality: ").append(descriptor.name())
          .append(" — ").append(descriptor.briefing() != null ? descriptor.briefing() : "").append("\n");

        var profile = descriptor.disposition().dispositionProfile();
        if (profile != null && !profile.isEmpty()) {
            sb.append("Disposition: ").append(
                    profile.stream()
                            .map(dv -> dv.term() + "=" + String.format("%.2f", dv.weight()))
                            .collect(Collectors.joining(", "))
            ).append("\n");
        }

        sb.append("\nRecent observations (most recent ").append(config.maxObservationsInPrompt()).append("):\n");
        var recentEvents = events.size() > config.maxObservationsInPrompt()
                ? events.subList(events.size() - config.maxObservationsInPrompt(), events.size())
                : events;
        if (recentEvents.isEmpty()) {
            sb.append("No recent observations.\n");
        } else {
            for (var event : recentEvents) {
                sb.append("- ").append(event.payload().toString()).append("\n");
            }
        }

        sb.append("\nReflections:\n");
        if (reflections == null || reflections.isEmpty()) {
            sb.append("No recent reflections.\n");
        } else {
            for (var r : reflections) {
                sb.append("- ").append(r).append("\n");
            }
        }

        sb.append("\nAvailable channels and context:\n");
        sb.append(channelContext != null ? channelContext : "No channel context available.");

        return sb.toString();
    }

    private @Nullable String invokeText(String systemPrompt, String userPrompt, String agentId) {
        var pipeline = this.sessionPipeline;
        if (pipeline == null) return null;
        try {
            var session = pipeline.openSession("inner-life-" + agentId, AgentSessionInit.of(systemPrompt));
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

    private @Nullable MotivationAssessment parseAssessment(String responseText) {
        try {
            var cleaned = responseText.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonObject json;
            try (var reader = Json.createReader(new StringReader(cleaned))) {
                json = reader.readObject();
            }
            double score = json.getJsonNumber("score").doubleValue();
            String content = json.getString("content", "");
            String channelHint = json.isNull("channelHint") ? null : json.getString("channelHint", null);

            if (score < 0.0 || score > 1.0) {
                LOG.warning("Motivation score out of range: " + score);
                return null;
            }

            return new MotivationAssessment(score, content, channelHint);
        } catch (Exception e) {
            LOG.warning("Failed to parse motivation assessment: " + e.getMessage());
            return null;
        }
    }

    public void evictIdle(java.time.Duration maxIdle) {
        var cutoff = java.time.Instant.now().minus(maxIdle);
        lastTickTimes.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                var key = e.getKey();
                agentStates.remove(key);
                tickLocks.remove(key);
                return true;
            }
            return false;
        });
    }

    private static String agentKey(AgentDescriptor descriptor) {
        return descriptor.tenancyId() + ":" + descriptor.agentId();
    }

    private static final class AgentState {
        final List<LevelEvent<?>> eventBuffer = new ArrayList<>();
        final StringBuilder rawObservationText = new StringBuilder();
        final Deque<Instant> initiationTimestamps = new ArrayDeque<>();
        Instant lastInitiationTimestamp = Instant.EPOCH;
        Instant lastLlmEvaluationTimestamp = Instant.EPOCH;
        Instant lastActivityTimestamp = Instant.now();
        int consecutiveInitiationsWithoutResponse = 0;
        int observationCountSinceLastInitiation = 0;
        String previousObservationText = "";
    }
}
