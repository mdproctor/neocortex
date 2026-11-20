package io.casehub.neocortex.mindmap.intelligence.consolidation;

import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.platform.agent.session.SessionPipeline;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.logging.Logger;

public class EdgeInferencePhase implements ConsolidationPhase {

    private static final Logger LOG = Logger.getLogger(EdgeInferencePhase.class.getName());

    final MindMapStore mindMapStore;
    final CaseMemoryStore memoryStore;
    final int degreeThreshold;
    final int maxCandidates;
    private volatile SessionPipeline sessionPipeline;

    @Inject
    public EdgeInferencePhase(MindMapStore mindMapStore,
                              Instance<CaseMemoryStore> memoryStoreInstance,
                              Instance<EdgeInferenceConfig> configInstance) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStoreInstance.isResolvable() ? memoryStoreInstance.get() : null;
        EdgeInferenceConfig c = configInstance.isResolvable() ? configInstance.get() : null;
        this.degreeThreshold = c != null ? c.degreeThreshold() : 1;
        this.maxCandidates = c != null ? c.maxCandidates() : 30;
    }

    EdgeInferencePhase(MindMapStore mindMapStore, CaseMemoryStore memoryStore,
                       int degreeThreshold, int maxCandidates) {
        this.mindMapStore = mindMapStore;
        this.memoryStore = memoryStore;
        this.degreeThreshold = degreeThreshold;
        this.maxCandidates = maxCandidates;
    }

    @Override
    public String name() { return "edge-inference"; }

    @Override
    public void setSessionPipeline(SessionPipeline pipeline) {
        this.sessionPipeline = pipeline;
    }

    SessionPipeline sessionPipeline() { return sessionPipeline; }

    @Override
    public void run(String tenantId, List<String> subgraphPriority) {
        if (sessionPipeline == null || memoryStore == null) {
            LOG.fine("Edge inference skipped: no SessionPipeline or CaseMemoryStore");
            return;
        }
    }
}
