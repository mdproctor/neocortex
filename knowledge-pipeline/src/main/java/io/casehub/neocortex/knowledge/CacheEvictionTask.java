package io.casehub.neocortex.knowledge;

import io.casehub.neocortex.knowledge.cache.CacheEvictionScheduler;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CacheEvictionTask {

    @Inject
    CacheEvictionScheduler scheduler;

    @Scheduled(every = "${casehub.knowledge.cache.eviction-interval:24h}")
    void runEviction() {
        scheduler.runEviction();
    }
}
