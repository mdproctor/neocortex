package io.casehub.neocortex.mindmap.intelligence;

import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.neocortex.mindmap.MindMapConfidenceDefaults;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.MutationContext;
import io.casehub.neocortex.mindmap.NodeInput;
import io.casehub.neocortex.mindmap.SubgraphTypes;
import io.casehub.neocortex.mindmap.intelligence.consolidation.RetrievalAccessTracker;
import io.casehub.platform.api.identity.PrincipalId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@ApplicationScoped
public class ConversationBridge {

    private final MindMapStore store;
    private final RetrievalAccessTracker accessTracker;
    private final Consumer<ExtractionRequested> eventSink;

    @Inject
    public ConversationBridge(MindMapStore store,
                              Instance<RetrievalAccessTracker> accessTracker,
                              Event<ExtractionRequested> extractionEvent) {
        this.store = store;
        this.accessTracker = accessTracker.isResolvable() ? accessTracker.get() : null;
        this.eventSink = extractionEvent::fireAsync;
    }

    ConversationBridge(MindMapStore store,
                       RetrievalAccessTracker accessTracker,
                       Consumer<ExtractionRequested> eventSink) {
        this.store = store;
        this.accessTracker = accessTracker;
        this.eventSink = eventSink;
    }

    public SegmentationResult process(String cleanedText, String tenantId,
                                      List<String> recentEntityNames,
                                      PrincipalId principalId,
                                      ConfidenceOrigin confidenceOrigin) {
        if (cleanedText == null || cleanedText.isBlank()) {
            return SegmentationResult.EMPTY;
        }

        MutationContext.set("conversation-bridge");
        try {
            List<TextSegment> segments   = segment(cleanedText);
            String            subgraphId = findOrCreateGeneralSubgraph(tenantId);

            ConfidenceOrigin origin = confidenceOrigin != null ? confidenceOrigin : ConfidenceOrigin.STATED;

            List<String> createdNodeIds = new ArrayList<>();
            for (TextSegment seg : segments) {
                String nodeId = store.addNode(
                        NodeInput.of(seg.title(), subgraphId)
                                 .withConfidence(MindMapConfidenceDefaults.forOrigin(origin, Instant.now()))
                                 .withProvenance("conversation-bridge")
                                 .withPrincipalId(principalId)
                                 .withProperties(Map.of(
                                         "body", seg.body(),
                                         "topic", seg.topic())),
                        tenantId);
                createdNodeIds.add(nodeId);
            }

            if (accessTracker != null) {
                createdNodeIds.forEach(accessTracker::recordAccess);
            }

            eventSink.accept(new ExtractionRequested(
                    cleanedText, tenantId, recentEntityNames, createdNodeIds, principalId));

            return new SegmentationResult(createdNodeIds, segments.size());
        } finally {
            MutationContext.clear();
        }
    }

    List<TextSegment> segment(String text) {
        String[] paragraphs = text.split("\\n\\n+");
        List<TextSegment> segments = new ArrayList<>();
        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;
            String title = trimmed.length() > 60
                ? trimmed.substring(0, 60).trim() + "..."
                : trimmed;
            segments.add(new TextSegment(title, trimmed, "general"));
        }
        if (segments.isEmpty() && !text.isBlank()) {
            String title = text.strip().length() > 60
                ? text.strip().substring(0, 60).trim() + "..."
                : text.strip();
            segments.add(new TextSegment(title, text.strip(), "general"));
        }
        return segments;
    }

    private String findOrCreateGeneralSubgraph(String tenantId) {
        return SubgraphUtils.ensureSubgraph(store, "General", SubgraphTypes.GENERAL, tenantId);
    }
}
