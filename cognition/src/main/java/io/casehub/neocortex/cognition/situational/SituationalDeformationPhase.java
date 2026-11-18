package io.casehub.neocortex.cognition.situational;

import io.casehub.neocortex.cognition.journal.CognitiveEntry;
import io.casehub.neocortex.cognition.journal.CognitiveJournal;
import io.casehub.neocortex.cognition.journal.EventRef;
import io.casehub.neocortex.cognitive.index.EffectiveCognitiveDefaults;
import io.casehub.neocortex.mindmap.intelligence.consolidation.ConsolidationPhase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@ApplicationScoped
@Priority(46)
public class SituationalDeformationPhase implements ConsolidationPhase {

    private static final double EPSILON_PUBLISH = 0.005;

    private final PersonalityStateService    stateService;
    private final PersonalityStateIntegrator integrator;
    private final EffectiveCognitiveDefaults effectiveDefaults;
    private       CognitiveJournal           journal = CognitiveJournal.NOOP;
    private final Clock                      clock;

    @Inject
    public SituationalDeformationPhase(
            PersonalityStateService stateService,
            PersonalityStateIntegrator integrator,
            EffectiveCognitiveDefaults effectiveDefaults,
            Clock clock) {
        this.stateService      = stateService;
        this.integrator        = integrator;
        this.effectiveDefaults = effectiveDefaults;
        this.clock             = clock;
    }

    SituationalDeformationPhase(
            PersonalityStateService stateService,
            PersonalityStateIntegrator integrator,
            EffectiveCognitiveDefaults effectiveDefaults,
            CognitiveJournal journal,
            Clock clock) {
        this.stateService      = stateService;
        this.integrator        = integrator;
        this.effectiveDefaults = effectiveDefaults;
        this.journal           = journal;
        this.clock             = clock;
    }

    @Override
    public String name() {
        return "situational-deformation";
    }

    public void setCognitiveJournal(CognitiveJournal journal) {
        this.journal = journal != null ? journal : CognitiveJournal.NOOP;
    }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        var now = clock.instant();

        for (var agentId : stateService.agentIds(tenantId)) {
            try {
                var before       = stateService.currentState(tenantId, agentId);
                var beforeCursor = before != null ? before.cursorTime() : now;

                var state = stateService.transition(tenantId, agentId, now, st -> {
                    var advanced   = integrator.advance(st, now);
                    var baselines  = resolveBaselines(tenantId, agentId);
                    var candidates = integrator.promotionCandidates(advanced, baselines, now);
                    return applyPromotions(advanced, candidates);
                });

                int hoursAdvanced   = (int) Duration.between(beforeCursor, state.cursorTime()).toHours();
                int facetsWithDrift = countFacetsWithDrift(state);

                journal.record(new CognitiveEntry.SituationalDeformationConsolidated(
                        tenantId, agentId, EventRef.consolidation(EventRef.event("situational-deformation")),
                        now, hoursAdvanced, facetsWithDrift));
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(getClass().getName())
                                        .log(java.util.logging.Level.WARNING, "Deformation failed for " + agentId + " in " + tenantId, e);
            }
        }
    }

    private int countFacetsWithDrift(PersonalityState state) {
        int count = 0;
        for (var fs : state.facets().values()) {
            if (Math.abs(fs.homeOffset()) >= EPSILON_PUBLISH || Math.abs(fs.displacement()) >= EPSILON_PUBLISH) {
                count++;
            }
        }
        return count;
    }

    private java.util.Map<String, Double> resolveBaselines(String tenantId, String agentId) {
        var defaults = effectiveDefaults.forAgent(tenantId, agentId);
        if (defaults == null || defaults.descriptor() == null) {return java.util.Map.of();}
        var baselines = new java.util.LinkedHashMap<String, Double>();
        for (var facet : defaults.descriptor().personalityFacets()) {
            baselines.put(facet.facet(), facet.score());
        }
        return baselines;
    }

    private PersonalityState applyPromotions(PersonalityState state,
                                             java.util.Map<String, PersonalityStateIntegrator.PromotionCandidate> candidates) {
        if (candidates.isEmpty()) {return state;}
        var facets = new java.util.LinkedHashMap<>(state.facets());
        for (var candidate : candidates.values()) {
            var fs = facets.get(candidate.facet());
            if (fs == null) {continue;}
            facets.put(candidate.facet(), new FacetState(
                    fs.displacement(),
                    fs.homeOffset() - candidate.delta(),
                    fs.resilience(), fs.exposure(), fs.load(),
                    fs.promoted() + candidate.delta(),
                    null,
                    fs.cappedSince()));
        }
        return state.withFacets(facets);
    }

}
