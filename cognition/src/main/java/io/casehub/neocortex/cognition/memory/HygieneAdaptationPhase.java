package io.casehub.neocortex.cognition.memory;

import io.casehub.neocortex.memory.KnowledgeGapSummary;
import io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationPhase;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
@Priority(48)
@IfBuildProperty(name = "casehub.cognition.hygiene-adaptation.enabled",
                 stringValue = "true", enableIfMissing = false)
public class HygieneAdaptationPhase implements ConsolidationPhase {

    private final MemoryHygieneOrchestrator hygieneOrchestrator;
    private final AdaptiveRetentionConfig adaptiveConfig;
    private final HygieneAdaptationConfig config;
    private final Consumer<HygieneAdaptationEvent> eventSink;

    private final ConcurrentHashMap<String, KnowledgeGapSummary> previousSummaries =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RetentionConfig> previousConfigs =
            new ConcurrentHashMap<>();

    public HygieneAdaptationPhase(
            MemoryHygieneOrchestrator hygieneOrchestrator,
            AdaptiveRetentionConfig adaptiveConfig,
            HygieneAdaptationConfig config,
            Event<HygieneAdaptationEvent> adaptationEvent) {
        this(hygieneOrchestrator, adaptiveConfig, config,
             (Consumer<HygieneAdaptationEvent>) adaptationEvent::fire);
    }

    HygieneAdaptationPhase(
            MemoryHygieneOrchestrator hygieneOrchestrator,
            AdaptiveRetentionConfig adaptiveConfig,
            HygieneAdaptationConfig config,
            Consumer<HygieneAdaptationEvent> eventSink) {
        this.hygieneOrchestrator = hygieneOrchestrator;
        this.adaptiveConfig = adaptiveConfig;
        this.config = config;
        this.eventSink = eventSink;
    }

    @Override
    public String name() {
        return "hygiene-adaptation";
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        var currentSummary = hygieneOrchestrator.tenantGapSummary(tenantId);
        if (currentSummary.totalScored() == 0) {return;}

        var previous      = previousSummaries.get(tenantId);
        var currentConfig = adaptiveConfig.current(tenantId);

        if (previous == null) {
            previousSummaries.put(tenantId, currentSummary);
            previousConfigs.put(tenantId, currentConfig);
            return;
        }

        double pressure = (double) currentSummary.lowRetentionCount() / currentSummary.totalScored();
        double previousPressure = previous.totalScored() > 0
                                  ? (double) previous.lowRetentionCount() / previous.totalScored()
                                  : 0.0;

        var prevConfig = previousConfigs.getOrDefault(tenantId, currentConfig);
        boolean previouslyAdjusted =
                prevConfig.retentionThreshold() != currentConfig.retentionThreshold();

        if (previouslyAdjusted) {
            boolean improving =
                    Math.abs(pressure - targetMidpoint()) < Math.abs(previousPressure - targetMidpoint());
            if (improving) {
                previousSummaries.put(tenantId, currentSummary);
                previousConfigs.put(tenantId, currentConfig);
                return;
            }
        }

        double                                     newThreshold = currentConfig.retentionThreshold();
        HygieneAdaptationEvent.AdaptationDirection direction;

        if (pressure > config.evictionPressureUpperThreshold()) {
            newThreshold -= config.stepSize();
            direction = HygieneAdaptationEvent.AdaptationDirection.LOWERED;
        } else if (pressure < config.evictionPressureLowerThreshold()) {
            newThreshold += config.stepSize();
            direction = HygieneAdaptationEvent.AdaptationDirection.RAISED;
        } else {
            previousSummaries.put(tenantId, currentSummary);
            previousConfigs.put(tenantId, currentConfig);
            return;
        }

        newThreshold = Math.max(config.minRetentionThreshold(),
                                Math.min(config.maxRetentionThreshold(), newThreshold));

        if (newThreshold == currentConfig.retentionThreshold()) {
            previousSummaries.put(tenantId, currentSummary);
            previousConfigs.put(tenantId, currentConfig);
            return;
        }

        var newConfig = new RetentionConfig(newThreshold,
                                            currentConfig.confidenceWeight(), currentConfig.recencyWeight(),
                                            currentConfig.scopeWeight(), currentConfig.trustWeight());

        adaptiveConfig.update(tenantId, newConfig);

        eventSink.accept(new HygieneAdaptationEvent(
                tenantId, currentConfig, newConfig, pressure, direction, Instant.now()));

        previousSummaries.put(tenantId, currentSummary);
        previousConfigs.put(tenantId, newConfig);
    }

    private double targetMidpoint() {
        return (config.evictionPressureUpperThreshold() + config.evictionPressureLowerThreshold()) / 2.0;
    }
}
