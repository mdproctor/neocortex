package io.casehub.neocortex.cognitive.observability.spring;

import io.casehub.neocortex.cognitive.index.CognitiveProfile;
import io.casehub.neocortex.cognitive.index.DomainActivation;
import io.casehub.neocortex.cognitive.observability.CognitionService;
import io.casehub.neocortex.cognitive.observability.SnapshotStore;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.mindmap.MindMapStore;
import io.casehub.neocortex.mindmap.intelligence.ActivityQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(CognitionService.class)
public class CognitionAutoConfiguration {

    @Bean
    public CognitionService cognitionService(MindMapStore store,
                                             ObjectProvider<CognitiveProfile> cognitiveProfile,
                                             ObjectProvider<SnapshotStore> snapshotStore,
                                             ObjectProvider<CaseMemoryStore> memoryStore,
                                             ObjectProvider<DomainActivation> domainActivation,
                                             ObjectProvider<ActivityQueryService> activityQueryService) {
        return new CognitionService(
                store,
                cognitiveProfile.getIfAvailable(),
                snapshotStore.getIfAvailable(),
                memoryStore.getIfAvailable(),
                domainActivation.getIfAvailable(),
                activityQueryService.getIfAvailable());
    }
}
