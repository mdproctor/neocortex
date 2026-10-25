package io.casehub.neocortex.memory.seeding.biography;

import io.casehub.neocortex.caps.CapsEngine;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.Subject;
import io.casehub.neocortex.memory.relationship.RelationshipAttributeKeys;
import io.casehub.neocortex.memory.relationship.RelationshipEvents;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.NodeUpdate;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class EntityEnrichmentHandler implements BiographyHandler {

    private final MindMapStore    store;
    private final CaseMemoryStore memoryStore;
    private final CapsEngine      capsEngine;

    @Inject
    public EntityEnrichmentHandler(MindMapStore store, CaseMemoryStore memoryStore,
                                   CapsEngine capsEngine) {
        this.store       = store;
        this.memoryStore = memoryStore;
        this.capsEngine  = capsEngine;
    }

    EntityEnrichmentHandler(MindMapStore store, CaseMemoryStore memoryStore,
                            @SuppressWarnings("unused") boolean testMarker) {
        this.store       = store;
        this.memoryStore = memoryStore;
        this.capsEngine  = null;
    }

    EntityEnrichmentHandler(MindMapStore store, CaseMemoryStore memoryStore,
                            CapsEngine capsEngine,
                            @SuppressWarnings("unused") boolean testMarker) {
        this.store       = store;
        this.memoryStore = memoryStore;
        this.capsEngine  = capsEngine;
    }

    @Override
    public Set<String> handledTypes() {return Set.of(BiographyTemplateTypes.ENTITY_ENRICHMENT);}

    @Override
    public void handle(BiographyProfile profile, String agentId, String tenantId) {
        String behavioralSgId = null;

        for (var entry : profile.entityEnrichments()) {
            String nodeId = AssociationProcessor.resolveAcrossSubgraphs(entry.entityRef(), store, tenantId);
            if (nodeId == null) {continue;}

            var props = new HashMap<String, String>();

            if (entry.driveBaselines() != null) {
                for (var e : entry.driveBaselines().entrySet()) {
                    props.put("drive-baseline-" + e.getKey(), String.valueOf(e.getValue().baseline()));
                    if (e.getValue().justification() != null) {
                        props.put("drive-baseline-" + e.getKey() + "-justification", e.getValue().justification());
                    }
                }
            }

            if (entry.capsPatterns() != null) {
                props.put("caps-pattern-count", String.valueOf(entry.capsPatterns().size()));
                for (int i = 0; i < entry.capsPatterns().size(); i++) {
                    var p = entry.capsPatterns().get(i);
                    props.put("caps-pattern-" + i + "-pattern", p.pattern());
                    if (p.frequency() != null) {
                        props.put("caps-pattern-" + i + "-frequency", String.valueOf(p.frequency()));
                    }
                }
            }

            if (!props.isEmpty()) {
                store.updateNode(nodeId, NodeUpdate.empty().withPropertiesToSet(props), tenantId);
            }

            if (entry.behaviouralAttractors() != null && !entry.behaviouralAttractors().isEmpty()) {
                if (behavioralSgId == null) {
                    behavioralSgId = BiographyUtils.ensureSubgraph(store, SubgraphTypes.BEHAVIORAL, tenantId);
                }
                for (var attractor : entry.behaviouralAttractors()) {
                    var nodeProps = new HashMap<String, String>();
                    nodeProps.put("category", attractor.name());
                    nodeProps.put("agent-id", agentId);
                    nodeProps.put("provenance", "biographical-import");
                    if (attractor.strength() != null) {
                        nodeProps.put("strength", String.valueOf(attractor.strength()));
                    }
                    if (attractor.description() != null) {
                        nodeProps.put("description", attractor.description());
                    }
                    if (attractor.trigger() != null) {
                        nodeProps.put("trigger", attractor.trigger());
                    }
                    if (attractor.response() != null) {
                        nodeProps.put("response", attractor.response());
                    }
                    store.addNode(
                            NodeInput.of(attractor.name(), behavioralSgId)
                                     .withTraits(Set.of("CapsGenerated"))
                                     .withProperties(nodeProps),
                            tenantId);
                }
            }

            storeRelationshipStages(entry, agentId, tenantId);
        }

        seedCapsWeights(profile, agentId, tenantId);
    }

    private void storeRelationshipStages(EntityEnrichmentEntry entry, String agentId, String tenantId) {
        if (entry.relationshipStages() == null || entry.relationshipStages().isEmpty()) {return;}

        for (var stage : entry.relationshipStages()) {
            if (stage.otherAgent() == null || stage.transitions() == null) {continue;}

            for (var t : stage.transitions()) {
                var attrs = new HashMap<String, String>();
                attrs.put(RelationshipAttributeKeys.OTHER_AGENT, stage.otherAgent());
                attrs.put(RelationshipAttributeKeys.SOURCE_EVENT_TYPE, "stage-transition");
                attrs.put("stage-from", t.from());
                attrs.put("stage-to", t.to());
                if (t.period() != null) {attrs.put("period", t.period());}
                if (t.trigger() != null) {attrs.put("trigger", t.trigger());}
                attrs.put("provenance", "biographical-import");

                String text = "Relationship with " + stage.otherAgent()
                              + " transitioned from " + t.from() + " to " + t.to();

                memoryStore.store(MemoryInput.of(
                                                     Subject.of("agent", agentId), RelationshipEvents.DOMAIN, tenantId, text)
                                             .withAttributes(attrs));
            }
        }
    }

    private void seedCapsWeights(BiographyProfile profile, String agentId, String tenantId) {
        if (capsEngine == null) {return;}

        boolean hasActivations = profile.entityEnrichments().stream()
                                        .flatMap(e -> e.capsPatterns() != null ? e.capsPatterns().stream() : Stream.empty())
                                        .anyMatch(p -> p.inputActivations() != null && !p.inputActivations().isEmpty());
        if (!hasActivations) {return;}

        DispositionAxes disposition = resolveDisposition(profile);
        var             state       = capsEngine.initializeAgent(tenantId, agentId, disposition);

        for (var entry : profile.entityEnrichments()) {
            if (entry.capsPatterns() == null) {continue;}
            for (var pattern : entry.capsPatterns()) {
                if (pattern.inputActivations() == null || pattern.inputActivations().isEmpty()) {continue;}
                double intensity = pattern.frequency() != null ? pattern.frequency() / 5.0 : 0.5;
                double salience  = pattern.confidence() != null ? pattern.confidence() : 1.0;
                state = capsEngine.updateWeights(state, pattern.inputActivations(),
                                                 intensity, 1.0, salience, "variable_ratio");
            }
        }

        capsEngine.saveState(state);
    }

    private DispositionAxes resolveDisposition(BiographyProfile profile) {
        if (profile.dispositionProfiles().isEmpty()) {
            return new DispositionAxes("moderate", "moderate", "calculated", "moderate", "analytical");
        }
        var dp = profile.dispositionProfiles().getFirst();
        return new DispositionAxes(
                dp.socialOrient() != null ? dp.socialOrient() : "moderate",
                dp.ruleFollowing() != null ? dp.ruleFollowing() : "moderate",
                dp.riskAppetite() != null ? dp.riskAppetite() : "calculated",
                dp.autonomy() != null ? dp.autonomy() : "moderate",
                dp.conflictMode() != null ? dp.conflictMode() : "analytical");
    }


}
