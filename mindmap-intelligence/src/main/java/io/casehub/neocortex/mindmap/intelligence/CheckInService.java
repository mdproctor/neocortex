package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.mindmap.EdgeInput;
import io.casehub.neocortex.mindmap.MindMapNode;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CheckInService {

    private final MindMapStore store;

    @Inject
    public CheckInService(MindMapStore store) {
        this.store = store;
    }

    public CheckInResult checkIn(CheckInRequest request, String tenantId) {
        String placeNodeId = resolveOrCreatePlace(request, tenantId);
        String activityNodeId = createActivity(request, placeNodeId, tenantId);
        List<String> participantEdgeIds = linkParticipants(request, activityNodeId, tenantId);
        return new CheckInResult(activityNodeId, placeNodeId, participantEdgeIds);
    }

    private String resolveOrCreatePlace(CheckInRequest request, String tenantId) {
        String subgraphId = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PLACE, tenantId);
        MindMapNode existing = store.resolveNode(request.placeName(), subgraphId, tenantId);
        if (existing != null) {
            return existing.id();
        }
        Map<String, String> props = new HashMap<>(request.placeProperties());
        return store.addNode(
            NodeInput.of(request.placeName(), subgraphId)
                .withProperties(props)
                .withProvenance("check-in"),
            tenantId);
    }

    private String createActivity(CheckInRequest request, String placeNodeId, String tenantId) {
        String subgraphId = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.ACTIVITY, tenantId);
        Map<String, String> props = new HashMap<>();
        if (request.date() != null) props.put("date", request.date().toString());
        if (request.activityType() != null) props.put("activityType", request.activityType());
        if (request.notes() != null) props.put("notes", request.notes());

        String activityId = store.addNode(
            NodeInput.of(request.activityName(), subgraphId)
                .withProperties(props)
                .withValidFrom(request.date())
                .withProvenance("check-in"),
            tenantId);

        store.addEdge(EdgeInput.of(activityId, placeNodeId, "at")
                .withProvenance("check-in"), tenantId);

        return activityId;
    }

    private List<String> linkParticipants(CheckInRequest request, String activityNodeId, String tenantId) {
        List<String> edgeIds = new ArrayList<>();
        String personSubgraphId = SubgraphUtils.ensureSubgraph(store, SubgraphTypes.PERSON, tenantId);
        for (var participant : request.participants()) {
            String personId = resolveOrCreatePerson(participant.name(), personSubgraphId, tenantId);
            String edgeId = store.addEdge(
                EdgeInput.of(personId, activityNodeId, "participated")
                    .withProperties(participant.edgeProperties())
                    .withProvenance("check-in"),
                tenantId);
            edgeIds.add(edgeId);
        }
        return edgeIds;
    }

    private String resolveOrCreatePerson(String name, String subgraphId, String tenantId) {
        MindMapNode existing = store.resolveNode(name, subgraphId, tenantId);
        if (existing != null) {
            return existing.id();
        }
        return store.addNode(
            NodeInput.of(name, subgraphId).withProvenance("check-in"),
            tenantId);
    }

}
