package io.casehub.neocortex.cognition.strategy;

import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.TrendAnalyzer;
import io.casehub.neocortex.memory.cbr.TrendProfile;
import io.casehub.neocortex.memory.cbr.TrendSpec;
import io.casehub.neocortex.memory.cbr.TrendType;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.session.SessionPipeline;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StrategyLearningOrchestrator {

    private static final Logger LOG = Logger.getLogger(StrategyLearningOrchestrator.class.getName());

    private static final ExecutorService REFLECT_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "strategy-reflect");
                t.setDaemon(true);
                return t;
            });

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a metacognitive strategy advisor for an AI agent. Analyze the agent's \
            interaction history and recommend strategy adjustments.

            Respond with JSON only:
            {"guidelines":["guideline1","guideline2"],\
            "dimensionDeltas":{"verbosity":-0.1,"formality":0.05}}

            Guidelines: ranked list, most impactful first. Include per-subject insights \
            where patterns differ significantly from global.
            Deltas: in [-%s, +%s]. Only include dimensions that should change.""";

    static final List<String> DEFAULT_DIMENSIONS = List.of(
            "verbosity", "formality", "initiative", "directness", "questionRate");
    /**
     * Cattell (1971): workingMemorySpan (Engle &amp; Kane 2004) scales the strategy
     * adaptation ceiling — the LLM prompt range matches the effective clamp value.
     */
    private String systemPrompt(String agentId, String tenantId) {
        double d = effectiveMaxDelta(agentId, tenantId);
        return String.format(SYSTEM_PROMPT_TEMPLATE, d, d);
    }

    private double effectiveMaxDelta(String agentId, String tenantId) {
        return config.maxDimensionDelta() * resolveWorkingMemorySpan(agentId, tenantId);
    }

    private double resolveWorkingMemorySpan(String agentId, String tenantId) {
        if (agentId == null || effectiveDefaults == null) {return 1.0;}
        var cap = effectiveDefaults.restingFor(tenantId, agentId).capacityConfig();
        return cap != null ? cap.workingMemorySpanOrDefault() : 1.0;
    }


    private final StrategyMemory strategyMemory;
    private final ReflectionOrchestrator reflectionOrchestrator;
    private final StrategyLearningConfig config;
    private final Clock clock;
    private final @Nullable SessionPipeline sessionPipeline;
    private final io.casehub.neocortex.cognitive.index.@Nullable CognitiveMemoryRetriever memoryRetriever;
    private final io.casehub.neocortex.cognitive.index.@Nullable EffectiveCognitiveDefaults effectiveDefaults;

    private final ConcurrentHashMap<String, AgentLearningState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> tickLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.time.Instant> lastTickTimes = new ConcurrentHashMap<>();

    public StrategyLearningOrchestrator(StrategyMemory strategyMemory,
                                 ReflectionOrchestrator reflectionOrchestrator,
                                 StrategyLearningConfig config,
                                 Clock clock,
                                 @Nullable SessionPipeline sessionPipeline,
                                 io.casehub.neocortex.cognitive.index.@Nullable CognitiveMemoryRetriever memoryRetriever) {
        this(strategyMemory, reflectionOrchestrator, config, clock, sessionPipeline, memoryRetriever, null);
    }

    public StrategyLearningOrchestrator(StrategyMemory strategyMemory,
                                 ReflectionOrchestrator reflectionOrchestrator,
                                 StrategyLearningConfig config,
                                 Clock clock,
                                 @Nullable SessionPipeline sessionPipeline,
                                 io.casehub.neocortex.cognitive.index.@Nullable CognitiveMemoryRetriever memoryRetriever,
                                 io.casehub.neocortex.cognitive.index.@Nullable EffectiveCognitiveDefaults effectiveDefaults) {
        this.strategyMemory         = strategyMemory;
        this.reflectionOrchestrator = reflectionOrchestrator;
        this.config                 = config;
        this.clock                  = clock;
        this.sessionPipeline        = sessionPipeline;
        this.memoryRetriever        = memoryRetriever;
        this.effectiveDefaults      = effectiveDefaults;
    }

    public StrategyLearningOrchestrator(StrategyMemory strategyMemory,
                                 ReflectionOrchestrator reflectionOrchestrator,
                                 StrategyLearningConfig config,
                                 Clock clock,
                                 @Nullable SessionPipeline sessionPipeline) {
        this(strategyMemory, reflectionOrchestrator, config, clock, sessionPipeline, null, null);
    }

    public void record(EngagementSignal signal, String agentId,
                        String subjectId, String tenantId) {
        var state = states.computeIfAbsent(stateKey(agentId, tenantId),
                k -> new AgentLearningState(agentId, tenantId, config.maxBufferSize()));
        state.lastActivityTimestamp = clock.instant();

        if (signal instanceof EngagementSignal.TurnOutcome turn) {
            state.pendingTurns.addLast(new TurnEntry(turn, subjectId));
            if (state.pendingTurns.size() > config.maxBufferSize()) {
                state.pendingTurns.removeFirst();
            }
        } else if (signal instanceof EngagementSignal.ConversationOutcome conv) {
            state.pendingConversations.addLast(new ConversationEntry(conv, subjectId));
        }
    }

    public StrategyLearningTick tick(String agentId, String tenantId) {
        lastTickTimes.put(stateKey(agentId, tenantId), java.time.Instant.now());
        var state = states.get(stateKey(agentId, tenantId));
        if (state == null || (state.pendingTurns.isEmpty() && state.pendingConversations.isEmpty())) {
            return new StrategyLearningTick.NoChange("no pending signals");
        }

        return withLock(stateKey(agentId, tenantId), () -> doTick(state));
    }

    public StrategyReflection reflect(String agentId, String tenantId) {
        return withLock(stateKey(agentId, tenantId), () -> doReflect(agentId, tenantId));
    }

    public Optional<StrategyProfile> currentStrategy(String agentId, String tenantId) {
        var state = states.get(stateKey(agentId, tenantId));
        if (state != null && state.currentProfile != null) {
            return Optional.of(state.currentProfile);
        }
        return strategyMemory.lookup(agentId, tenantId);
    }

    public Optional<EngagementTrend> engagementTrend(String agentId, String tenantId) {
        var key   = stateKey(agentId, tenantId);
        var state = states.get(key);
        if (state == null || state.currentProfile == null) {return Optional.empty();}

        var profile = state.currentProfile;
        var trends = new java.util.HashMap<String, EngagementTrend.TrendDirection>();
        for (var entry : profile.dimensions().entrySet()) {
            double val = entry.getValue();
            trends.put(entry.getKey(), val > config.trendImprovingThreshold() ? EngagementTrend.TrendDirection.IMPROVING
                                                 : val < config.trendDecliningThreshold() ? EngagementTrend.TrendDirection.DECLINING
                                                             : EngagementTrend.TrendDirection.STABLE);
        }
        double responseRate = state.totalSignals > 0
                              ? (double) state.totalResponded / state.totalSignals : 0.0;
        return Optional.of(new EngagementTrend(trends, responseRate, state.totalSignals));
    }


    private StrategyLearningTick doTick(AgentLearningState state) {
        var drainedTurns = new ArrayList<TurnEntry>();
        while (!state.pendingTurns.isEmpty()) {
            drainedTurns.add(state.pendingTurns.removeFirst());
        }

        for (var entry : drainedTurns) {
            state.totalSignals++;
            var event = entry.signal.event();
            if (event.responded() != null && event.responded()) {
                state.totalResponded++;
            }
            if (event.affectShift() != null) {
                state.affectSum += event.affectShift();
            }
            var caseId = event.caseId();
            if (caseId != null && !caseId.isBlank()) {
                state.conversationTurns
                        .computeIfAbsent(caseId, k -> new ArrayList<>())
                        .add(entry);
            }
        }

        double engagementRate = state.totalSignals > 0
                                ? (double) state.totalResponded / state.totalSignals : 0.0;
        double meanSentiment = state.totalSignals > 0
                               ? state.affectSum / state.totalSignals : 0.0;
        state.lastTickTimestamp = clock.instant();

        if (state.pendingConversations.isEmpty()
            && drainedTurns.size() < config.minSignalsForConversationCase()) {
            return new StrategyLearningTick.Observed(
                    drainedTurns.size(), engagementRate, meanSentiment);
        }

        var storedConversations = new ArrayList<String>();
        int casesStored         = 0;

        var drainedConversations = new ArrayList<ConversationEntry>();
        while (!state.pendingConversations.isEmpty()) {
            drainedConversations.add(state.pendingConversations.removeFirst());
        }

        for (var convEntry : drainedConversations) {
            var conv        = convEntry.signal;
            var accumulated = state.conversationTurns.remove(conv.conversationId());
            if (accumulated == null || accumulated.isEmpty()) {continue;}

            var evidence = buildEvidence(accumulated, convEntry.subjectId, state.agentId,
                                         state.tenantId, conv.conversationId(), conv.conversationSummary());
            strategyMemory.storeEvidence(evidence);
            storedConversations.add(conv.conversationId());
            casesStored++;
        }

        if (drainedConversations.isEmpty() && drainedTurns.size() >= config.minSignalsForConversationCase()) {
            var grouped = new LinkedHashMap<String, List<TurnEntry>>();
            for (var entry : drainedTurns) {
                grouped.computeIfAbsent(entry.subjectId, k -> new ArrayList<>()).add(entry);
            }
            for (var group : grouped.entrySet()) {
                if (group.getValue().size() >= config.minSignalsForConversationCase()) {
                    var evidence = buildEvidence(group.getValue(), group.getKey(), state.agentId,
                                                 state.tenantId, null, null);
                    strategyMemory.storeEvidence(evidence);
                    storedConversations.add(group.getKey());
                    casesStored++;
                }
            }
        }

        if (casesStored == 0) {
            return new StrategyLearningTick.Observed(
                    drainedTurns.size(), engagementRate, meanSentiment);
        }

        maybeAutoReflect(state);

        return new StrategyLearningTick.Learned(
                drainedTurns.size(), engagementRate, meanSentiment,
                List.copyOf(storedConversations), casesStored);
    }

    private void maybeAutoReflect(AgentLearningState state) {
        int totalCases = countAgentCases(state.agentId, state.tenantId);
        if (totalCases < config.minCasesForReflection()) return;
        if (state.lastReflectTimestamp != null
                && Duration.between(state.lastReflectTimestamp, clock.instant())
                    .compareTo(config.staleStateTimeout()) < 0) return;

        REFLECT_EXECUTOR.submit(() -> doReflectAsync(state.agentId, state.tenantId));
    }

    private int countAgentCases(String agentId, String tenantId) {
        return strategyMemory.evidenceCount(agentId, tenantId);
    }

    private void doReflectAsync(String agentId, String tenantId) {
        try {
            StrategyProfile          profile;
            List<EngagementEvidence> cases;

            record Snapshot(StrategyProfile profile, List<EngagementEvidence> cases) {}
            var snapshot = withLock(stateKey(agentId, tenantId), () -> {
                var p = currentStrategy(agentId, tenantId)
                                .orElseGet(() -> defaultProfile(agentId, tenantId));
                var c = strategyMemory.recentEvidence(agentId, tenantId, config.maxReflectionSources());
                return new Snapshot(p, c);
            });
            profile = snapshot.profile();
            cases   = snapshot.cases();

            if (cases.size() < config.minCasesForReflection()) {return;}

            TrendProfile trends            = analyzeTrends(cases);
            var          perSubjectSummary = summarizePerSubject(cases);
            var          userPrompt        = buildReflectionPrompt(profile, trends, List.of(), perSubjectSummary, agentId, tenantId);

            String llmResponse = invokeText(systemPrompt(agentId, tenantId), userPrompt, agentId);
            if (llmResponse == null) {
                LOG.log(Level.WARNING, "Async reflect LLM failed for " + agentId);
                return;
            }

            var state = states.get(stateKey(agentId, tenantId));
            withLock(stateKey(agentId, tenantId), () -> {
                applyReflectionResult(profile, llmResponse, trends, cases.size(),
                                      agentId, tenantId, state);
                if (state != null) {
                    state.lastReflectTimestamp = clock.instant();
                }
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Async reflect failed for " + agentId, e);
        }
    }

    private Map<String, FeatureValue> extractFeatures(List<TurnEntry> turns,
                                                       String subjectId, String agentId) {
        var features = new LinkedHashMap<String, FeatureValue>();
        features.put("subjectId", FeatureValue.string(subjectId));
        features.put("agentId", FeatureValue.string(agentId));
        features.put("conversationTimestamp",
                FeatureValue.number((double) clock.instant().toEpochMilli()));
        features.put("turnCount", FeatureValue.number(turns.size()));

        double lenSum = 0; int lenCount = 0;
        double sentSum = 0; int sentCount = 0;
        int continued = 0; int contTotal = 0;

        for (var entry : turns) {
            var event = entry.signal.event();
            if (event.responseLength() != null) {
                lenSum += event.responseLength();
                lenCount++;
            }
            if (event.affectShift() != null) {
                sentSum += event.affectShift();
                sentCount++;
            }
            if (event.continued() != null) {
                contTotal++;
                if (event.continued()) continued++;
            }
        }

        features.put("avgResponseLength",
                FeatureValue.number(lenCount > 0 ? lenSum / lenCount : 0));
        features.put("continuationRate",
                FeatureValue.number(contTotal > 0 ? (double) continued / contTotal : 0));
        features.put("meanAffectShift",
                FeatureValue.number(sentCount > 0 ? sentSum / sentCount : 0));

        for (String dim : DEFAULT_DIMENSIONS) {
            double sum = 0; int count = 0;
            for (var entry : turns) {
                Double val = entry.signal.dimensionalSnapshot().get(dim);
                if (val != null) { sum += val; count++; }
            }
            features.put("avgSnapshot_" + dim,
                    FeatureValue.number(count > 0 ? sum / count : config.defaultDimensionValue()));
        }

        return Map.copyOf(features);
    }

    private EngagementEvidence buildEvidence(List<TurnEntry> turns, String subjectId,
                                             String agentId, String tenantId,
                                             @Nullable String conversationId,
                                             @Nullable String conversationSummary) {
        var features = extractFeatures(turns, subjectId, agentId);

        double lenSum    = 0;
        int    lenCount  = 0;
        double sentSum   = 0;
        int    sentCount = 0;
        int    continued = 0;
        int    contTotal = 0;

        for (var entry : turns) {
            var event = entry.signal.event();
            if (event.responseLength() != null) {
                lenSum += event.responseLength();
                lenCount++;
            }
            if (event.affectShift() != null) {
                sentSum += event.affectShift();
                sentCount++;
            }
            if (event.continued() != null) {
                contTotal++;
                if (event.continued()) {continued++;}
            }
        }

        var dimSnapshots = new LinkedHashMap<String, Double>();
        for (String dim : DEFAULT_DIMENSIONS) {
            var val = features.get("avgSnapshot_" + dim);
            if (val instanceof FeatureValue.NumberVal nv) {
                dimSnapshots.put(dim, nv.value());
            }
        }

        if (conversationSummary == null) {
            conversationSummary = "Interaction with " + subjectId + " (" + turns.size() + " turns)";
        }

        return new EngagementEvidence(
                agentId, subjectId, tenantId,
                conversationId, conversationSummary,
                turns.size(),
                contTotal > 0 ? (double) continued / contTotal : 0,
                lenCount > 0 ? lenSum / lenCount : 0,
                sentCount > 0 ? sentSum / sentCount : 0,
                Map.copyOf(dimSnapshots),
                clock.instant());
    }

    private static Map<String, FeatureValue> evidenceToFeatures(EngagementEvidence e) {
        var features = new LinkedHashMap<String, FeatureValue>();
        features.put("conversationTimestamp", FeatureValue.number((double) e.recordedAt().toEpochMilli()));
        features.put("continuationRate", FeatureValue.number(e.continuationRate()));
        features.put("avgResponseLength", FeatureValue.number(e.avgResponseLength()));
        features.put("meanAffectShift", FeatureValue.number(e.meanAffectShift()));
        features.put("subjectId", FeatureValue.string(e.subjectId()));
        for (var dim : e.dimensionSnapshots().entrySet()) {
            features.put("avgSnapshot_" + dim.getKey(), FeatureValue.number(dim.getValue()));
        }
        return Map.copyOf(features);
    }


    private StrategyReflection doReflect(String agentId, String tenantId) {
        var profile = currentStrategy(agentId, tenantId).orElseGet(
                () -> defaultProfile(agentId, tenantId));

        var cases = strategyMemory.recentEvidence(agentId, tenantId, config.maxReflectionSources());

        if (cases.size() < config.minCasesForReflection()) {
            return new StrategyReflection.NoChange("insufficient evidence ("
                                                   + cases.size() + "/" + config.minCasesForReflection() + ")");
        }

        TrendProfile trends = analyzeTrends(cases);

        var state = states.get(stateKey(agentId, tenantId));
        Instant since = state != null && state.lastReflectTimestamp != null
                        ? state.lastReflectTimestamp : Instant.EPOCH;
        List<String> reflections;
        try {
            reflections = reflectionOrchestrator.reflect(
                    agentId, tenantId, since, config.maxReflectionSources());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ReflectionOrchestrator failed for " + agentId, e);
            reflections = List.of();
        }

        var perSubjectSummary = summarizePerSubject(cases);
        var userPrompt        = buildReflectionPrompt(profile, trends, reflections, perSubjectSummary, agentId, tenantId);

            String llmResponse = invokeText(systemPrompt(agentId, tenantId), userPrompt, agentId);
        if (llmResponse == null) {
            return new StrategyReflection.NoChange("LLM synthesis failed");
        }

        return applyReflectionResult(profile, llmResponse, trends, cases.size(),
                                     agentId, tenantId, state);
    }

    private TrendProfile analyzeTrends(List<EngagementEvidence> cases) {
        var sorted = cases.stream()
                          .sorted(java.util.Comparator.comparing(EngagementEvidence::recordedAt))
                          .toList();

        var observations = new ArrayList<Map<String, FeatureValue>>();
        for (var evidence : sorted) {
            observations.add(evidenceToFeatures(evidence));
        }

        if (observations.size() < 2) {
            return new TrendProfile(Map.of());
        }

        try {
            var trendSpec = new TrendSpec(
                    Set.of(TrendType.SLOPE, TrendType.DELTA, TrendType.VOLATILITY),
                    java.time.temporal.ChronoUnit.MILLIS);
            var tsSchema = new FeatureField.TimeSeries(
                    "engagement",
                    List.of(
                            FeatureField.numeric("conversationTimestamp", 0, Double.MAX_VALUE),
                            FeatureField.numeric("continuationRate", 0, 1),
                            FeatureField.numeric("meanAffectShift", -1, 1),
                            FeatureField.numeric("avgResponseLength", 0, 10000)),
                    "conversationTimestamp",
                    null,
                    trendSpec);
            return TrendAnalyzer.analyze(observations, tsSchema);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "TrendAnalyzer failed", e);
            return new TrendProfile(Map.of());
        }
    }

    private String summarizePerSubject(List<EngagementEvidence> cases) {
        var bySubject = new LinkedHashMap<String, List<EngagementEvidence>>();
        for (var evidence : cases) {
            bySubject.computeIfAbsent(evidence.subjectId(), k -> new ArrayList<>()).add(evidence);
        }

        var sb = new StringBuilder();
        for (var entry : bySubject.entrySet()) {
            double avgCont = entry.getValue().stream()
                                  .mapToDouble(EngagementEvidence::continuationRate)
                                  .average().orElse(0);
            double avgSent = entry.getValue().stream()
                                  .mapToDouble(EngagementEvidence::meanAffectShift)
                                  .average().orElse(0);
            sb.append(String.format("  %s: engagement %.0f%%, sentiment %+.2f (%d conversations)\n",
                                    entry.getKey(), avgCont * 100, avgSent, entry.getValue().size()));
        }
        return sb.toString();
    }

    private String buildReflectionPrompt(StrategyProfile profile, TrendProfile trends,
                                          List<String> reflections, String perSubjectSummary,
                                          String agentId, String tenantId) {
        var sb = new StringBuilder();
        sb.append("Current strategy dimensions:\n");
        for (var dim : profile.dimensions().entrySet()) {
            sb.append(String.format("  %s = %.2f\n", dim.getKey(), dim.getValue()));
        }

        sb.append("\nCurrent guidelines:\n");
        if (profile.guidelines().isEmpty()) {
            sb.append("  None yet\n");
        } else {
            for (int i = 0; i < profile.guidelines().size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, profile.guidelines().get(i)));
            }
        }

        sb.append("\nEngagement trend analysis:\n");
        if (trends.metrics().isEmpty()) {
            sb.append("  Insufficient data for trends\n");
        } else {
            for (var m : trends.metrics().entrySet()) {
                sb.append(String.format("  %s = %.4f\n", m.getKey(), m.getValue()));
            }
        }

        sb.append("\nReflective insights:\n");
        if (reflections.isEmpty()) {
            sb.append("  None\n");
        } else {
            for (var r : reflections) {
                sb.append("  - ").append(r).append("\n");
            }
        }

        sb.append("\nPer-subject engagement patterns:\n");
        sb.append(perSubjectSummary.isEmpty() ? "  None\n" : perSubjectSummary);

        if (memoryRetriever != null) {
            var memories = memoryRetriever.query(
                    io.casehub.neocortex.memory.experience.ExperienceQuery.forAgent(agentId, tenantId).withLimit(5));
            if (!memories.isEmpty()) {
                sb.append("\nRelevant experience memories:\n");
                for (var m : memories) {
                    var text = m.text();
                    if (text != null && !text.isBlank()) {
                        sb.append("  - ").append(text.length() > 120 ? text.substring(0, 120) : text).append("\n");
                    }
                }
            }
        }

        sb.append(String.format("\nProvide up to %d ranked guidelines and dimensional deltas.",
                config.maxGuidelines()));
        return sb.toString();
    }

    private StrategyReflection applyReflectionResult(StrategyProfile profile, String json,
                                                      TrendProfile trends, int evidenceCases,
                                                      String agentId, String tenantId,
                                                      @Nullable AgentLearningState state) {
        try {
            json = json.strip();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```[a-z]*\\n?", "").replaceFirst("\\n?```$", "").strip();
            }

            if (!json.contains("\"guidelines\"") && !json.contains("\"dimensionDeltas\"")) {
                LOG.warning("LLM output missing expected JSON keys: " + json);
                return new StrategyReflection.NoChange("parse failure");
            }

            var guidelines = extractStringArray(json, "guidelines");
            if (guidelines.isEmpty()) {
                guidelines = profile.guidelines();
            }
            if (guidelines.size() > config.maxGuidelines()) {
                guidelines = guidelines.subList(0, config.maxGuidelines());
            }

            var deltas        = extractDeltas(json);
            var newDimensions = new LinkedHashMap<>(profile.dimensions());
            for (var delta : deltas.entrySet()) {
                if (!DEFAULT_DIMENSIONS.contains(delta.getKey())) {
                    LOG.fine("Ignoring unknown dimension: " + delta.getKey());
                    continue;
                }
                double maxDelta = effectiveMaxDelta(agentId, tenantId);
                double clamped = Math.max(-maxDelta, Math.min(maxDelta, delta.getValue()));
                double current = newDimensions.getOrDefault(delta.getKey(),
                                                            config.defaultDimensionValue());
                newDimensions.put(delta.getKey(), Math.max(0.0, Math.min(1.0, current + clamped)));
            }

            var now = clock.instant();
            var updated = new StrategyProfile(agentId, tenantId,
                                              Map.copyOf(newDimensions), List.copyOf(guidelines), now, evidenceCases);
            strategyMemory.store(updated);

            if (state != null) {
                state.currentProfile       = updated;
                state.lastReflectTimestamp = now;
            }

            return new StrategyReflection.Reflected(
                    updated, List.copyOf(guidelines), trends, evidenceCases);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse reflection result: " + json, e);
            return new StrategyReflection.NoChange("parse failure");
        }}

    private List<String> extractStringArray(String json, String field) {
        var result = new ArrayList<String>();
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return result;
        int arrStart = json.indexOf('[', keyIdx);
        if (arrStart < 0) return result;
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return result;
        String inner = json.substring(arrStart + 1, arrEnd);
        for (String element : inner.split(",")) {
            element = element.strip();
            if (element.startsWith("\"") && element.endsWith("\"") && element.length() > 1) {
                result.add(element.substring(1, element.length() - 1));
            }
        }
        return result;
    }

    private Map<String, Double> extractDeltas(String json) {
        var result = new LinkedHashMap<String, Double>();
        String key = "\"dimensionDeltas\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return result;
        int objStart = json.indexOf('{', keyIdx);
        if (objStart < 0) return result;
        int objEnd = json.indexOf('}', objStart);
        if (objEnd < 0) return result;
        String inner = json.substring(objStart + 1, objEnd);
        for (String pair : inner.split(",")) {
            pair = pair.strip();
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String k = pair.substring(0, colon).strip();
            String v = pair.substring(colon + 1).strip();
            if (k.startsWith("\"") && k.endsWith("\"") && k.length() > 1) {
                k = k.substring(1, k.length() - 1);
            }
            try {
                result.put(k, Double.parseDouble(v));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private StrategyProfile defaultProfile(String agentId, String tenantId) {
        var dims = new LinkedHashMap<String, Double>();
        for (String dim : DEFAULT_DIMENSIONS) {
            dims.put(dim, config.defaultDimensionValue());
        }
        return new StrategyProfile(agentId, tenantId, dims, List.of(), clock.instant(), 0);
    }

    private @Nullable String invokeText(String systemPrompt, String userPrompt, String agentId) {
        var pipeline = this.sessionPipeline;
        if (pipeline == null) return null;
        try {
            var session = pipeline.openSession("strategy-learning-" + agentId, AgentSessionInit.of(systemPrompt));
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

    private <T> T withLock(String key, Supplier<T> action) {
        var lock = tickLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withLock(String key, Runnable action) {
        var lock = tickLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public void evictIdle(java.time.Duration maxIdle) {
        var cutoff = java.time.Instant.now().minus(maxIdle);
        lastTickTimes.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                var key = e.getKey();
                states.remove(key);
                tickLocks.remove(key);
                return true;
            }
            return false;
        });
    }

    private static String stateKey(String agentId, String tenantId) {
        return agentId + ":" + tenantId;
    }

    record TurnEntry(EngagementSignal.TurnOutcome signal, String subjectId) {}
    record ConversationEntry(EngagementSignal.ConversationOutcome signal, String subjectId) {}

    static final class AgentLearningState {
        final String agentId;
        final String tenantId;
        final ArrayDeque<TurnEntry> pendingTurns;
        final ArrayDeque<ConversationEntry> pendingConversations;
        final ConcurrentHashMap<String, List<TurnEntry>> conversationTurns = new ConcurrentHashMap<>();
        int totalSignals;
        int totalResponded;
        double affectSum;
        Instant lastSignalTimestamp;
        Instant lastTickTimestamp;
        Instant lastReflectTimestamp;
        Instant lastActivityTimestamp;
        @Nullable StrategyProfile currentProfile;

        AgentLearningState(String agentId, String tenantId, int maxBufferSize) {
            this.agentId = agentId;
            this.tenantId = tenantId;
            this.pendingTurns = new ArrayDeque<>(maxBufferSize);
            this.pendingConversations = new ArrayDeque<>();
        }
    }
}
