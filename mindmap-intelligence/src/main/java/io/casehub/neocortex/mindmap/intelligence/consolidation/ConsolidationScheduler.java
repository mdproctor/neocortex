package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureStatistics;
import io.casehub.neocortex.mindmap.AttentionSignal;
import io.casehub.neocortex.mindmap.ConsolidationArtifact;
import io.casehub.neocortex.mindmap.MutationContext;
import io.casehub.neocortex.mindmap.SignalCategory;
import io.casehub.neocortex.mindmap.intelligence.CuriositySignal;
import io.casehub.neocortex.mindmap.intelligence.CuriositySignalGenerator;
import io.casehub.neocortex.mindmap.runtime.IdleTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ConsolidationScheduler {

    private static final Logger LOG = Logger.getLogger(
        ConsolidationScheduler.class.getName());

    private final List<ConsolidationPhase> phases;
    private final IdleTracker idleTracker;
    private final CaseMemoryStore memoryStore;
    private final CuriositySignalGenerator curiosityGenerator;
    private final Consumer<ConsolidationCompleted> completionSink;
    private final SignificanceAccumulator significanceAccumulator;
    private final CognitiveAttentionAccumulator attentionAccumulator;
    private final ReentrantLock lock = new ReentrantLock();
    private final long intervalMinutes;
    private final java.util.Set<String> knownTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService executor;
    private volatile io.casehub.platform.agent.session.SessionPipeline sessionPipeline;

    @Inject
    ConsolidationScheduler(Instance<ConsolidationPhase> phases,
                           IdleTracker idleTracker,
                           CaseMemoryStore memoryStore,
                           Instance<CuriositySignalGenerator> curiosityGenerator,
                           Instance<SignificanceAccumulator> significanceAccumulator,
                           Instance<CognitiveAttentionAccumulator> attentionAccumulator,
                           Event<ConsolidationCompleted> completionEvent,
                           @ConfigProperty(name = "casehub.mindmap.consolidation.interval-minutes",
                                           defaultValue = "5") long intervalMinutes) {
        this(phases.stream()
                .sorted(Comparator.comparingInt(p -> {
                    for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
                        var ann = c.getAnnotation(jakarta.annotation.Priority.class);
                        if (ann != null) return ann.value();
                    }
                    return Integer.MAX_VALUE;
                }))
                .toList(),
            idleTracker, memoryStore,
            curiosityGenerator.isResolvable() ? curiosityGenerator.get() : null,
            completionEvent::fire,
            intervalMinutes,
            significanceAccumulator.isResolvable() ? significanceAccumulator.get() : null,
            attentionAccumulator.isResolvable() ? attentionAccumulator.get() : null);
    }

    ConsolidationScheduler(List<ConsolidationPhase> phases,
                           IdleTracker idleTracker,
                           CaseMemoryStore memoryStore,
                           CuriositySignalGenerator curiosityGenerator,
                           Consumer<ConsolidationCompleted> completionSink,
                           long intervalMinutes,
                           SignificanceAccumulator significanceAccumulator,
                           CognitiveAttentionAccumulator attentionAccumulator) {
        this.phases = phases;
        this.idleTracker = idleTracker;
        this.memoryStore = memoryStore;
        this.curiosityGenerator = curiosityGenerator;
        this.completionSink = completionSink;
        this.intervalMinutes = intervalMinutes;
        this.significanceAccumulator = significanceAccumulator;
        this.attentionAccumulator = attentionAccumulator;
    }

    ConsolidationScheduler(List<ConsolidationPhase> phases,
                           IdleTracker idleTracker,
                           CaseMemoryStore memoryStore,
                           CuriositySignalGenerator curiosityGenerator) {
        this(phases, idleTracker, memoryStore, curiosityGenerator, e -> {}, 5, null, null);
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consolidation-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOG.info("Consolidation scheduler started: interval=" + intervalMinutes + "m");
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warning("Consolidation scheduler did not terminate within 30s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void tick() {
        try {
            if (!lock.tryLock()) {return;}
            try {
                if (!idleTracker.isIdle(Duration.ofMinutes(1))) {return;}
                if (knownTenants.isEmpty()) {return;}
                beginTickAllPhases();
                for (String tenantId : knownTenants) {
                    runPhases(tenantId);
                }
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Consolidation tick failed — scheduler will retry next interval", e);
        }
    }

    public void consolidateNow(String tenantId) {
        knownTenants.add(tenantId);
        if (!lock.tryLock()) {
            LOG.info("Consolidation already running — skipping on-demand request for " + tenantId);
            return;
        }
        try {
            beginTickAllPhases();
            runPhases(tenantId);
        } finally {
            lock.unlock();
        }
    }

    public void setSessionPipeline(io.casehub.platform.agent.session.SessionPipeline pipeline) {
        this.sessionPipeline = pipeline;
    }

    public boolean hasSessionPipeline() {
        return this.sessionPipeline != null;
    }


    private void runPhases(String tenantId) {
        List<String>                priority     = subgraphPriority(tenantId);
        List<PhaseResult>           phaseResults = new ArrayList<>();
        List<AttentionSignal>       allSignals   = new ArrayList<>();
        List<ConsolidationArtifact> allArtifacts = new ArrayList<>();
        LOG.info("Consolidation tick: " + phases.size() + " phases for tenant " + tenantId);
        Instant tickStart = Instant.now();
        var pipeline = this.sessionPipeline;
        for (ConsolidationPhase phase : phases) {
            phase.setSessionPipeline(pipeline);
            Instant phaseStart = Instant.now();
            MutationContext.set("consolidation:" + phase.name());
            try {
                LOG.info("Phase START: " + phase.name() + " [tenant=" + tenantId + "]");
                phase.run(tenantId, priority);
                allSignals.addAll(phase.signals());
                allArtifacts.addAll(phase.artifacts());
                long elapsed = Duration.between(phaseStart, Instant.now()).toMillis();
                phaseResults.add(new PhaseResult(phase.name(), phaseStart, Instant.now(), true, null));
                LOG.info("Phase END: " + phase.name() + " [" + elapsed + "ms, tenant=" + tenantId + "]");
            } catch (Exception e) {
                phaseResults.add(new PhaseResult(phase.name(), phaseStart, Instant.now(), false, e.getMessage()));
                LOG.log(Level.WARNING, "Phase " + phase.name()
                                       + " failed for tenant " + tenantId, e);
            } finally {
                MutationContext.clear();
            }
        }
        long tickElapsed = Duration.between(tickStart, Instant.now()).toMillis();
        LOG.info("Consolidation tick complete: " + tickElapsed + "ms total [tenant=" + tenantId + "]");
        if (attentionAccumulator != null && !allSignals.isEmpty()) {
            refreshUrgencyP75(allSignals);
            attentionAccumulator.addSignals(allSignals);
        }
        completionSink.accept(new ConsolidationCompleted(tenantId, phaseResults, allArtifacts));
    }

    private void refreshUrgencyP75(List<AttentionSignal> signals) {
        var byPrincipal = new java.util.HashMap<String, List<Double>>();
        for (var signal : signals) {
            if (signal.category() == SignalCategory.URGENCY_SPIKE && signal.principalId() != null) {
                byPrincipal.computeIfAbsent(signal.principalId(), k -> new ArrayList<>())
                           .add(signal.significance());
            }
        }
        for (var entry : byPrincipal.entrySet()) {
            double[] values = entry.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            double   p75    = FeatureStatistics.compute(values).p75();
            attentionAccumulator.updateUrgencyP75(entry.getKey(), p75);
        }
    }


    private void beginTickAllPhases() {
        if (significanceAccumulator != null) {
            significanceAccumulator.swapAndReset();
        }
        for (ConsolidationPhase phase : phases) {
            phase.beginTick();
        }
    }

    private List<String> subgraphPriority(String tenantId) {
        if (curiosityGenerator == null) return List.of();
        return curiosityGenerator.computeSignals(tenantId, Set.of()).stream()
            .map(CuriositySignal::targetSubgraphType)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }
}
