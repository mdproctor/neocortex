package io.casehub.neocortex.cognitive.observability;

import io.casehub.neocortex.cognitive.index.CognitiveProfile;
import io.casehub.neocortex.cognitive.index.DomainActivation;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.intelligence.ActivityQueryService;
import io.casehub.neocortex.mindmap.intelligence.consolidation.CognitiveAttentionAccumulator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CognitionBeans {

    @Produces
    @ApplicationScoped
    public CognitionService cognitionService(MindMapStore store,
                                             Instance<CognitiveProfile> cognitiveProfile,
                                             Instance<SnapshotStore> snapshotStore,
                                             Instance<CaseMemoryStore> memoryStore,
                                             Instance<DomainActivation> domainActivation,
                                             Instance<ActivityQueryService> activityQueryService,
                                             Instance<CognitiveAttentionAccumulator> attentionAccumulator) {
        return new CognitionService(
                store,
                cognitiveProfile.isResolvable() ? cognitiveProfile.get() : null,
                snapshotStore.isResolvable() ? snapshotStore.get() : null,
                memoryStore.isResolvable() ? memoryStore.get() : null,
                domainActivation.isResolvable() ? domainActivation.get() : null,
                activityQueryService.isResolvable() ? activityQueryService.get() : null,
                attentionAccumulator.isResolvable() ? attentionAccumulator.get() : null);
    }
}
